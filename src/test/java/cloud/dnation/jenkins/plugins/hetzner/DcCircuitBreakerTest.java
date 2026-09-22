/*
 * Copyright 2026 Percona LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 */
package cloud.dnation.jenkins.plugins.hetzner;

import cloud.dnation.jenkins.plugins.hetzner.metrics.HetznerMetricProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DcCircuitBreakerTest {

    @AfterEach
    void tearDown() {
        DcHealthTracker.clearForTest();
    }

    @Test
    void newBreakerStartsClosed() {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1", "amd64");
        assertEquals(DcCircuitBreaker.State.CLOSED, cb.getState());
        assertTrue(cb.tryAcquireProbe());
        assertEquals(0, cb.getConsecutiveFailures());
    }

    @Test
    void singleFailureStaysClosed() {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1", "amd64");
        cb.recordFailure();
        assertEquals(DcCircuitBreaker.State.CLOSED, cb.getState());
        assertTrue(cb.tryAcquireProbe());
        assertEquals(1, cb.getConsecutiveFailures());
    }

    @Test
    void twoConsecutiveFailuresOpensCircuit() {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1", "amd64");
        cb.recordFailure();
        cb.recordFailure();
        assertEquals(DcCircuitBreaker.State.OPEN, cb.getState());
        assertFalse(cb.tryAcquireProbe());
        assertEquals(2, cb.getConsecutiveFailures());
    }

    @Test
    void successResetsFailureCount() {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1", "amd64");
        cb.recordFailure();
        cb.recordSuccess();
        assertEquals(DcCircuitBreaker.State.CLOSED, cb.getState());
        assertTrue(cb.tryAcquireProbe());
        assertEquals(0, cb.getConsecutiveFailures());
    }

    @Test
    void successAfterOpenResetsToClosed() {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1", "amd64");
        cb.recordFailure();
        cb.recordFailure();
        assertEquals(DcCircuitBreaker.State.OPEN, cb.getState());
        // Simulate half-open transition and success
        cb.recordSuccess();
        assertEquals(DcCircuitBreaker.State.CLOSED, cb.getState());
        assertTrue(cb.tryAcquireProbe());
    }

    @Test
    void openTransitionsToHalfOpenAfterTimeout() throws Exception {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1", "amd64");
        cb.recordFailure();
        cb.recordFailure();
        assertEquals(DcCircuitBreaker.State.OPEN, cb.getState());

        // Use reflection to set openedAt to the past
        setOpenedAt(cb, System.currentTimeMillis() - DcCircuitBreaker.resetTimeoutMs() - 1);

        assertEquals(DcCircuitBreaker.State.HALF_OPEN, cb.getState());
        assertTrue(cb.tryAcquireProbe());
    }

    @Test
    void halfOpenFailureReopensCircuit() throws Exception {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1", "amd64");
        cb.recordFailure();
        cb.recordFailure();

        // Force HALF_OPEN by backdating openedAt
        setOpenedAt(cb, System.currentTimeMillis() - DcCircuitBreaker.resetTimeoutMs() - 1);
        assertTrue(cb.tryAcquireProbe()); // transitions to HALF_OPEN

        cb.recordFailure();
        assertEquals(DcCircuitBreaker.State.OPEN, cb.getState());
        assertFalse(cb.tryAcquireProbe());
    }

    @Test
    void halfOpenSuccessCloses() throws Exception {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1", "amd64");
        cb.recordFailure();
        cb.recordFailure();

        setOpenedAt(cb, System.currentTimeMillis() - DcCircuitBreaker.resetTimeoutMs() - 1);
        assertTrue(cb.tryAcquireProbe()); // HALF_OPEN

        cb.recordSuccess();
        assertEquals(DcCircuitBreaker.State.CLOSED, cb.getState());
        assertEquals(0, cb.getConsecutiveFailures());
    }

    /**
     * v103.percona.26: after the breaker enters HALF_OPEN (OPEN reset-timeout
     * elapsed), only the FIRST caller gets the probe lease. Subsequent
     * concurrent callers see false until recordSuccess() closes the breaker
     * or recordFailure() reopens it. Closes the storm path where N queued
     * shards all saw HALF_OPEN as healthy and stampeded the Hetzner API.
     */
    @Test
    void halfOpenIsSingleProbe() throws Exception {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1", "amd64");
        cb.recordFailure();
        cb.recordFailure();
        setOpenedAt(cb, System.currentTimeMillis() - DcCircuitBreaker.resetTimeoutMs() - 1);

        assertTrue(cb.tryAcquireProbe(),
                "first caller in HALF_OPEN window holds the probe lease");
        assertFalse(cb.tryAcquireProbe(),
                "second concurrent caller is denied (lease already taken)");
        assertFalse(cb.tryAcquireProbe(),
                "third caller still denied (lease not released until success/failure)");
        assertEquals(DcCircuitBreaker.State.HALF_OPEN, cb.getState());
    }

    /**
     * v103.percona.26: a fresh probe lease is armed each time the breaker
     * transitions OPEN -> HALF_OPEN. After a probe failure reopens the
     * breaker and another reset-timeout window elapses, the next caller
     * should again hold the lease.
     */
    @Test
    void halfOpenLeaseRearmsAfterReopening() throws Exception {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1", "amd64");
        cb.recordFailure();
        cb.recordFailure();
        setOpenedAt(cb, System.currentTimeMillis() - DcCircuitBreaker.resetTimeoutMs() - 1);

        assertTrue(cb.tryAcquireProbe(), "first probe lease acquired");
        cb.recordFailure(); // probe-holder fails -> back to OPEN
        assertEquals(DcCircuitBreaker.State.OPEN, cb.getState());

        // Advance clock past another reset window; a new lease should be armed.
        setOpenedAt(cb, System.currentTimeMillis() - DcCircuitBreaker.resetTimeoutMs() - 1);
        assertTrue(cb.tryAcquireProbe(),
                "after reopen + timeout, a fresh probe lease is available");
        assertFalse(cb.tryAcquireProbe(),
                "second caller in the new HALF_OPEN window denied");
    }

    /**
     * v103.percona.26: isProbeable() is non-consuming. Two consecutive
     * calls in a HALF_OPEN window both return true (the lease is not
     * consumed by a peek). Critical for filterHealthy/sortByHealth which
     * call into the breaker as part of list filtering; consuming the
     * lease there would steal it from the actual provisioner.
     */
    @Test
    void isProbeableIsNonConsuming() throws Exception {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1", "amd64");
        cb.recordFailure();
        cb.recordFailure();
        setOpenedAt(cb, System.currentTimeMillis() - DcCircuitBreaker.resetTimeoutMs() - 1);

        // Two peeks in a row both succeed (lease not consumed)
        assertTrue(cb.isProbeable(), "first peek sees HALF_OPEN as healthy");
        assertTrue(cb.isProbeable(), "second peek still sees HALF_OPEN healthy (lease intact)");

        // Now the actual consumer takes the lease
        assertTrue(cb.tryAcquireProbe(), "consumer acquires the lease");
        // And the next consumer is denied
        assertFalse(cb.tryAcquireProbe(), "second consumer denied");
        // But isProbeable() now correctly returns false too (lease taken)
        assertFalse(cb.isProbeable(), "peek after consumption returns false");
    }

    /**
     * v103.percona.26: if the probe-holder consumes the lease and then
     * dies without calling recordSuccess/recordFailure (thread crash,
     * non-DC bootstrap exception path that does not record), the breaker
     * is otherwise pinned in HALF_OPEN forever with no lease. After
     * HALF_OPEN_STALE_TTL_MS (2 * RESET_TIMEOUT_MS = 10 min) the lease
     * is re-armed on the next isProbeable()/tryAcquireProbe() call.
     */
    @Test
    void halfOpenLeaseReArmsAfterStaleTtl() throws Exception {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1", "amd64");
        cb.recordFailure();
        cb.recordFailure();
        setOpenedAt(cb, System.currentTimeMillis() - DcCircuitBreaker.resetTimeoutMs() - 1);

        assertTrue(cb.tryAcquireProbe(), "lease acquired");
        assertFalse(cb.tryAcquireProbe(), "lease taken, second caller denied");
        assertEquals(DcCircuitBreaker.State.HALF_OPEN, cb.getState());

        // Simulate the probe-holder dying: backdate halfOpenEnteredAt past
        // the stale TTL. We do this via reflection since the field is
        // private + transient.
        java.lang.reflect.Field f = DcCircuitBreaker.class.getDeclaredField("halfOpenEnteredAt");
        f.setAccessible(true);
        f.setLong(cb, System.currentTimeMillis() - (2L * 6 * 60 * 1000)); // 12 minutes ago

        // The stale lease should re-arm on the next call.
        assertTrue(cb.tryAcquireProbe(), "after stale TTL elapsed, fresh lease available");
        assertFalse(cb.tryAcquireProbe(), "lease consumed; subsequent caller denied");
        assertEquals(DcCircuitBreaker.State.HALF_OPEN, cb.getState(),
                "still in HALF_OPEN until next outcome recorded");
    }

    /**
     * v103.percona.26: getState() also lazily resets OPEN -> HALF_OPEN when
     * the timeout has elapsed. This path must also arm the probe lease so a
     * subsequent tryAcquireProbe() call can consume it; otherwise a getter
     * call would consume the implicit "first caller" semantics without
     * anyone actually being able to probe.
     */
    @Test
    void getStateLazyResetArmsProbeLease() throws Exception {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1", "amd64");
        cb.recordFailure();
        cb.recordFailure();
        setOpenedAt(cb, System.currentTimeMillis() - DcCircuitBreaker.resetTimeoutMs() - 1);

        // getState() lazy-resets OPEN -> HALF_OPEN; lease should be armed.
        assertEquals(DcCircuitBreaker.State.HALF_OPEN, cb.getState());
        assertTrue(cb.tryAcquireProbe(),
                "after getState() lazy-reset, the next isHealthy() holds the lease");
        assertFalse(cb.tryAcquireProbe(),
                "lease is single-use after lazy-reset path too");
    }

    @Test
    void failureThresholdIsTwo() {
        assertEquals(2, DcCircuitBreaker.failureThreshold());
    }

    @Test
    void resetTimeoutIsFiveMinutes() {
        assertEquals(5 * 60 * 1000, DcCircuitBreaker.resetTimeoutMs());
    }

    @Test
    void locationIsPreserved() {
        DcCircuitBreaker cb = new DcCircuitBreaker("nbg1", "amd64");
        assertEquals("nbg1", cb.getLocation());
    }

    @Test
    void concurrentFailuresAreThreadSafe() throws Exception {
        DcCircuitBreaker cb = new DcCircuitBreaker("hel1", "amd64");
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger exceptions = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    for (int j = 0; j < 100; j++) {
                        cb.recordFailure();
                        cb.tryAcquireProbe();
                        cb.recordSuccess();
                        cb.getState();
                    }
                } catch (Exception e) {
                    exceptions.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(0, exceptions.get(), "No exceptions expected from concurrent access");
    }

    @Test
    void timestampsAreRecorded() {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1", "amd64");
        long before = System.currentTimeMillis();
        cb.recordSuccess();
        assertTrue(cb.getLastSuccessAt() >= before);

        before = System.currentTimeMillis();
        cb.recordFailure();
        assertTrue(cb.getLastFailureAt() >= before);
    }

    private static void setOpenedAt(DcCircuitBreaker cb, long value) throws Exception {
        Field f = DcCircuitBreaker.class.getDeclaredField("openedAt");
        f.setAccessible(true);
        f.set(cb, value);
    }

    /**
     * v103.percona.31: a HALF_OPEN breaker that has waited longer than the
     * stale TTL for a probe outcome closes on request, so a routing layer
     * that diverted all traffic away cannot pin it HALF_OPEN forever.
     */
    @Test
    void staleHalfOpenClosesAfterTtl() throws Exception {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1", "arm64");
        cb.recordFailure();
        cb.recordFailure();
        setOpenedAt(cb, System.currentTimeMillis() - DcCircuitBreaker.resetTimeoutMs() - 1);
        assertEquals(DcCircuitBreaker.State.HALF_OPEN, cb.getState());
        long ttl = 30L * 60 * 1000;
        setOpenedAt(cb, System.currentTimeMillis() - ttl - 1);
        setLastFailureAt(cb, System.currentTimeMillis() - ttl - 1);
        double before = HetznerMetricProvider.DC_HEALTH_STALE_HALF_OPEN_CLOSES.labels("fsn1", "arm64").get();

        assertTrue(cb.closeIfStaleHalfOpen(System.currentTimeMillis(), ttl, "test"));

        assertEquals(DcCircuitBreaker.State.CLOSED, cb.getState());
        assertEquals(0, cb.getConsecutiveFailures());
        assertEquals(0.0, HetznerMetricProvider.DC_BREAKER_STATE.labels("fsn1", "arm64").get(), 0.0001);
        assertEquals(before + 1,
                HetznerMetricProvider.DC_HEALTH_STALE_HALF_OPEN_CLOSES.labels("fsn1", "arm64").get(), 0.0001);
        assertTrue(cb.tryAcquireProbe(), "a CLOSED breaker accepts provisioning again");
    }

    /** A HALF_OPEN breaker still inside the TTL keeps waiting for its probe. */
    @Test
    void youngHalfOpenIsNotClosedBySweep() throws Exception {
        DcCircuitBreaker cb = new DcCircuitBreaker("hel1", "arm64");
        cb.recordFailure();
        cb.recordFailure();
        setOpenedAt(cb, System.currentTimeMillis() - DcCircuitBreaker.resetTimeoutMs() - 1);
        assertEquals(DcCircuitBreaker.State.HALF_OPEN, cb.getState());

        assertFalse(cb.closeIfStaleHalfOpen(System.currentTimeMillis(), 30L * 60 * 1000, "test"));
        assertEquals(DcCircuitBreaker.State.HALF_OPEN, cb.getState());
    }

    /** Only HALF_OPEN is eligible: CLOSED and OPEN breakers are untouched even with a zero TTL. */
    @Test
    void closeIfStaleHalfOpenIgnoresOpenAndClosed() {
        DcCircuitBreaker closed = new DcCircuitBreaker("nbg1", "arm64");
        assertFalse(closed.closeIfStaleHalfOpen(System.currentTimeMillis(), 0, "test"));
        assertEquals(DcCircuitBreaker.State.CLOSED, closed.getState());

        DcCircuitBreaker open = new DcCircuitBreaker("nbg1", "amd64");
        open.recordFailure();
        open.recordFailure();
        assertFalse(open.closeIfStaleHalfOpen(System.currentTimeMillis(), 0, "test"));
        assertEquals(DcCircuitBreaker.State.OPEN, open.getState());
    }

    private static void setHalfOpenEnteredAt(DcCircuitBreaker cb, long value) throws Exception {
        java.lang.reflect.Field f = DcCircuitBreaker.class.getDeclaredField("halfOpenEnteredAt");
        f.setAccessible(true);
        f.set(cb, value);
    }

    private static void setLastFailureAt(DcCircuitBreaker cb, long value) throws Exception {
        java.lang.reflect.Field f = DcCircuitBreaker.class.getDeclaredField("lastFailureAt");
        f.setAccessible(true);
        f.set(cb, value);
    }

    private static void setState(DcCircuitBreaker cb, DcCircuitBreaker.State value) throws Exception {
        java.lang.reflect.Field f = DcCircuitBreaker.class.getDeclaredField("state");
        f.setAccessible(true);
        f.set(cb, value);
    }

    /** Drive a breaker to HALF_OPEN whose last failure is {@code failedAgoMs} in the past. */
    private static DcCircuitBreaker halfOpenFailedAgo(long failedAgoMs) throws Exception {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1", "arm64");
        cb.recordFailure();
        cb.recordFailure();
        setOpenedAt(cb, System.currentTimeMillis() - failedAgoMs);
        setLastFailureAt(cb, System.currentTimeMillis() - failedAgoMs);
        assertEquals(DcCircuitBreaker.State.HALF_OPEN, cb.getState());
        return cb;
    }

    private static final long TTL_30_MIN = 30L * 60 * 1000;

    /**
     * v103.percona.32: the lease re-arm stamps the transient entry time with
     * now every 10 minutes. The age clock must ignore it, otherwise a breaker
     * whose lease keeps getting consumed without an outcome never looks stale.
     */
    @Test
    void leaseRearmDoesNotRestartTheStaleClock() throws Exception {
        DcCircuitBreaker cb = halfOpenFailedAgo(TTL_30_MIN + 60_000);
        setHalfOpenEnteredAt(cb, System.currentTimeMillis()); // as if re-armed just now
        assertTrue(cb.tryAcquireProbe(), "lease available after the re-arm");
        setHalfOpenEnteredAt(cb, System.currentTimeMillis() - 11L * 60 * 1000); // holder silent past the lease TTL

        assertTrue(cb.closeIfStaleHalfOpen(System.currentTimeMillis(), TTL_30_MIN, "test"));
        assertEquals(DcCircuitBreaker.State.CLOSED, cb.getState());
    }

    /** A probe that consumed the lease less than 10 minutes ago still owns the outcome. */
    @Test
    void inFlightProbeDefersTheClose() throws Exception {
        DcCircuitBreaker cb = halfOpenFailedAgo(TTL_30_MIN + 60_000);
        assertTrue(cb.tryAcquireProbe(), "probe consumes the lease");
        setHalfOpenEnteredAt(cb, System.currentTimeMillis() - 2L * 60 * 1000);

        assertFalse(cb.closeIfStaleHalfOpen(System.currentTimeMillis(), TTL_30_MIN, "test"),
                "outcome still expected from the in-flight probe");
        assertEquals(DcCircuitBreaker.State.HALF_OPEN, cb.getState());

        cb.recordFailure();
        assertEquals(DcCircuitBreaker.State.OPEN, cb.getState(), "the probe's failure still reopens");
    }

    /** No usable timestamp at all (hand-edited or ancient XML) counts as stale. */
    @Test
    void halfOpenWithoutTimestampsClosesAsStale() throws Exception {
        DcCircuitBreaker cb = new DcCircuitBreaker("nbg1", "arm64");
        setState(cb, DcCircuitBreaker.State.HALF_OPEN);
        setOpenedAt(cb, 0);
        setLastFailureAt(cb, 0);

        assertTrue(cb.closeIfStaleHalfOpen(System.currentTimeMillis(), TTL_30_MIN, "test"));
        assertEquals(DcCircuitBreaker.State.CLOSED, cb.getState());
    }

    /** The newer of openedAt and lastFailureAt is the clock, whichever it is. */
    @Test
    void newerTimestampIsTheClock() throws Exception {
        DcCircuitBreaker recentFailure = halfOpenFailedAgo(TTL_30_MIN + 60_000);
        setLastFailureAt(recentFailure, System.currentTimeMillis() - 10L * 60 * 1000);
        assertFalse(recentFailure.closeIfStaleHalfOpen(System.currentTimeMillis(), TTL_30_MIN, "test"));

        DcCircuitBreaker recentOpen = halfOpenFailedAgo(TTL_30_MIN + 60_000);
        setOpenedAt(recentOpen, System.currentTimeMillis() - 10L * 60 * 1000);
        assertFalse(recentOpen.closeIfStaleHalfOpen(System.currentTimeMillis(), TTL_30_MIN, "test"));
    }
}
