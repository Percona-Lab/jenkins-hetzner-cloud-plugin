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
 * Static registry of per-DC circuit breakers.
 * Provides sorted template lists that prefer healthy DCs while maintaining
 * backward-compatible random selection when all DCs are healthy.
 *
 * State persistence (PS-11173, v103.percona.21):
 * Breaker state is persisted to $JENKINS_HOME/hetzner-dc-health.xml via
 * XmlFile + Saveable so OPEN circuit breakers survive a JVM restart and
 * the master does not stampede a still-sick DC on first boot. Stale
 * OPEN entries (lastFailureAt older than STALE_OPEN_TTL_MS) load as
 * CLOSED so a transient outage does not pin a DC out of rotation.
 */
package cloud.dnation.jenkins.plugins.hetzner;

import cloud.dnation.jenkins.plugins.hetzner.metrics.HetznerMetricProvider;
import hudson.BulkChange;
import hudson.XmlFile;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Saveable;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
public class DcHealthTracker {

    // v103.percona.25: map keys are "<location>:<arch>" composite strings.
    // String keys (vs a record type) keep XStream serialization simple and
    // backward-compatible with the pre-v25 single-location format; legacy
    // entries are migrated in load() by cloning each into two arch-keyed
    // breakers. The breaker object itself carries (location, arch) so
    // iteration over BREAKERS does not need to parse the key.
    private static final ConcurrentHashMap<String, DcCircuitBreaker> BREAKERS = new ConcurrentHashMap<>();
    // One staleness TTL for both non-CLOSED states: an OPEN breaker older than
    // this resets to CLOSED on load, and a HALF_OPEN breaker whose last open or
    // failure is older than this closes on load and on the refresher sweep
    // (v103.percona.31, single constant since .32).
    private static final long STALE_OPEN_TTL_MS = 30 * 60 * 1000L;
    private static final AtomicBoolean SAVE_SCHEDULED = new AtomicBoolean(false);
    // v103.percona.32: set by every save() request, cleared by the writer before
    // each snapshot. A request that lands during a write is no longer lost.
    private static final AtomicBoolean SAVE_DIRTY = new AtomicBoolean(false);

    // Composite key delimiter for "<location>:<arch>". Hetzner location
    // codes (fsn1/hel1/nbg1/ash/...) never contain ':', so this is safe.
    private static final String KEY_DELIM = ":";

    /** Build the composite map key for a (location, arch) pair. */
    static String encodeKey(String location, String arch) {
        return location + KEY_DELIM + arch;
    }

    /**
     * Split a composite key into {location, arch}. Returns null on a legacy
     * single-segment key so the caller can drive the migration path.
     */
    static String[] decodeKey(String key) {
        if (key == null) {
            return null;
        }
        int idx = key.indexOf(KEY_DELIM);
        if (idx < 0) {
            return null;
        }
        return new String[] { key.substring(0, idx), key.substring(idx + 1) };
    }

    private DcHealthTracker() {
    }

