/*
 * Copyright 2026 Percona LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * DC-level circuit breaker for Hetzner Cloud provisioning.
 * Tracks consecutive failures per datacenter location and short-circuits
 * provisioning attempts to broken DCs, forcing failover to healthy ones.
 */
package cloud.dnation.jenkins.plugins.hetzner;

import cloud.dnation.jenkins.plugins.hetzner.metrics.HetznerMetricProvider;
import com.thoughtworks.xstream.annotations.XStreamAlias;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@XStreamAlias("dcCircuitBreaker")
class DcCircuitBreaker {

    enum State { CLOSED, OPEN, HALF_OPEN }

    private static final int FAILURE_THRESHOLD = 2;
    private static final long RESET_TIMEOUT_MS = 5 * 60 * 1000; // 5 minutes
    // v103.percona.26: forced-reset TTL for HALF_OPEN. If the probe-holder
    // dies between consuming the lease and calling recordSuccess/recordFailure
    // (thread crash, OOM, non-DC-attributable bootstrap_other exception
    // path), the breaker would otherwise be pinned in HALF_OPEN forever
    // with no lease. After this TTL, isProbeable() re-arms the lease so a
    // fresh probe can re-evaluate the DC.
    private static final long HALF_OPEN_STALE_TTL_MS = 2 * RESET_TIMEOUT_MS; // 10 minutes

    // Not final: XStream deserialization assigns fields directly, and
    // afterLoad() may fill in a fallback location/arch from the persisted
    // map key if older XML omitted them.
    @Getter
    private String location;
    // v103.percona.25: per-arch breaker key. One of {amd64, arm64, unknown}
    // via HetznerMetricProvider.archOf(). Legacy XML (pre-v25) lacks this
    // field; afterLoad() fills it from the decoded map key on first load.
    @Getter
    private String arch;
    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private long openedAt = 0;
    private long lastSuccessAt = System.currentTimeMillis();
    private long lastFailureAt = 0;

    // v103.percona.26: HALF_OPEN single-probe lease. true = lease is available
    // (next tryAcquireProbe() caller in HALF_OPEN state gets the probe).
    // Re-armed whenever the breaker enters HALF_OPEN (OPEN reset-timeout
    // elapse) and when HALF_OPEN_STALE_TTL_MS elapses without a recorded
    // outcome. Consumed by tryAcquireProbe() only; isProbeable() is a
    // non-consuming peek used by filterHealthy/sortByHealth so a *filter*
    // operation does not steal the probe from the actual provisioner.
    // Transient: in-memory only; meaningless across JVM restart.
    private transient boolean halfOpenProbeAvailable;
    private transient long halfOpenEnteredAt;
    // v103.percona.32: set by detach() when resetAll() drops this instance from
    // the registry. A late outcome recorded on a dropped instance must not
    // write the shared per-label gauges, which now belong to the replacement.
    private transient boolean detached;

    DcCircuitBreaker(String location, String arch) {
        this.location = location;
        this.arch = arch;
        // Initialize gauge with starting state so panels render immediately
        // instead of "no data" until the first transition.
        HetznerMetricProvider.DC_BREAKER_STATE.labels(location, arch).set(State.CLOSED.ordinal());
        // v103.percona.32: pre-create the closes counter child at 0 so the
        // first increment (often on load, before the first scrape) is visible
        // to increase() and rate().
        HetznerMetricProvider.DC_HEALTH_STALE_HALF_OPEN_CLOSES.labels(location, arch);
    }

    /**
     * Record a state transition to Prometheus. Called from the four sites
     * where {@code state} is mutated (isHealthy reset, recordSuccess close,
     * recordFailure open, getState lazy reset).
     */
    private void recordTransition(State from, State to) {
        gaugeState(to);
        HetznerMetricProvider.DC_BREAKER_TRANSITIONS.labels(location, arch, from.name(), to.name()).inc();
    }

    /** Write the state gauge unless this instance was dropped by resetAll(). */
    private void gaugeState(State value) {
        if (!detached) {
            HetznerMetricProvider.DC_BREAKER_STATE.labels(location, arch).set(value.ordinal());
        }
    }

    /** Write the consecutive-failures gauge unless this instance was dropped by resetAll(). */
    private void gaugeFailures(int value) {
        if (!detached) {
            HetznerMetricProvider.DC_BREAKER_CONSECUTIVE_FAILURES.labels(location, arch).set(value);
        }
    }

