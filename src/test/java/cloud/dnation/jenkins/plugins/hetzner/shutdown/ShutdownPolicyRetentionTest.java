/*
 * Copyright 2026 Percona LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 */
package cloud.dnation.jenkins.plugins.hetzner.shutdown;

import hudson.slaves.CloudRetentionStrategy;
import hudson.util.XStream2;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The retention strategy wrapped by a shutdown policy is a transient field,
 * and XStream skips constructors. Without {@code readResolve} a policy
 * loaded from the persisted cloud configuration carries a null strategy;
 * agents built from such a template bake the null in and core {@code Slave}
 * maps it to {@code RetentionStrategy.Always}, producing immortal workers
 * that are never reaped (observed fleet-wide on cloud.cd/ps80.cd/pxc.cd).
 */
class ShutdownPolicyRetentionTest {

    @Test
    void idlePeriodPolicySurvivesXstreamRoundTrip() {
        final XStream2 xstream = new XStream2();
        final IdlePeriodPolicy original = new IdlePeriodPolicy(7);

        final Object deserialized = xstream.fromXML(xstream.toXML(original));

        final IdlePeriodPolicy policy = assertInstanceOf(IdlePeriodPolicy.class, deserialized);
        assertEquals(7, policy.getIdleMinutes());
        assertNotNull(policy.getRetentionStrategy(),
                "deserialized policy must rebuild its transient retention strategy");
        assertInstanceOf(CloudRetentionStrategy.class, policy.getRetentionStrategy());
    }

    @Test
    void beforeHourWrapsPolicySurvivesXstreamRoundTrip() {
        final XStream2 xstream = new XStream2();
        final BeforeHourWrapsPolicy original = new BeforeHourWrapsPolicy();

        final Object deserialized = xstream.fromXML(xstream.toXML(original));

        final BeforeHourWrapsPolicy policy = assertInstanceOf(BeforeHourWrapsPolicy.class, deserialized);
        assertNotNull(policy.getRetentionStrategy(),
                "hour-wrap policy getter must not expose the transient null field");
    }
}
