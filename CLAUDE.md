# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Percona's patched fork of the dNation **Hetzner Cloud plugin for Jenkins**
(`jenkinsci/hetzner-cloud-plugin`). A Jenkins controller uses it to provision
ephemeral build agents as Hetzner Cloud VMs, matched to jobs by label. Percona
runs it across a fleet of Jenkins masters; the patches add a resilience +
observability layer on top of upstream v103.

- Java package: `cloud.dnation.jenkins.plugins.hetzner`.
- Version: `103.percona.28` (the `justfile` pin and top `CHANGELOG.md` entry — the pin is the source of truth; `.28` is in development here, newest released tag is `v103.percona.27`).
- Git remotes: `origin` = `Percona-Lab/jenkins-hetzner-cloud-plugin` (canonical — PRs/releases), `personal` = `nogueiraanderson/hetzner-cloud-plugin`, `upstream` = `jenkinsci/hetzner-cloud-plugin`. Upstream base: dNation tag `103.v843b_12130985`.
- **Two distinct changelogs, both kept current:** `CHANGELOG.md` = per-version patch history with the **incident/root-cause behind each change** (read it before touching the resilience code — every behavior has a postmortem). `CHANGES.md` = the Apache-2.0 §4(b) added/modified-file ledger.

## Build, test, release

Build/test run in Docker (`maven:3.9-eclipse-temurin-17`, deps cached in the `hetzner-m2-cache` volume) via `just`. The committed justfile has **only these four recipes**:

```bash
just build        # -> hetzner-cloud-<version>.hpi  (mvn clean package -DskipTests)
just test         # mvn clean verify (full unit + integration suite)
just clean        # rm *.hpi, drop build container
just clean-cache  # nuke the Maven cache volume
```

README's `deploy`/`dc-health`/`verify`/`backup` recipes are **not in this repo** — they live in a private local justfile; fleet deploy is out-of-band via the Jenkins Script Console.

Without Docker, build with Maven directly. `pom.xml` uses CI-friendly versioning (`<version>${changelist}</version>`, default `999999-SNAPSHOT`); the Jenkins baseline is `2.479.x` (Java 21 language level) but CI/Docker build on JDK 17:

```bash
mvn -B -DskipTests package -Dchangelist=103.percona.27   # artifact: target/hetzner-cloud.hpi (unversioned copy)
mvn -B verify                                            # full build + tests (what ci.yml runs)
mvn -B test -Dtest=DcCircuitBreakerTest                  # single test class
mvn -B test '-Dtest=DcCircuitBreakerTest#twoConsecutiveFailuresOpensCircuit'   # single method
```

Tests are **JUnit 5 only** — JUnit 4 imports are banned at build time (`ban-junit4-imports.skip=false` in `pom.xml`, enforced by the parent plugin POM). Lombok is used throughout. JCasC round-trip is covered by `JCasCTest` against `src/test/resources/.../jcasc.yaml`.

**Releases are tag-driven, not merge-driven.** Pushing a `v*.percona.*` tag fires `release.yml`, which derives `changelist` from the tag, builds the HPI, and publishes a GitHub Release (`.hpi` + `.sha256`). Merging to `main` runs `ci.yml` (`mvn -B verify`) **only** — never publishes, so docs-only changes need no tag. To cut a release: land code + a `CHANGELOG.md` entry, bump the `justfile` `version` pin, then tag and push. (`jenkins-security-scan.yml` is a separate reusable workflow pinned `@v2`, on a default JDK.)

## Architecture

**Upstream happy path:** `HetznerCloud` (a `hudson.slaves.Cloud`) `.provision()` builds a `HetznerServerAgent` and submits a `NodeCallable`, which calls `HetznerCloudResourceManager` (all Hetzner REST ops: create/destroy server, SSH keys, firewalls, image/network/placement-group lookup), then `HetznerServerComputerLauncher` SSHes in (SCPs `remoting.jar`, waits on `cloud-init status --wait`, launches the agent JVM). `HetznerServerTemplate` is the per-agent-type config.

**Resource labels are the backbone of every reconciliation path** (`HetznerConstants`): VMs and SSH keys are tagged in the `jenkins.io/` namespace — `managed-by=hetzner-jenkins-plugin`, `cloud-name`, `template-name`, `credentials-id`. `HetznerCloudResourceManager.fetchAllServers(cloud)` selects on `managed-by` + `cloud-name`; orphan cleanup, rehydration, and the under-cap recheck all key off these labels.

**Percona resilience + observability layer** (mostly new files; `CHANGES.md` has the exact added/modified split):

