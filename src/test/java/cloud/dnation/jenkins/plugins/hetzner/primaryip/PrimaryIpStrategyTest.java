/*
 *     Copyright 2022 https://dnation.cloud
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
 * Modified by Percona LLC in 2026 (retention/failover/metrics hardening; see NOTICE).
 */
package cloud.dnation.jenkins.plugins.hetzner.primaryip;

import cloud.dnation.hetznerclient.CreateServerRequest;
import cloud.dnation.hetznerclient.LocationDetail;
import cloud.dnation.hetznerclient.PrimaryIpDetail;
import org.junit.jupiter.api.Test;

import static cloud.dnation.jenkins.plugins.hetzner.primaryip.AbstractByLabelSelector.isIpUsable;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimaryIpStrategyTest {

    private static final LocationDetail FSN1;
    private static final LocationDetail NBG1;

    static {
        FSN1 = new LocationDetail();
        FSN1.setName("fsn1");
        NBG1 = new LocationDetail();
        NBG1.setName("nbg1");
    }

    @Test
    void testIpIsUsable() {
        final CreateServerRequest server = new CreateServerRequest();
        final PrimaryIpDetail ip = new PrimaryIpDetail();
        //Same location
        server.setLocation(FSN1.getName());
        ip.setLocation(FSN1);
        assertTrue(isIpUsable(ip, server));

        //Different location
        ip.setLocation(NBG1);
        assertFalse(isIpUsable(ip, server));

        //IP without location
        ip.setLocation(null);
        assertFalse(isIpUsable(ip, server));

        //Server without location
        ip.setLocation(FSN1);
        server.setLocation(null);
        assertFalse(isIpUsable(ip, server));

        //Already allocated
        server.setLocation(FSN1.getName());
        ip.setAssigneeId(0L);
        assertFalse(isIpUsable(ip, server));
    }
}
