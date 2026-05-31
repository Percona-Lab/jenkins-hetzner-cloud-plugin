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

import cloud.dnation.hetznerclient.ServerDetail;
import cloud.dnation.jenkins.plugins.hetzner.metrics.HetznerMetricProvider;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.stubbing.Answer;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that {@link OrphanedNodesCleaner} ghost-node removal is scoped to the
 * owning cloud, so a controller with more than one {@link HetznerCloud} does
 * not mass-delete one cloud's agents while cleaning another (v103.percona.27,
 * ported from upstream {@code 5a7a304}).
 * <p>
 * Each {@code cleanCloud()} pass computes its ghost set from a single cloud's
 * VMs ({@code fetchAllServers(cloud.name)}) but {@code Helper.getHetznerAgents()}
 * returns agents from ALL clouds; without the owning-cloud filter, a cloud with
 * no (or non-overlapping) VMs would remove every other cloud's live agents.
 * Also verifies the legacy fallback: agents persisted before the {@code cloudName}
 * field existed are attributed via the persistent {@code provisioningId} rather
 * than leaked.
 */
class OrphanedNodesCleanerTest {

    private MockedStatic<Jenkins> jenkinsMock;
    private MockedStatic<HetznerCloudResourceManager> rsrcMgrStatic;
    private MockedStatic<HetznerApiClient> apiClientStatic;

    private Jenkins jenkins;
    private HetznerCloudResourceManager rsrcMgr;

    @BeforeEach
    void setUp() {
        jenkinsMock = mockStatic(Jenkins.class);
        rsrcMgrStatic = mockStatic(HetznerCloudResourceManager.class);
        apiClientStatic = mockStatic(HetznerApiClient.class);

        jenkins = mock(Jenkins.class);
        doAnswer((Answer<LabelAtom>) inv -> new LabelAtom(inv.getArgument(0)))
                .when(jenkins).getLabelAtom(anyString());
        when(Jenkins.get()).thenReturn(jenkins);

        // Shared resource-manager mock for every cloud; results are keyed by the
        // cloud name argument to fetchAllServers, so per-cloud VM sets differ.
        rsrcMgr = mock(HetznerCloudResourceManager.class);
        when(HetznerCloudResourceManager.create(anyString())).thenReturn(rsrcMgr);

        // Never rate-limited, so cleanCloud() does not short-circuit.
        HetznerApiClient apiClient = mock(HetznerApiClient.class);
        when(apiClient.isRateLimited()).thenReturn(false);
        when(HetznerApiClient.forCredentials(anyString())).thenReturn(apiClient);

        HetznerMetricProvider.resetForTest();
        DcHealthTracker.resetAll();
        TemplateErrorTracker.resetAll();
    }

    @AfterEach
    void tearDown() {
        jenkinsMock.close();
        rsrcMgrStatic.close();
        apiClientStatic.close();
        HetznerMetricProvider.resetForTest();
        DcHealthTracker.resetAll();
        TemplateErrorTracker.resetAll();
    }

    private HetznerCloud cloud(String name, String credentialsId) {
        HetznerServerTemplate template = new HetznerServerTemplate(
                "tmpl", "label", "img", "fsn1", "cx31");
        return new HetznerCloud(name, credentialsId, "10", List.of(template));
    }

    /**
     * @param cloudName        value of the persistent cloudName field; pass null
     *                         to simulate a pre-v103.percona.27 (legacy) agent
     * @param provisioningCloud cloud name carried by the persistent provisioningId
     */
    private HetznerServerAgent agent(String nodeName, String cloudName, String provisioningCloud) {
        HetznerServerAgent agent = mock(HetznerServerAgent.class);
        when(agent.getNodeName()).thenReturn(nodeName);
        when(agent.getCloudName()).thenReturn(cloudName);
        when(agent.getId()).thenReturn(
                new ProvisioningActivity.Id(provisioningCloud, "tmpl", nodeName));
        // toComputer() returns null on the mock; removeGhostNode handles that.
        return agent;
    }

    private void givenNodes(HetznerServerAgent... agents) {
        when(jenkins.getNodes()).thenReturn(List.<Node>of(agents));
    }

    private void givenVms(String cloudName, String... vmNames) throws Exception {
        List<ServerDetail> vms = java.util.Arrays.stream(vmNames)
                .map(n -> new ServerDetail().name(n))
                .collect(java.util.stream.Collectors.toList());
        when(rsrcMgr.fetchAllServers(cloudName)).thenReturn(vms);
    }

    @Test
    void doesNotRemoveOtherCloudsAgentsAsGhosts() throws Exception {
        HetznerCloud cloudA = cloud("cloud-A", "creds-A");
        // cloud-B owns a live agent; cloud-A has zero VMs this cycle.
        HetznerServerAgent agentB = agent("node-B", "cloud-B", "cloud-B");
        givenNodes(agentB);
        givenVms("cloud-A");

        OrphanedNodesCleaner.cleanCloud(cloudA);

        // Without the owning-cloud filter, cloud-A's pass would see node-B
        // missing from its (empty) VM set and delete it -- the regression.
        verify(jenkins, never()).removeNode(agentB);
    }

    @Test
    void removesOwnCloudGhostNode() throws Exception {
        HetznerCloud cloudA = cloud("cloud-A", "creds-A");
        // agentA belongs to cloud-A but its VM is gone -> a genuine ghost.
        HetznerServerAgent agentA = agent("node-A", "cloud-A", "cloud-A");
        givenNodes(agentA);
        givenVms("cloud-A");

        OrphanedNodesCleaner.cleanCloud(cloudA);

        verify(jenkins).removeNode(agentA);
    }

    @Test
    void legacyAgentCleanedByOwningCloudViaProvisioningId() throws Exception {
        HetznerCloud cloudA = cloud("cloud-A", "creds-A");
        // Pre-v27 agent: cloudName == null, but provisioningId still names cloud-A.
        HetznerServerAgent legacy = agent("node-legacy", null, "cloud-A");
        givenNodes(legacy);
        givenVms("cloud-A");

        OrphanedNodesCleaner.cleanCloud(cloudA);

        verify(jenkins).removeNode(legacy);
    }

    @Test
    void legacyAgentNotRemovedByForeignCloud() throws Exception {
        HetznerCloud cloudB = cloud("cloud-B", "creds-B");
        // Same legacy agent owned (per provisioningId) by cloud-A.
        HetznerServerAgent legacy = agent("node-legacy", null, "cloud-A");
        givenNodes(legacy);
        givenVms("cloud-B");

        OrphanedNodesCleaner.cleanCloud(cloudB);

        verify(jenkins, never()).removeNode(legacy);
    }
}