    /**
     * Load persisted breaker state from $JENKINS_HOME/hetzner-dc-health.xml.
     * Runs after PLUGINS_STARTED so {@link Jenkins#XSTREAM2} is available and
     * before any provisioning event would create breakers from scratch.
     * Missing file is a silent no-op (clean install / first-ever load).
     */
    @Initializer(after = InitMilestone.PLUGINS_STARTED)
    public static void load() {
        XmlFile xml = getXmlFile();
        if (!xml.exists()) {
            return;
        }
        try {
            Store store = (Store) xml.read();
            if (store != null && store.breakers != null) {
                long now = System.currentTimeMillis();
                java.util.concurrent.atomic.AtomicBoolean changed = new java.util.concurrent.atomic.AtomicBoolean(false);
                store.breakers.forEach((rawKey, breaker) -> {
                    if (rawKey == null || breaker == null) {
                        return;
                    }
                    String[] decoded = decodeKey(rawKey);
                    if (decoded != null) {
                        // v25+ composite key: load as-is.
                        String location = decoded[0];
                        String arch = decoded[1];
                        if (breaker.afterLoad(location, arch, now, STALE_OPEN_TTL_MS)) {
                            changed.set(true);
                        }
                        BREAKERS.put(rawKey, breaker);
                        return;
                    }
                    // Legacy pre-v25 key: the persisted breaker has no arch
                    // and no way to know which arch tripped it. Clone state
                    // into a breaker for each canonical arch so the loaded
                    // state still gates BOTH arches' templates until normal
                    // operation (or the stale-OPEN TTL) resolves them.
                    String legacyLocation = rawKey;
                    for (String arch : HetznerMetricProvider.ALWAYS_EMIT_ARCHS) {
                        DcCircuitBreaker clone = clonePreV25Breaker(breaker, legacyLocation, arch);
                        if (clone.afterLoad(legacyLocation, arch, now, STALE_OPEN_TTL_MS)) {
                            changed.set(true);
                        }
                        BREAKERS.put(encodeKey(legacyLocation, arch), clone);
                        HetznerMetricProvider.DC_HEALTH_LEGACY_KEYS_MIGRATED
                                .labels(legacyLocation, arch).inc();
                    }
                    log.info("Hetzner DC health: migrated pre-v25 legacy key '{}' into per-arch breakers {}",
                            legacyLocation, Arrays.toString(HetznerMetricProvider.ALWAYS_EMIT_ARCHS.toArray()));
                });
                HetznerMetricProvider.DC_HEALTH_LOADED_BREAKERS.set(BREAKERS.size());
                log.info("Hetzner DC health state loaded from {}: {} breakers",
                        xml.getFile(), BREAKERS.size());
                if (changed.get()) {
                    // v103.percona.32: persist the converged state, otherwise a
                    // master with no provisioning traffic reloads, re-closes
                    // and re-counts the same stale entry on every boot.
                    save();
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to load Hetzner DC health state from {}", xml.getFile(), e);
        }
    }

    /**
     * Build a v25+ breaker carrying the in-memory state of a pre-v25 legacy
     * entry. Used by {@link #load()} during the one-time migration window.
     * Reflective field copy keeps the migration path local to this class
     * without exposing breaker internals.
     */
    private static DcCircuitBreaker clonePreV25Breaker(DcCircuitBreaker source,
                                                       String location, String arch) {
        DcCircuitBreaker clone = new DcCircuitBreaker(location, arch);
        try {
            java.lang.reflect.Field stateField = DcCircuitBreaker.class.getDeclaredField("state");
            stateField.setAccessible(true);
            stateField.set(clone, stateField.get(source));
            java.lang.reflect.Field cfField = DcCircuitBreaker.class.getDeclaredField("consecutiveFailures");
            cfField.setAccessible(true);
            cfField.setInt(clone, cfField.getInt(source));
            java.lang.reflect.Field openedAtField = DcCircuitBreaker.class.getDeclaredField("openedAt");
            openedAtField.setAccessible(true);
            openedAtField.setLong(clone, openedAtField.getLong(source));
            java.lang.reflect.Field lastSuccessField = DcCircuitBreaker.class.getDeclaredField("lastSuccessAt");
            lastSuccessField.setAccessible(true);
            lastSuccessField.setLong(clone, lastSuccessField.getLong(source));
            java.lang.reflect.Field lastFailureField = DcCircuitBreaker.class.getDeclaredField("lastFailureAt");
            lastFailureField.setAccessible(true);
            lastFailureField.setLong(clone, lastFailureField.getLong(source));
        } catch (ReflectiveOperationException e) {
            log.warn("Failed to copy pre-v25 breaker state for {}:{}, starting clone CLOSED",
                    location, arch, e);
        }
        return clone;
    }

    /**
     * Get or create the circuit breaker for a given (DC location, arch)
     * pair. v25+: every breaker is arch-scoped. Pass
     * {@link HetznerMetricProvider#archOf(String)} of the template's
     * server type to derive {@code arch}.
     */
    static DcCircuitBreaker getBreaker(String location, String arch) {
        return BREAKERS.computeIfAbsent(encodeKey(location, arch),
                k -> new DcCircuitBreaker(location, arch));
    }

    /** Record a provisioning failure for the (location, arch). */
    static void recordFailure(String location, String arch) {
        getBreaker(location, arch).recordFailure();
        save();
    }

    /** Record a provisioning success for the (location, arch). */
    static void recordSuccess(String location, String arch) {
        getBreaker(location, arch).recordSuccess();
        save();
    }

    /**
     * Non-consuming health query: would this (location, arch) breaker
     * accept a probe right now? CLOSED -> yes. OPEN with timeout
     * elapsed -> yes (lazily transitions to HALF_OPEN). HALF_OPEN with
     * lease available -> yes. Used by {@link #filterHealthy} and
     * {@link #sortByHealth}; does NOT consume the HALF_OPEN probe lease.
     * v103.percona.26: previously consumed the lease, which made the
     * filter pass steal the probe from the actual provisioner.
     */
    static boolean isHealthy(String location, String arch) {
        return getBreaker(location, arch).isProbeable();
    }

    /**
     * Overload: non-consuming health query for a template. v103.percona.26.
     */
    static boolean isHealthy(HetznerServerTemplate template) {
        return isHealthy(template.getLocation(),
                HetznerMetricProvider.archOf(template.getServerType()));
    }

    /**
     * Consuming probe-lease acquisition: returns true exactly once per
     * HALF_OPEN window, and always for CLOSED. Called at the actual API
     * attempt site ({@link cloud.dnation.jenkins.plugins.hetzner.NodeCallable})
     * so the lease is held by the provisioner that will actually call
     * {@code createServer}, not by an earlier filter or sort pass.
     * v103.percona.26.
     */
    static boolean tryAcquireProbe(String location, String arch) {
        return getBreaker(location, arch).tryAcquireProbe();
    }

    /** Overload: consuming probe-lease acquisition for a template. */
    static boolean tryAcquireProbe(HetznerServerTemplate template) {
        return tryAcquireProbe(template.getLocation(),
                HetznerMetricProvider.archOf(template.getServerType()));
    }

    /**
     * Return only templates whose (location, arch) DC breaker is currently
     * healthy. v103.percona.26. Used as the entry-point storm gate in
     * {@link cloud.dnation.jenkins.plugins.hetzner.HetznerCloud#provision},
     * which must NOT call {@code fetchAllServers} / {@code createServer}
     * against templates whose DCs are OPEN.
     *
     * <p>Distinct from {@link #sortByHealth(List)}, which keeps unhealthy
     * templates at the tail for diagnostic visibility. Both are kept;
     * filterHealthy is the storm-gate, sortByHealth is the rank hint inside
     * NodeCallable's failover loop.
     *
     * @param templates list of matching templates (caller-provided)
     * @return new list containing only currently-healthy templates; never
     *         modifies input, returns empty (never null) for null/empty
     *         input or when no template is healthy.
     */
    static List<HetznerServerTemplate> filterHealthy(List<HetznerServerTemplate> templates) {
        if (templates == null || templates.isEmpty()) {
            return new ArrayList<>();
        }
        return templates.stream()
                .filter(DcHealthTracker::isHealthy)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * Sort templates by DC health: healthy DCs first, unhealthy last.
     * Within each partition, templates are shuffled randomly.
     * When all DCs are healthy (normal case), this is equivalent to a random shuffle.
     *
     * @param templates list of matching templates
     * @return new list sorted by DC health (never modifies input)
     */
    static List<HetznerServerTemplate> sortByHealth(List<HetznerServerTemplate> templates) {
        if (templates == null || templates.size() <= 1) {
            return templates == null ? Collections.emptyList() : new ArrayList<>(templates);
        }

        // v25+: each template's health gate is per (location, arch). Note:
        // HetznerServerTemplate.getServerType() returns a String (the SKU
        // name, e.g. "cpx32" / "cax21"); do NOT chain .getName() on it.
        java.util.function.Predicate<HetznerServerTemplate> healthFor = t -> isHealthy(
                t.getLocation(),
                HetznerMetricProvider.archOf(t.getServerType()));

        List<HetznerServerTemplate> healthy = templates.stream()
                .filter(healthFor)
                .collect(Collectors.toCollection(ArrayList::new));

        List<HetznerServerTemplate> unhealthy = templates.stream()
                .filter(healthFor.negate())
                .collect(Collectors.toCollection(ArrayList::new));

        Collections.shuffle(healthy);
        Collections.shuffle(unhealthy);

        if (!unhealthy.isEmpty()) {
            log.info("DC health ranking: {} healthy, {} unhealthy DCs for {} templates",
                    healthy.size(), unhealthy.size(), templates.size());
        }

        List<HetznerServerTemplate> ranked = new ArrayList<>(templates.size());
        ranked.addAll(healthy);
        ranked.addAll(unhealthy);
        return ranked;
    }

    /**
     * v103.percona.31: close every HALF_OPEN breaker that has recorded no
     * outcome for {@link #STALE_OPEN_TTL_MS}. Called once a minute from
     * {@link HetznerMetricsRefresher}, so the unstick does not depend on any
     * provisioning traffic reaching Hetzner. Persists when anything changed
     * so the CLOSED state survives a restart.
     *
     * <p>v103.percona.32: the sweep first reads {@code getState()}, which
     * takes the lazy OPEN to HALF_OPEN step once the 5 minute reset timeout
     * has elapsed. Nothing else in the plugin calls it without provisioning
     * traffic, so an idle OPEN breaker used to latch exactly like an idle
     * HALF_OPEN one.
     *
     * @return number of breakers closed by this sweep
     */
    static int closeStaleHalfOpen() {
        long now = System.currentTimeMillis();
        int closed = 0;
        for (DcCircuitBreaker breaker : BREAKERS.values()) {
            breaker.getState();
            if (breaker.closeIfStaleHalfOpen(now, STALE_OPEN_TTL_MS, "by refresh tick")) {
                closed++;
            }
        }
        if (closed > 0) {
            save();
        }
        return closed;
    }

    /**
     * Get a snapshot of all tracked breakers. For observability/testing.
     */
    static ConcurrentHashMap<String, DcCircuitBreaker> getAllBreakers() {
        return BREAKERS;
    }

    /**
     * Reset all circuit breakers: the operator {@code jenkins hetzner reset}
     * path (Script Console reflection).
     *
     * <p>v103.percona.31: also reports every dropped breaker as CLOSED on
     * the gauges and zeroes the loaded-breakers gauge. Clearing the registry
     * never cleared the per-label gauge series, so a health probe reading
     * the metrics text still saw the pre-reset HALF_OPEN after a reset
     * (2026-09-17).
     *
     * <p>v103.percona.32: persists the empty map, so a restart right after a
     * reset no longer reloads the state the operator just cleared. Tests
     * that want "clear memory, keep disk" use {@link #clearForTest()}.
     */
    static void resetAll() {
        List<DcCircuitBreaker> dropped = new ArrayList<>(BREAKERS.values());
        BREAKERS.clear();
        dropped.forEach(DcCircuitBreaker::detach);
        HetznerMetricProvider.DC_HEALTH_LOADED_BREAKERS.set(0);
        save();
    }

    /**
     * Test-only: drop the in-memory registry without touching the disk, the
     * way a JVM restart would before {@link #load()} runs. Gauges are left
     * alone so tests can assert what {@code afterLoad()} restores.
     */
    static void clearForTest() {
        BREAKERS.clear();
        HetznerMetricProvider.DC_HEALTH_LOADED_BREAKERS.set(0);
    }

    /**
     * Schedule a persistence write. Coalesces concurrent triggers via
     * an AtomicBoolean so a burst of recordFailure/recordSuccess calls
     * produces a single write instead of N. Deferring to Timer keeps
     * disk I/O off the synchronized breaker lock.
     *
     * Defensive against missing Jenkins context: in unit tests that mock
     * Jenkins.get() without stubbing getRootDir(), this would NPE; we
     * swallow the exception so test code paths that exercise the breaker
     * directly do not need to mock the persistence layer.
     */
    static void save() {
        // Skip persistence if Jenkins is not fully up (unit tests that mock
        // Jenkins.get() without stubbing getRootDir() would NPE in the
        // deferred Timer task and leave SAVE_SCHEDULED stuck).
        Jenkins j;
        try {
            j = Jenkins.getInstanceOrNull();
        } catch (RuntimeException e) {
            return;
        }
        if (j == null || j.getRootDir() == null) {
            return;
        }
        SAVE_DIRTY.set(true);
        if (SAVE_SCHEDULED.compareAndSet(false, true)) {
            Timer.get().submit(() -> {
                try {
                    do {
                        SAVE_DIRTY.set(false);
                        new Store(BREAKERS).save();
                        HetznerMetricProvider.DC_HEALTH_SAVES.inc();
                    } while (SAVE_DIRTY.get());
                } catch (IOException | RuntimeException e) {
                    HetznerMetricProvider.DC_HEALTH_SAVE_FAILURES.inc();
                    log.warn("Failed to save Hetzner DC health state", e);
                } finally {
                    SAVE_SCHEDULED.set(false);
                    if (SAVE_DIRTY.get()) {
                        // A request landed after the last write and before the
                        // flag flip: schedule another pass instead of losing it.
                        save();
                    }
                }
            });
        }
    }

    private static XmlFile getXmlFile() {
        File rootDir = Jenkins.get().getRootDir();
        if (rootDir == null) {
            throw new IllegalStateException("Jenkins root directory not available");
        }
        return new XmlFile(Jenkins.XSTREAM2, new File(rootDir, "hetzner-dc-health.xml"));
    }

    /**
     * Saveable wrapper for the in-memory breaker map. Kept package-private
     * with a no-arg constructor so XStream can deserialize without
     * reflection magic.
     */
    static final class Store implements Saveable {
        ConcurrentHashMap<String, DcCircuitBreaker> breakers = new ConcurrentHashMap<>();

        Store() {
        }

        Store(ConcurrentHashMap<String, DcCircuitBreaker> source) {
            this.breakers = new ConcurrentHashMap<>(source);
        }

        @Override
        public void save() throws IOException {
            if (BulkChange.contains(this)) {
                return;
            }
            getXmlFile().write(this);
        }
    }
}