    /**
     * Non-consuming peek: would this DC accept a probe right now?
     * Used by {@link DcHealthTracker#filterHealthy} and
     * {@link DcHealthTracker#sortByHealth} which need a read-only health
     * view; consuming the probe-lease inside a filter would prevent the
     * actual provisioner from ever attempting the probe (Codex post-impl
     * finding, v103.percona.26).
     *
     * <p>CLOSED: always yes. OPEN: yes only if reset timeout has elapsed
     * (and we lazily transition to HALF_OPEN, arming the lease).
     * HALF_OPEN: yes if the lease is available OR if the HALF_OPEN window
     * is older than {@link #HALF_OPEN_STALE_TTL_MS} (forced re-arm to
     * handle probe-holder-died-without-recording).
     */
    synchronized boolean isProbeable() {
        if (state == State.CLOSED) {
            return true;
        }
        if (state == State.OPEN) {
            if (System.currentTimeMillis() - openedAt >= RESET_TIMEOUT_MS) {
                enterHalfOpen("reset timeout elapsed");
                // Fall through to HALF_OPEN branch.
            } else {
                return false;
            }
        }
        // HALF_OPEN.
        rearmStaleHalfOpenLeaseIfNeeded();
        return halfOpenProbeAvailable;
    }

    /**
     * Consuming probe lease acquisition. Returns true exactly once per
     * HALF_OPEN window (the lease-holder); subsequent callers see false
     * until {@link #recordSuccess()} closes the breaker (lease becomes
     * irrelevant) or {@link #recordFailure()} reopens it. CLOSED state
     * always returns true (no lease semantics in CLOSED).
     *
     * <p>Called by {@link cloud.dnation.jenkins.plugins.hetzner.NodeCallable}
     * just before {@code createServer()} so the lease is consumed by the
     * actual provisioner, not by an earlier filter or sort pass.
     */
    synchronized boolean tryAcquireProbe() {
        if (state == State.CLOSED) {
            return true;
        }
        if (state == State.OPEN) {
            if (System.currentTimeMillis() - openedAt >= RESET_TIMEOUT_MS) {
                enterHalfOpen("reset timeout elapsed");
            } else {
                return false;
            }
        }
        // HALF_OPEN.
        rearmStaleHalfOpenLeaseIfNeeded();
        if (halfOpenProbeAvailable) {
            halfOpenProbeAvailable = false;
            // v103.percona.32: the lease TTL and the stale-close in-flight
            // guard both measure from the hand-out, as the re-arm Javadoc
            // already describes ("holder did not record outcome within").
            halfOpenEnteredAt = System.currentTimeMillis();
            return true;
        }
        return false;
    }

    /** Transition OPEN -> HALF_OPEN and arm a fresh probe lease. */
    private void enterHalfOpen(String reason) {
        State previous = state;
        state = State.HALF_OPEN;
        halfOpenProbeAvailable = true;
        halfOpenEnteredAt = System.currentTimeMillis();
        log.info("DC {} arch {} circuit breaker: {} -> HALF_OPEN ({})",
                location, arch, previous, reason);
        recordTransition(previous, State.HALF_OPEN);
    }

    /**
     * Re-arm the HALF_OPEN probe lease if the window has been open longer
     * than {@link #HALF_OPEN_STALE_TTL_MS} without a recorded outcome.
     * Handles the probe-holder-died-without-recording case (SA-R2 finding):
     * a NodeCallable that consumed the lease but then crashed or hit a
     * non-DC-attributable exit path without calling recordSuccess /
     * recordFailure would otherwise pin the breaker out of rotation
     * forever.
     */
    private void rearmStaleHalfOpenLeaseIfNeeded() {
        if (!halfOpenProbeAvailable
                && halfOpenEnteredAt > 0
                && System.currentTimeMillis() - halfOpenEnteredAt >= HALF_OPEN_STALE_TTL_MS) {
            halfOpenProbeAvailable = true;
            halfOpenEnteredAt = System.currentTimeMillis();
            HetznerMetricProvider.DC_HEALTH_STALE_HALF_OPEN_RESETS.labels(location, arch).inc();
            log.warn("DC {} arch {} circuit breaker: stale HALF_OPEN lease re-armed "
                    + "(probe-holder did not record outcome within {}ms)",
                    location, arch, HALF_OPEN_STALE_TTL_MS);
        }
    }

