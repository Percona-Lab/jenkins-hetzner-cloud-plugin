# CHANGES

Percona fork of the dNation Hetzner Cloud Plugin for Jenkins.

This file records the changes between the upstream base and the Percona fork,
satisfying the Apache License 2.0 §4(b) obligation to state significant changes.
It is the auditable companion to the root `NOTICE` and the per-file
"Modified by Percona" header notices.

- Upstream base: dNation hetzner-cloud-plugin tag `103.v843b_12130985`
  ("Make node name prefix configurable").
- Percona fork: `103.percona.26`.

Legend: A = added by Percona, M = modified from upstream.

## Added source (src/main)

- src/main/java/cloud/dnation/jenkins/plugins/hetzner/DcCircuitBreaker.java
- src/main/java/cloud/dnation/jenkins/plugins/hetzner/DcHealthTracker.java
- src/main/java/cloud/dnation/jenkins/plugins/hetzner/HetznerApiClient.java
- src/main/java/cloud/dnation/jenkins/plugins/hetzner/HetznerMetricsRefresher.java
- src/main/java/cloud/dnation/jenkins/plugins/hetzner/HetznerProvisioningException.java
- src/main/java/cloud/dnation/jenkins/plugins/hetzner/HetznerWorkerRehydrator.java
- src/main/java/cloud/dnation/jenkins/plugins/hetzner/HungBuildDetector.java
- src/main/java/cloud/dnation/jenkins/plugins/hetzner/metrics/HetznerMetricProvider.java
- src/main/java/cloud/dnation/jenkins/plugins/hetzner/metrics/HetznerPrometheusEndpoint.java
- src/main/java/cloud/dnation/jenkins/plugins/hetzner/RateLimitInterceptor.java
- src/main/java/cloud/dnation/jenkins/plugins/hetzner/RetryInterceptor.java
- src/main/java/cloud/dnation/jenkins/plugins/hetzner/TemplateErrorTracker.java

## Modified source (src/main)

- src/main/java/cloud/dnation/jenkins/plugins/hetzner/ConfigurationValidator.java
- src/main/java/cloud/dnation/jenkins/plugins/hetzner/ControllerListener.java
- src/main/java/cloud/dnation/jenkins/plugins/hetzner/Helper.java
- src/main/java/cloud/dnation/jenkins/plugins/hetzner/HetznerCloud.java
- src/main/java/cloud/dnation/jenkins/plugins/hetzner/HetznerCloudResourceManager.java
- src/main/java/cloud/dnation/jenkins/plugins/hetzner/HetznerConstants.java
- src/main/java/cloud/dnation/jenkins/plugins/hetzner/HetznerServerAgent.java
- src/main/java/cloud/dnation/jenkins/plugins/hetzner/HetznerServerTemplate.java
- src/main/java/cloud/dnation/jenkins/plugins/hetzner/launcher/DefaultConnectionMethod.java
- src/main/java/cloud/dnation/jenkins/plugins/hetzner/launcher/DefaultV6ConnectionMethod.java
- src/main/java/cloud/dnation/jenkins/plugins/hetzner/launcher/HetznerServerComputerLauncher.java
- src/main/java/cloud/dnation/jenkins/plugins/hetzner/launcher/PublicAddressOnly.java
- src/main/java/cloud/dnation/jenkins/plugins/hetzner/launcher/PublicV6AddressOnly.java
- src/main/java/cloud/dnation/jenkins/plugins/hetzner/NodeCallable.java
- src/main/java/cloud/dnation/jenkins/plugins/hetzner/OrphanedNodesCleaner.java
- src/main/java/cloud/dnation/jenkins/plugins/hetzner/primaryip/AbstractByLabelSelector.java
- src/main/java/cloud/dnation/jenkins/plugins/hetzner/shutdown/BeforeHourWrapsPolicy.java

## Added tests (src/test)

- src/test/java/cloud/dnation/jenkins/plugins/hetzner/DcCircuitBreakerTest.java
- src/test/java/cloud/dnation/jenkins/plugins/hetzner/DcHealthPersistenceTest.java
- src/test/java/cloud/dnation/jenkins/plugins/hetzner/DcHealthTrackerTest.java
- src/test/java/cloud/dnation/jenkins/plugins/hetzner/HetznerApiClientTest.java
- src/test/java/cloud/dnation/jenkins/plugins/hetzner/HetznerCloudRehydrateTest.java
- src/test/java/cloud/dnation/jenkins/plugins/hetzner/HetznerCloudResourceManagerDedupTest.java
- src/test/java/cloud/dnation/jenkins/plugins/hetzner/HetznerMetricIntegrationTest.java
- src/test/java/cloud/dnation/jenkins/plugins/hetzner/HetznerMetricsRefresherTest.java
- src/test/java/cloud/dnation/jenkins/plugins/hetzner/HetznerProvisioningExceptionTest.java
- src/test/java/cloud/dnation/jenkins/plugins/hetzner/HetznerWorkerRehydratorTest.java
- src/test/java/cloud/dnation/jenkins/plugins/hetzner/HungBuildDetectorTest.java
- src/test/java/cloud/dnation/jenkins/plugins/hetzner/metrics/HetznerMetricProviderTest.java
- src/test/java/cloud/dnation/jenkins/plugins/hetzner/metrics/HetznerPrometheusEndpointTest.java
- src/test/java/cloud/dnation/jenkins/plugins/hetzner/NodeCallableRetryTest.java
- src/test/java/cloud/dnation/jenkins/plugins/hetzner/NodeCallableTest.java
- src/test/java/cloud/dnation/jenkins/plugins/hetzner/RateLimitInterceptorTest.java
- src/test/java/cloud/dnation/jenkins/plugins/hetzner/RetryInterceptorTest.java

## Modified tests (src/test)

- src/test/java/cloud/dnation/jenkins/plugins/hetzner/HelperTest.java
- src/test/java/cloud/dnation/jenkins/plugins/hetzner/HetznerCloudSimpleTest.java
- src/test/java/cloud/dnation/jenkins/plugins/hetzner/launcher/TestPublicV6AddressOnly.java

## Modified build / project metadata

- .gitignore
- pom.xml
- README.md

## Added project files retained in this distribution

- CHANGELOG.md
- justfile

_Note: Percona local-only artifacts present in the development fork
(committed `.hpi` blobs, `scripts/`, `build-docker.sh`, `DestroyServerDemo.java`,
`Jenkinsfile`, `CLAUDE.md`) are intentionally excluded from this release
distribution and are not listed above._