- **DC circuit-breaker failover** — `DcHealthTracker` is a static registry of `DcCircuitBreaker`, one per **`(location, arch)`** pair (key `"<location>:<arch>"`; `arch` from `HetznerMetricProvider.archOf()`: `cax*`→arm64, `cpx*`/`cx*`/`ccx*`→amd64, else unknown). CLOSED→OPEN after 2 consecutive failures, OPEN→HALF_OPEN after a reset timeout with a **single-probe lease** (one provision attempt per recovering DC per window). `provision()` calls `filterHealthy()` to short-circuit *before* the `fetchAllServers` API call when all matching templates' DCs are OPEN — this is what stops the OPEN-breaker API storm. State persists to `$JENKINS_HOME/hetzner-dc-health.xml` so OPEN breakers survive a restart.
- **API rate-limit safety** — `HetznerApiClient` (per-`credentialsId` singleton) wraps Retrofit with `RateLimitInterceptor` (token-scoped: on HTTP 429, blocks all calls for that token until the window resets; `RetryInterceptor` deliberately does *not* retry 429) and backoff. Each master has its own token but the budget is per Hetzner *project*, so one misbehaving master can starve the fleet — hence the breaker + rate-limit work.
- **Self-contained Prometheus metrics** — `HetznerMetricProvider` defines all `hetzner_*` instruments (no owned state; driven from the in-memory machinery). `HetznerPrometheusEndpoint` serves them at **`/hetzner-prometheus`** as an `UnprotectedRootAction`, **loopback-only** — the trust boundary is the 127.0.0.1 bind, not Jenkins ACLs (a master-side Grafana Alloy agent scrapes and pushes onward). The plugin bundles `io.prometheus:simpleclient` so no Jenkins Prometheus plugin is needed. (Ignore the stale `/prometheus` + `SYSTEM_READ` comments in `HetznerMetricProvider`/`pom.xml` — they describe the abandoned first design.)
- **Lifecycle reconciliation** — `OrphanedNodesCleaner` (hourly `PeriodicWork`) is **bi-directional**: destroys Hetzner VMs with no Jenkins node, *and* removes ghost Jenkins nodes with no VM — but the ghost half is **scoped to the owning cloud** via `ownerCloudName(agent)` (the persistent `cloudName` field, with a `provisioningId` fallback for legacy agents), and is skipped entirely when the cloud's token is rate-limited. (Without the scoping, a multi-cloud controller deletes every *other* cloud's live agents hourly — v103.percona.27.) Also: `HetznerWorkerRehydrator` (re-adopts the master's own VMs as agents after a JVM restart; off by default), `HungBuildDetector`, `TemplateErrorTracker`, `HetznerMetricsRefresher`.
- **CRW timer-death prevention** — the recurring theme. Every `PeriodicWork.doRun()` and `provision()` has a catch-all so an exception can't kill the `ComputerRetentionWork` timer. `HetznerCloud.pendingProvisions` (an `AtomicInteger`) closes the cap *accounting* race (the hard cap is still best-effort — see README "Known limitations").

**Extension model & entry points:** each strategy leaf under `launcher/` (SSH connector + connection method), `connect/` (`AbstractConnectivity` leaves — note `ConnectivityType` itself is a plain enum), `primaryip/`, `shutdown/` is `extends Abstract*` + a nested `@Extension @Symbol("…") DescriptorImpl` (this is how you add a new one; JCasC binds by symbol). The root cloud's JCasC key `hetzner` is class-name-derived (its descriptor has `@Extension` but no `@Symbol`). Boot/lifecycle hooks: `ControllerListener` (a `ComputerListener` — `onOnline` triggers/defers cleanup, `onOffline` terminates all Hetzner agents on shutdown); `@Initializer` `DcHealthTracker.load()` (after `PLUGINS_STARTED`) and `HetznerWorkerRehydrator.rehydrate()` (after `JOB_LOADED`, before `COMPLETED`).

## Conventions & gotchas

- **Deserialization safety is the #1 invariant — and the most-repeated bug class** (v103.percona.1, .25, .27). XStream restores objects via `Unsafe.allocateInstance`, **bypassing field initializers**, and `transient` fields are null exactly when the resilience code runs (after a restart; when a ghost node arises). Any field read by a `PeriodicWork`, a `@Initializer`, or `provision()` **must survive deserialization**: use a persistent field plus a `readResolve()` / lazy `ensureX()` guard — never rely on a field initializer, and never read a `transient` field there. (The v.27 multi-cloud mass-deletion existed *because* `HetznerServerAgent.cloud` is transient; the fix added a persistent `cloudName`, `serialVersionUID` 1→2.)
- **Metrics conventions:** never emit a `master`/instance label from the plugin — master-side Alloy injects it via relabel, and an empty plugin value silently drops the series. Gauges must **clear** stale label-children (`set(0)` / `remove(...)`) when a value drops, or they pin at their last peak in Mimir. Both have caused real false alerts. Two arch vocabularies coexist: `archOf()` emits `amd64`/`arm64`/`unknown` (metric labels, breaker keys); `NodeCallable.inferArchFromServerType()` emits `x86_64`/`arm64` (hardware validation) — don't cross them.
- **License-header hygiene (Apache-2.0 §4(b)) is mandatory.** New Percona files: `Copyright 2026 Percona LLC` (matches `NOTICE`). Modified upstream files: keep the original header **plus** `Modified by Percona LLC in 2026 (retention/failover/metrics hardening; see NOTICE).`. Record adds/mods in `CHANGES.md` and keep `NOTICE` current. Copy a header from a recent file rather than from memory — two files (`HungBuildDetector.java`, `HetznerMetricsRefresher.java`) carry a stray `Percona, LLC.` variant; don't propagate it.
- **Runtime feature flags** (JVM system properties, both default **off**): `-Dhetzner.rehydrate.enabled=true` enables the rehydrator; `-Dhetzner.prometheus.allowNonLoopback=true` drops the loopback gate on the metrics endpoint (only safe behind a separate auth layer). Other tuning knobs exist (`hetzner.hung-build.*`, `hetzner.rehydrate.grace-period-minutes`, `cloud.dnation.hetznerclient.apiendpoint`, `cloud.dnation.hetzner.http.loglevel`) — `grep 'System.getProperty\|getBoolean'` under `src/main/java`.
- **`.hpi` artifacts and `target/` are git-ignored**, and the built `.hpi` bundles only what is under `src/` — this `CLAUDE.md` lives at the repo root for Claude Code and never ships inside the plugin. (`CHANGES.md` still lists it among local-only artifacts; that note predates tracking it here.)
- The CRW timer-death fix (v103.percona.1) was contributed upstream and ships as jenkinsci v106; v.27 then ported upstream's `5a7a304` back on top. Check `CHANGELOG.md` before assuming a behavior is upstream-shared.
