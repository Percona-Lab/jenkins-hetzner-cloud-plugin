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

import cloud.dnation.jenkins.plugins.hetzner.shutdown.AbstractShutdownPolicy;
import cloud.dnation.jenkins.plugins.hetzner.shutdown.BeforeHourWrapsPolicy;
import cloud.dnation.jenkins.plugins.hetzner.shutdown.IdlePeriodPolicy;
import hudson.model.TaskListener;
import hudson.slaves.CloudRetentionStrategy;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link HetznerServerAgent} exception safety and multi-cloud scoping.
 * <p>
 * Verify that {@code _terminate()} and {@code isAlive()} handle null transient
 * fields gracefully, which occurs after Jenkins restart/deserialization. An
 * uncaught exception from {@code _terminate()} kills the ComputerRetentionWork
 * timer permanently (the failure mode fixed in v103.percona.1 and upstreamed to
 * jenkinsci as v106 / commit 796d19b).
 * <p>
 * Also verify the persistent {@code cloudName} field, which scopes
 * {@link OrphanedNodesCleaner} ghost-node detection to the owning cloud and
 * survives the deserialization that nulls the transient {@code cloud} field
 * (v103.percona.27; ported from upstream commit 5a7a304).
 */
@WithJenkins
class HetznerServerAgentTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    private HetznerServerAgent createTestAgent() throws Exception {
        HetznerServerTemplate template = new HetznerServerTemplate(
                "test-template", "test-label", "test-image", "fsn1", "cx31");
        HetznerCloud cloud = new HetznerCloud(
                "test-cloud", "mock-credentials", "10",
                Collections.singletonList(template));
        return new HetznerServerAgent(
                new ProvisioningActivity.Id("test-cloud", "test-template", "test-node"),
                "test-node",
                "/tmp/jenkins",
                new hudson.slaves.JNLPLauncher(),
                cloud,
                template
        );
    }

    private static void setFieldNull(Object obj, String fieldName) throws Exception {
        Field field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, null);
    }

    // -- retention: a strategy-less policy must not produce an immortal agent --

    @Test
    void agentFallsBackToDefaultRetentionWhenPolicyLostItsStrategy() throws Exception {
        HetznerServerTemplate template = new HetznerServerTemplate(
                "test-template", "test-label", "test-image", "fsn1", "cx31");
        IdlePeriodPolicy crippled = new IdlePeriodPolicy(10);
        Field strategy = AbstractShutdownPolicy.class.getDeclaredField("retentionStrategy");
        strategy.setAccessible(true);
        strategy.set(crippled, null);
        template.setShutdownPolicy(crippled);
        HetznerCloud cloud = new HetznerCloud(
                "test-cloud", "mock-credentials", "10",
                Collections.singletonList(template));

        HetznerServerAgent agent = new HetznerServerAgent(
                new ProvisioningActivity.Id("test-cloud", "test-template", "test-node"),
                "test-node",
                "/tmp/jenkins",
                new hudson.slaves.JNLPLauncher(),
                cloud,
                template
        );

        assertInstanceOf(CloudRetentionStrategy.class, agent.getRetentionStrategy(),
                "null strategy must fall back to the default idle policy, not RetentionStrategy.Always");
    }

    @Test
    void idleOverdueThresholdSkipsHourWrapAgents() throws Exception {
        HetznerServerTemplate hourWrap = new HetznerServerTemplate(
                "hw-template", "hw-label", "test-image", "fsn1", "cx31");
        hourWrap.setShutdownPolicy(new BeforeHourWrapsPolicy());
        HetznerCloud hwCloud = new HetznerCloud(
                "test-cloud", "mock-credentials", "10",
                Collections.singletonList(hourWrap));
        HetznerServerAgent hwAgent = new HetznerServerAgent(
                new ProvisioningActivity.Id("test-cloud", "hw-template", "hw-node"),
                "hw-node", "/tmp/jenkins", new hudson.slaves.JNLPLauncher(), hwCloud, hourWrap);
        assertEquals(-1L, HetznerCloud.idleOverdueThresholdMinutes(hwAgent),
                "hour-wrap agents have no idle-shutdown period and must be excluded");

        HetznerServerTemplate idle = new HetznerServerTemplate(
                "idle-template", "idle-label", "test-image", "fsn1", "cx31");
        idle.setShutdownPolicy(new IdlePeriodPolicy(7));
        HetznerCloud idleCloud = new HetznerCloud(
                "test-cloud-2", "mock-credentials", "10",
                Collections.singletonList(idle));
        HetznerServerAgent idleAgent = new HetznerServerAgent(
                new ProvisioningActivity.Id("test-cloud-2", "idle-template", "idle-node"),
                "idle-node", "/tmp/jenkins", new hudson.slaves.JNLPLauncher(), idleCloud, idle);
        assertEquals(20L, HetznerCloud.idleOverdueThresholdMinutes(idleAgent),
                "2x7 idle minutes floors at 20");
    }

    // -- _terminate: must never throw (kills CRW timer) --

    @Test
    void terminateWithNullCloudDoesNotThrow() throws Exception {
        HetznerServerAgent agent = createTestAgent();
        setFieldNull(agent, "cloud");
        assertDoesNotThrow(() -> agent._terminate(TaskListener.NULL));
    }

    @Test
    void terminateWithNullServerInstanceDoesNotThrow() throws Exception {
        HetznerServerAgent agent = createTestAgent();
        agent.setServerInstance(null);
        assertDoesNotThrow(() -> agent._terminate(TaskListener.NULL));
    }

    @Test
    void terminateWithAllNullTransientsDoesNotThrow() throws Exception {
        HetznerServerAgent agent = createTestAgent();
        setFieldNull(agent, "cloud");
        agent.setServerInstance(null);
        assertDoesNotThrow(() -> agent._terminate(TaskListener.NULL));
    }

    // -- isAlive: must return false on error, never throw --

    @Test
    void isAliveReturnsFalseWhenCloudIsNull() throws Exception {
        HetznerServerAgent agent = createTestAgent();
        setFieldNull(agent, "cloud");
        assertFalse(agent.isAlive());
    }

    @Test
    void isAliveReturnsFalseWhenServerInstanceIsNull() throws Exception {
        HetznerServerAgent agent = createTestAgent();
        agent.setServerInstance(null);
        assertFalse(agent.isAlive());
    }

    // -- getDisplayName: must not throw --

    @Test
    void getDisplayNameDoesNotThrowWhenServerInstanceIsNull() throws Exception {
        HetznerServerAgent agent = createTestAgent();
        agent.setServerInstance(null);
        assertDoesNotThrow(agent::getDisplayName);
    }

    // -- cloudName: persistent field for multi-cloud ghost node scoping --

    @Test
    void cloudNameIsSetFromCloudOnConstruction() throws Exception {
        HetznerServerAgent agent = createTestAgent();
        assertEquals("test-cloud", agent.getCloudName());
    }

    @Test
    void cloudNameSurvivesWhenTransientCloudFieldIsNull() throws Exception {
        HetznerServerAgent agent = createTestAgent();
        setFieldNull(agent, "cloud");
        assertNull(agent.getCloud());
        assertEquals("test-cloud", agent.getCloudName(),
                "cloudName must survive after transient cloud field is nulled (deserialization)");
    }
}