    /**
     * v103.percona.31: close a HALF_OPEN breaker that has recorded no probe
     * outcome for {@code staleTtlMs}. A HALF_OPEN breaker only leaves that
     * state through a recorded outcome, and the probe only happens when a
     * provision request reaches this DC. When the routing layer diverts all
     * traffic away from Hetzner on the unhealthy flag derived from this
     * breaker's gauge, no probe ever arrives and the breaker stays HALF_OPEN
     * for good (ps80 arm64, 2026-06-09 to 2026-09-17). Closing
     * optimistically lets the next real provision decide: success keeps it
     * CLOSED, two failures reopen it.
     *
     * <p>Age clock: the persisted {@code openedAt} / {@code lastFailureAt}
     * only, so the rule reads "no recorded outcome for {@code staleTtlMs}
     * since the breaker last opened or failed". Both fields survive a
     * restart and neither is touched by the lease re-arm or by
     * {@link #afterLoad}. The transient {@code halfOpenEnteredAt} is not the
     * clock on purpose: the v26 re-arm and the on-load re-arm stamp it with
     * {@code now}, which would restart the window every 10 minutes or on
     * every restart (v103.percona.32, review finding on .31). A breaker with
     * no usable timestamp at all is treated as stale.
     *
     * <p>In-flight guard (this JVM only): while the probe lease is consumed
     * and was armed less than {@code HALF_OPEN_STALE_TTL_MS} ago, a probe
     * outcome is still expected, so the breaker is left to it. Past that,
     * the v26 re-arm already treats the holder as dead.
     *
     * @param trigger short label for the log line ("on load", "by refresh tick")
     * @return true if this call closed the breaker
     */
    synchronized boolean closeIfStaleHalfOpen(long now, long staleTtlMs, String trigger) {
        if (state != State.HALF_OPEN) {
            return false;
        }
        long since = Math.max(openedAt, lastFailureAt);
        if (since > 0 && now - since < staleTtlMs) {
            return false;
        }
        if (!halfOpenProbeAvailable && halfOpenEnteredAt > 0
                && now - halfOpenEnteredAt < HALF_OPEN_STALE_TTL_MS) {
            return false;
        }
        state = State.CLOSED;
        consecutiveFailures = 0;
        openedAt = 0;
        halfOpenProbeAvailable = false;
        halfOpenEnteredAt = 0;
        gaugeFailures(0);
        HetznerMetricProvider.DC_HEALTH_STALE_HALF_OPEN_CLOSES.labels(location, arch).inc();
        log.warn("DC {} arch {} circuit breaker: HALF_OPEN -> CLOSED {} (no probe outcome for {}, "
                + "closing so the next provision re-evaluates the DC)",
                location, arch, trigger, since > 0 ? (now - since) + "ms" : "an unknown time");
        // Housekeeping, not an outcome: the gauge moves, the transitions
        // counter (plotted as recoveries) does not. The dedicated closes
        // counter above carries the event. Same rule as the stale-OPEN reset.
        gaugeState(State.CLOSED);
        return true;
    }

    /**
     * Record a successful provisioning in this DC.
     * Resets the circuit breaker to CLOSED regardless of current state.
     */
    synchronized void recordSuccess() {
        State previous = state;
        consecutiveFailures = 0;
        state = State.CLOSED;
        lastSuccessAt = System.currentTimeMillis();
        if (previous != State.CLOSED) {
            log.info("DC {} arch {} circuit breaker: {} -> CLOSED (provisioning succeeded)",
                    location, arch, previous);
            recordTransition(previous, State.CLOSED);
        }
        gaugeFailures(0);
    }

    /**
     * Record a failed provisioning in this DC.
     * After FAILURE_THRESHOLD consecutive failures, opens the circuit breaker.
     */
    synchronized void recordFailure() {
        consecutiveFailures++;
        lastFailureAt = System.currentTimeMillis();
        gaugeFailures(consecutiveFailures);
        if (state == State.HALF_OPEN) {
            // Probe failed, go back to OPEN
            state = State.OPEN;
            openedAt = System.currentTimeMillis();
            log.warn("DC {} arch {} circuit breaker: HALF_OPEN -> OPEN (probe failed, {} consecutive failures)",
                    location, arch, consecutiveFailures);
            recordTransition(State.HALF_OPEN, State.OPEN);
        } else if (consecutiveFailures >= FAILURE_THRESHOLD) {
            state = State.OPEN;
            openedAt = System.currentTimeMillis();
            log.warn("DC {} arch {} circuit breaker: CLOSED -> OPEN ({} consecutive failures)",
                    location, arch, consecutiveFailures);
            recordTransition(State.CLOSED, State.OPEN);
        } else {
            log.info("DC {} arch {} provisioning failed ({}/{} before circuit opens)",
                    location, arch, consecutiveFailures, FAILURE_THRESHOLD);
        }
    }

    synchronized State getState() {
        // Re-evaluate in case reset timeout elapsed.
        //
        // Intentionally does NOT emit DC_BREAKER_TRANSITIONS counter here.
        // getState() is called by getters (Script Console, tests, dashboards)
        // and would inflate the counter every time the timeout has elapsed
        // until isHealthy() actually transitions. We update only the gauge
        // so the state stays consistent with what callers observe; the
        // transition counter is bumped exactly once when isHealthy() drives
        // the state change.
        if (state == State.OPEN && System.currentTimeMillis() - openedAt >= RESET_TIMEOUT_MS) {
            state = State.HALF_OPEN;
            // v103.percona.26: arm probe lease on lazy-reset too, so a
            // subsequent isProbeable()/tryAcquireProbe() in this HALF_OPEN
            // window can consume it.
            halfOpenProbeAvailable = true;
            halfOpenEnteredAt = System.currentTimeMillis();
            gaugeState(State.HALF_OPEN);
        }
        return state;
    }

    synchronized int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    synchronized long getLastSuccessAt() {
        return lastSuccessAt;
    }

    synchronized long getLastFailureAt() {
        return lastFailureAt;
    }

    /**
     * Post-deserialization hook called from {@link DcHealthTracker#load()}.
     * Returns true when it changed the persisted state (stale-OPEN reset or
     * stale-HALF_OPEN close), so the caller can persist the converged file
     * once instead of reloading and re-closing the same entry on every boot.
     * Restores the Prometheus gauges that are not persisted (so dashboards
     * render the loaded state right after master boot) and applies the
     * stale-OPEN TTL so an old transient outage does not pin a DC out of
     * rotation after a long restart.
     *
     * <p>{@code fallbackArch} fills in this field for pre-v25 XML where
     * the breaker map was keyed solely by location and the breaker itself
     * had no arch coordinate. {@link DcHealthTracker#load()} synthesizes
     * one breaker per arch from each legacy entry and feeds the chosen
     * arch here.
     */
    synchronized boolean afterLoad(String fallbackLocation, String fallbackArch,
                                   long now, long staleOpenTtlMs) {
        if (location == null) {
            location = fallbackLocation;
        }
        if (arch == null) {
            arch = fallbackArch;
        }
        // v103.percona.33: XStream bypasses the constructor, so a breaker
        // loaded from disk had no closes-counter child until its first close
        // created it at 1, invisible to increase() and rate(). Pre-create it
        // here as the constructor does for a fresh breaker.
        HetznerMetricProvider.DC_HEALTH_STALE_HALF_OPEN_CLOSES.labels(location, arch);
        boolean changed = false;
        if (state == State.OPEN && now - openedAt >= staleOpenTtlMs) {
            changed = true;
            log.info("DC {} arch {} circuit breaker: OPEN -> CLOSED on load (stale, last failure {}ms ago)",
                    location, arch, now - openedAt);
            state = State.CLOSED;
            consecutiveFailures = 0;
            openedAt = 0;
            HetznerMetricProvider.DC_HEALTH_STALE_OPEN_RESETS.labels(location, arch).inc();
        }
        if (state == State.HALF_OPEN) {
            // v103.percona.31: the probe lease is transient, so a reloaded
            // HALF_OPEN breaker could never be probed and pinned the arm64
            // routing flag on the fallback across every restart. Older than
            // the TTL: close it, the next provision re-evaluates the DC.
            // Younger: keep the state but arm a fresh lease.
            if (closeIfStaleHalfOpen(now, staleOpenTtlMs, "on load")) {
                changed = true;
            } else {
                halfOpenProbeAvailable = true;
                halfOpenEnteredAt = now;
            }
        }
        gaugeState(state);
        gaugeFailures(consecutiveFailures);
        return changed;
    }

    /**
     * v103.percona.31: report this breaker as CLOSED on the gauges without a
     * state transition. Used by {@link DcHealthTracker#resetAll()}: clearing
     * the registry does not clear the per-label gauge series, so a health
     * probe reading the metrics text kept seeing the pre-reset HALF_OPEN
     * after {@code jenkins hetzner reset} (2026-09-17).
     */
    synchronized void clearGauges() {
        HetznerMetricProvider.DC_BREAKER_STATE.labels(location, arch).set(State.CLOSED.ordinal());
        HetznerMetricProvider.DC_BREAKER_CONSECUTIVE_FAILURES.labels(location, arch).set(0);
    }

    /**
     * v103.percona.32: called by {@link DcHealthTracker#resetAll()} on every
     * instance it dropped. Reports CLOSED once, then silences this instance's
     * gauge writes for good, so an outcome recorded by a thread that fetched
     * the old instance just before the reset cannot pin the gauge at OPEN
     * with no live breaker left to repair it.
     */
    synchronized void detach() {
        clearGauges();
        detached = true;
    }

    /** Visible for testing. */
    static int failureThreshold() {
        return FAILURE_THRESHOLD;
    }

    /** Visible for testing. */
    static long resetTimeoutMs() {
        return RESET_TIMEOUT_MS;
    }
}
