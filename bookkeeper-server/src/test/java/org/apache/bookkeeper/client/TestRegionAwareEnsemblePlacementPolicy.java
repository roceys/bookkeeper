/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.bookkeeper.client;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.common.base.Optional;

import org.apache.bookkeeper.client.BKException.BKNotEnoughBookiesException;
import org.apache.bookkeeper.net.DNSToSwitchMapping;
import org.apache.bookkeeper.net.NetworkTopology;
import org.apache.bookkeeper.util.StaticDNSResolver;
import org.apache.commons.configuration.CompositeConfiguration;
import org.apache.commons.configuration.Configuration;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import junit.framework.TestCase;

import static org.apache.bookkeeper.client.RackawareEnsemblePlacementPolicy.REPP_DNS_RESOLVER_CLASS;
import static org.apache.bookkeeper.client.RegionAwareEnsemblePlacementPolicy.REPP_ENABLE_VALIDATION;
import static org.apache.bookkeeper.client.RegionAwareEnsemblePlacementPolicy.REPP_MINIMUM_REGIONS_FOR_DURABILITY;
import static org.apache.bookkeeper.client.RegionAwareEnsemblePlacementPolicy.REPP_REGIONS_TO_WRITE;

public class TestRegionAwareEnsemblePlacementPolicy extends TestCase {

    static final Logger LOG = LoggerFactory.getLogger(TestRegionAwareEnsemblePlacementPolicy.class);

    RegionAwareEnsemblePlacementPolicy repp;
    final Configuration conf = new CompositeConfiguration();
    final ArrayList<InetSocketAddress> ensemble = new ArrayList<InetSocketAddress>();
    final List<Integer> writeSet = new ArrayList<Integer>();
    InetSocketAddress addr1, addr2, addr3, addr4;

    static void updateMyRack(String rack) throws Exception {
        StaticDNSResolver.addNodeToRack(InetAddress.getLocalHost().getHostAddress(), rack);
        StaticDNSResolver.addNodeToRack(InetAddress.getLocalHost().getHostName(), rack);
        StaticDNSResolver.addNodeToRack("127.0.0.1", rack);
        StaticDNSResolver.addNodeToRack("localhost", rack);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        StaticDNSResolver.reset();
        updateMyRack(NetworkTopology.DEFAULT_RACK);
        LOG.info("Set up static DNS Resolver.");
        conf.setProperty(REPP_DNS_RESOLVER_CLASS, StaticDNSResolver.class.getName());

        addr1 = new InetSocketAddress("127.0.0.2", 3181);
        addr2 = new InetSocketAddress("127.0.0.3", 3181);
        addr3 = new InetSocketAddress("127.0.0.4", 3181);
        addr4 = new InetSocketAddress("127.0.0.5", 3181);
        // update dns mapping
        StaticDNSResolver.addNodeToRack(addr1.getAddress().getHostName(), "/r1/rack1");
        StaticDNSResolver.addNodeToRack(addr2.getAddress().getHostName(), NetworkTopology.DEFAULT_RACK);
        StaticDNSResolver.addNodeToRack(addr3.getAddress().getHostName(), NetworkTopology.DEFAULT_RACK);
        StaticDNSResolver.addNodeToRack(addr4.getAddress().getHostName(), "/r1/rack2");
        ensemble.add(addr1);
        ensemble.add(addr2);
        ensemble.add(addr3);
        ensemble.add(addr4);
        for (int i = 0; i < 4; i++) {
            writeSet.add(i);
        }
        repp = new RegionAwareEnsemblePlacementPolicy();
        repp.initialize(conf, Optional.<DNSToSwitchMapping>absent(), null);
    }

    @Override
    protected void tearDown() throws Exception {
        repp.uninitalize();
        super.tearDown();
    }

    @Test(timeout = 60000)
    public void testNotReorderReadIfInDefaultRack() throws Exception {
        repp.uninitalize();
        updateMyRack(NetworkTopology.DEFAULT_RACK);

        repp = new RegionAwareEnsemblePlacementPolicy();
        repp.initialize(conf, Optional.<DNSToSwitchMapping>absent(), null);

        List<Integer> reorderSet = repp.reorderReadSequence(ensemble, writeSet);
        assertFalse(reorderSet == writeSet);
        assertEquals(writeSet, reorderSet);
    }

    @Test(timeout = 60000)
    public void testNodeInSameRegion() throws Exception {
        repp.uninitalize();
        updateMyRack("/r1/rack3");

        repp = new RegionAwareEnsemblePlacementPolicy();
        repp.initialize(conf, Optional.<DNSToSwitchMapping>absent(), null);

        Set<InetSocketAddress> addrs = new HashSet<InetSocketAddress>();
        addrs.add(addr1);
        addrs.add(addr2);
        addrs.add(addr3);
        addrs.add(addr4);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());

        List<Integer> reoderSet = repp.reorderReadSequence(ensemble, writeSet);
        List<Integer> expectedSet = new ArrayList<Integer>();
        expectedSet.add(0);
        expectedSet.add(3);
        expectedSet.add(1);
        expectedSet.add(2);
        LOG.info("reorder set : {}", reoderSet);
        assertFalse(reoderSet == writeSet);
        assertEquals(expectedSet, reoderSet);
    }

    @Test(timeout = 60000)
    public void testNodeNotInSameRegions() throws Exception {
        repp.uninitalize();
        updateMyRack("/r2/rack1");

        repp = new RegionAwareEnsemblePlacementPolicy();
        repp.initialize(conf, Optional.<DNSToSwitchMapping>absent(), null);

        List<Integer> reoderSet = repp.reorderReadSequence(ensemble, writeSet);
        LOG.info("reorder set : {}", reoderSet);
        assertFalse(reoderSet == writeSet);
        assertEquals(writeSet, reoderSet);
    }

    @Test(timeout = 60000)
    public void testNodeDown() throws Exception {
        repp.uninitalize();
        updateMyRack("/r1/rack1");

        repp = new RegionAwareEnsemblePlacementPolicy();
        repp.initialize(conf, Optional.<DNSToSwitchMapping>absent(), null);

        // Update cluster
        Set<InetSocketAddress> addrs = new HashSet<InetSocketAddress>();
        addrs.add(addr1);
        addrs.add(addr2);
        addrs.add(addr3);
        addrs.add(addr4);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());
        addrs.remove(addr1);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());

        List<Integer> reoderSet = repp.reorderReadSequence(ensemble, writeSet);
        List<Integer> expectedSet = new ArrayList<Integer>();
        expectedSet.add(3);
        expectedSet.add(1);
        expectedSet.add(2);
        expectedSet.add(0);
        LOG.info("reorder set : {}", reoderSet);
        assertFalse(reoderSet == writeSet);
        assertEquals(expectedSet, reoderSet);
    }

    @Test(timeout = 60000)
    public void testNodeReadOnly() throws Exception {
        repp.uninitalize();
        updateMyRack("/r1/rack1");

        repp = new RegionAwareEnsemblePlacementPolicy();
        repp.initialize(conf, Optional.<DNSToSwitchMapping>absent(), null);

        // Update cluster
        Set<InetSocketAddress> addrs = new HashSet<InetSocketAddress>();
        addrs.add(addr1);
        addrs.add(addr2);
        addrs.add(addr3);
        addrs.add(addr4);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());
        addrs.remove(addr1);
        Set<InetSocketAddress> ro = new HashSet<InetSocketAddress>();
        ro.add(addr1);
        repp.onClusterChanged(addrs, ro);

        List<Integer> reoderSet = repp.reorderReadSequence(ensemble, writeSet);
        List<Integer> expectedSet = new ArrayList<Integer>();
        expectedSet.add(3);
        expectedSet.add(1);
        expectedSet.add(2);
        expectedSet.add(0);
        LOG.info("reorder set : {}", reoderSet);
        assertFalse(reoderSet == writeSet);
        assertEquals(expectedSet, reoderSet);
    }

    @Test(timeout = 60000)
    public void testTwoNodesDown() throws Exception {
        repp.uninitalize();
        updateMyRack("/r1/rack1");

        repp = new RegionAwareEnsemblePlacementPolicy();
        repp.initialize(conf, Optional.<DNSToSwitchMapping>absent(), null);

        // Update cluster
        Set<InetSocketAddress> addrs = new HashSet<InetSocketAddress>();
        addrs.add(addr1);
        addrs.add(addr2);
        addrs.add(addr3);
        addrs.add(addr4);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());
        addrs.remove(addr1);
        addrs.remove(addr2);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());

        List<Integer> reoderSet = repp.reorderReadSequence(ensemble, writeSet);
        List<Integer> expectedSet = new ArrayList<Integer>();
        expectedSet.add(3);
        expectedSet.add(2);
        expectedSet.add(0);
        expectedSet.add(1);
        LOG.info("reorder set : {}", reoderSet);
        assertFalse(reoderSet == writeSet);
        assertEquals(expectedSet, reoderSet);
    }

    @Test(timeout = 60000)
    public void testReplaceBookieWithEnoughBookiesInSameRegion() throws Exception {
        InetSocketAddress addr1 = new InetSocketAddress("127.0.0.2", 3181);
        InetSocketAddress addr2 = new InetSocketAddress("127.0.0.3", 3181);
        InetSocketAddress addr3 = new InetSocketAddress("127.0.0.4", 3181);
        InetSocketAddress addr4 = new InetSocketAddress("127.0.0.5", 3181);
        // update dns mapping
        StaticDNSResolver.addNodeToRack(addr1.getAddress().getHostAddress(), NetworkTopology.DEFAULT_RACK);
        StaticDNSResolver.addNodeToRack(addr2.getAddress().getHostAddress(), "/region1/r1");
        StaticDNSResolver.addNodeToRack(addr3.getAddress().getHostAddress(), "/region1/r2");
        StaticDNSResolver.addNodeToRack(addr4.getAddress().getHostAddress(), "/default-region/r3");
        // Update cluster
        Set<InetSocketAddress> addrs = new HashSet<InetSocketAddress>();
        addrs.add(addr1);
        addrs.add(addr2);
        addrs.add(addr3);
        addrs.add(addr4);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());
        // replace node under r2
        InetSocketAddress replacedBookie = repp.replaceBookie(1, 1, 1, new HashSet<InetSocketAddress>(), addr2, new HashSet<InetSocketAddress>());
        assertEquals(addr3, replacedBookie);
    }

    @Test(timeout = 60000)
    public void testReplaceBookieWithEnoughBookiesInDifferentRegion() throws Exception {
        InetSocketAddress addr1 = new InetSocketAddress("127.0.0.2", 3181);
        InetSocketAddress addr2 = new InetSocketAddress("127.0.0.3", 3181);
        InetSocketAddress addr3 = new InetSocketAddress("127.0.0.4", 3181);
        InetSocketAddress addr4 = new InetSocketAddress("127.0.0.5", 3181);
        // update dns mapping
        StaticDNSResolver.addNodeToRack(addr1.getAddress().getHostAddress(), NetworkTopology.DEFAULT_RACK);
        StaticDNSResolver.addNodeToRack(addr2.getAddress().getHostAddress(), "/region1/r2");
        StaticDNSResolver.addNodeToRack(addr3.getAddress().getHostAddress(), "/region2/r3");
        StaticDNSResolver.addNodeToRack(addr4.getAddress().getHostAddress(), "/region3/r4");
        // Update cluster
        Set<InetSocketAddress> addrs = new HashSet<InetSocketAddress>();
        addrs.add(addr1);
        addrs.add(addr2);
        addrs.add(addr3);
        addrs.add(addr4);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());
        // replace node under r2
        Set<InetSocketAddress> excludedAddrs = new HashSet<InetSocketAddress>();
        excludedAddrs.add(addr1);
        InetSocketAddress replacedBookie = repp.replaceBookie(1, 1, 1, new HashSet<InetSocketAddress>(), addr2, excludedAddrs);

        assertFalse(addr1.equals(replacedBookie));
        assertTrue(addr3.equals(replacedBookie) || addr4.equals(replacedBookie));
    }

    @Test(timeout = 60000)
    public void testNewEnsembleBookieWithNotEnoughBookies() throws Exception {
        InetSocketAddress addr1 = new InetSocketAddress("127.0.0.2", 3181);
        InetSocketAddress addr2 = new InetSocketAddress("127.0.0.3", 3181);
        InetSocketAddress addr3 = new InetSocketAddress("127.0.0.4", 3181);
        InetSocketAddress addr4 = new InetSocketAddress("127.0.0.5", 3181);
        // update dns mapping
        StaticDNSResolver.addNodeToRack(addr1.getAddress().getHostAddress(), NetworkTopology.DEFAULT_RACK);
        StaticDNSResolver.addNodeToRack(addr2.getAddress().getHostAddress(), "/region2/r2");
        StaticDNSResolver.addNodeToRack(addr3.getAddress().getHostAddress(), "/region3/r3");
        StaticDNSResolver.addNodeToRack(addr4.getAddress().getHostAddress(), "/region4/r4");
        // Update cluster
        Set<InetSocketAddress> addrs = new HashSet<InetSocketAddress>();
        addrs.add(addr1);
        addrs.add(addr2);
        addrs.add(addr3);
        addrs.add(addr4);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());
        try {
            ArrayList<InetSocketAddress> list = repp.newEnsemble(5, 5, 3, new HashSet<InetSocketAddress>());
            LOG.info("Ensemble : {}", list);
            fail("Should throw BKNotEnoughBookiesException when there is not enough bookies");
        } catch (BKNotEnoughBookiesException bnebe) {
            // should throw not enou
        }
    }

    @Test(timeout = 60000)
    public void testReplaceBookieWithNotEnoughBookies() throws Exception {
        InetSocketAddress addr1 = new InetSocketAddress("127.0.0.2", 3181);
        InetSocketAddress addr2 = new InetSocketAddress("127.0.0.3", 3181);
        InetSocketAddress addr3 = new InetSocketAddress("127.0.0.4", 3181);
        InetSocketAddress addr4 = new InetSocketAddress("127.0.0.5", 3181);
        // update dns mapping
        StaticDNSResolver.addNodeToRack(addr1.getAddress().getHostAddress(), NetworkTopology.DEFAULT_RACK);
        StaticDNSResolver.addNodeToRack(addr2.getAddress().getHostAddress(), "/region2/r2");
        StaticDNSResolver.addNodeToRack(addr3.getAddress().getHostAddress(), "/region3/r3");
        StaticDNSResolver.addNodeToRack(addr4.getAddress().getHostAddress(), "/region4/r4");
        // Update cluster
        Set<InetSocketAddress> addrs = new HashSet<InetSocketAddress>();
        addrs.add(addr1);
        addrs.add(addr2);
        addrs.add(addr3);
        addrs.add(addr4);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());
        // replace node under r2
        Set<InetSocketAddress> excludedAddrs = new HashSet<InetSocketAddress>();
        excludedAddrs.add(addr1);
        excludedAddrs.add(addr3);
        excludedAddrs.add(addr4);
        try {
            repp.replaceBookie(1, 1, 1, new HashSet<InetSocketAddress>(), addr2, excludedAddrs);
            fail("Should throw BKNotEnoughBookiesException when there is not enough bookies");
        } catch (BKNotEnoughBookiesException bnebe) {
            // should throw not enou
        }
    }

    @Test(timeout = 60000)
    public void testNewEnsembleWithSingleRegion() throws Exception {
        repp.uninitalize();
        repp = new RegionAwareEnsemblePlacementPolicy();
        repp.initialize(conf, Optional.<DNSToSwitchMapping>absent(), null);
        InetSocketAddress addr1 = new InetSocketAddress("127.0.0.2", 3181);
        InetSocketAddress addr2 = new InetSocketAddress("127.0.0.3", 3181);
        InetSocketAddress addr3 = new InetSocketAddress("127.0.0.4", 3181);
        InetSocketAddress addr4 = new InetSocketAddress("127.0.0.5", 3181);
        // update dns mapping
        StaticDNSResolver.addNodeToRack(addr1.getAddress().getHostAddress(), "/region1/r2");
        StaticDNSResolver.addNodeToRack(addr2.getAddress().getHostAddress(), "/region1/r2");
        StaticDNSResolver.addNodeToRack(addr3.getAddress().getHostAddress(), "/region1/r2");
        StaticDNSResolver.addNodeToRack(addr4.getAddress().getHostAddress(), "/region1/r2");
        // Update cluster
        Set<InetSocketAddress> addrs = new HashSet<InetSocketAddress>();
        addrs.add(addr1);
        addrs.add(addr2);
        addrs.add(addr3);
        addrs.add(addr4);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());
        try {
            ArrayList<InetSocketAddress> ensemble = repp.newEnsemble(3, 2, 2, new HashSet<InetSocketAddress>());
            assertEquals(0, getNumCoveredRegionsInWriteQuorum(ensemble, 2));
            ArrayList<InetSocketAddress> ensemble2 = repp.newEnsemble(4, 2, 2, new HashSet<InetSocketAddress>());
            assertEquals(0, getNumCoveredRegionsInWriteQuorum(ensemble2, 2));
        } catch (BKNotEnoughBookiesException bnebe) {
            fail("Should not get not enough bookies exception even there is only one rack.");
        }
    }

    @Test(timeout = 60000)
    public void testNewEnsembleWithMultipleRegions() throws Exception {
        repp.uninitalize();
        repp = new RegionAwareEnsemblePlacementPolicy();
        repp.initialize(conf, Optional.<DNSToSwitchMapping>absent(), null);
        InetSocketAddress addr1 = new InetSocketAddress("127.0.0.2", 3181);
        InetSocketAddress addr2 = new InetSocketAddress("127.0.0.3", 3181);
        InetSocketAddress addr3 = new InetSocketAddress("127.0.0.4", 3181);
        InetSocketAddress addr4 = new InetSocketAddress("127.0.0.5", 3181);
        // update dns mapping
        StaticDNSResolver.addNodeToRack(addr1.getAddress().getHostAddress(), NetworkTopology.DEFAULT_RACK);
        StaticDNSResolver.addNodeToRack(addr2.getAddress().getHostAddress(), "/region1/r2");
        StaticDNSResolver.addNodeToRack(addr3.getAddress().getHostAddress(), "/region1/r2");
        StaticDNSResolver.addNodeToRack(addr4.getAddress().getHostAddress(), "/region1/r2");
        // Update cluster
        Set<InetSocketAddress> addrs = new HashSet<InetSocketAddress>();
        addrs.add(addr1);
        addrs.add(addr2);
        addrs.add(addr3);
        addrs.add(addr4);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());
        try {
            ArrayList<InetSocketAddress> ensemble = repp.newEnsemble(3, 2, 2, new HashSet<InetSocketAddress>());
            int numCovered = getNumCoveredRegionsInWriteQuorum(ensemble, 2);
            assertTrue(numCovered >= 1);
            assertTrue(numCovered < 3);
        } catch (BKNotEnoughBookiesException bnebe) {
            fail("Should not get not enough bookies exception even there is only one rack.");
        }
        try {
            ArrayList<InetSocketAddress> ensemble2 = repp.newEnsemble(4, 2, 2, new HashSet<InetSocketAddress>());
            int numCovered = getNumCoveredRegionsInWriteQuorum(ensemble2, 2);
            assertTrue(numCovered >= 1 && numCovered < 3);
        } catch (BKNotEnoughBookiesException bnebe) {
            fail("Should not get not enough bookies exception even there is only one rack.");
        }
    }

    @Test(timeout = 60000)
    public void testNewEnsembleWithEnoughRegions() throws Exception {
        InetSocketAddress addr1 = new InetSocketAddress("127.0.0.2", 3181);
        InetSocketAddress addr2 = new InetSocketAddress("127.0.0.3", 3181);
        InetSocketAddress addr3 = new InetSocketAddress("127.0.0.4", 3181);
        InetSocketAddress addr4 = new InetSocketAddress("127.0.0.5", 3181);
        InetSocketAddress addr5 = new InetSocketAddress("127.0.0.6", 3181);
        InetSocketAddress addr6 = new InetSocketAddress("127.0.0.7", 3181);
        InetSocketAddress addr7 = new InetSocketAddress("127.0.0.8", 3181);
        InetSocketAddress addr8 = new InetSocketAddress("127.0.0.9", 3181);
        // update dns mapping
        StaticDNSResolver.addNodeToRack(addr1.getAddress().getHostAddress(), "/default-region/default-rack1");
        StaticDNSResolver.addNodeToRack(addr2.getAddress().getHostAddress(), "/region1/r2");
        StaticDNSResolver.addNodeToRack(addr3.getAddress().getHostAddress(), "/region2/r3");
        StaticDNSResolver.addNodeToRack(addr4.getAddress().getHostAddress(), "/region3/r4");
        StaticDNSResolver.addNodeToRack(addr5.getAddress().getHostAddress(), "/default-region/default-rack2");
        StaticDNSResolver.addNodeToRack(addr6.getAddress().getHostAddress(), "/region1/r12");
        StaticDNSResolver.addNodeToRack(addr7.getAddress().getHostAddress(), "/region2/r13");
        StaticDNSResolver.addNodeToRack(addr8.getAddress().getHostAddress(), "/region3/r14");
        // Update cluster
        Set<InetSocketAddress> addrs = new HashSet<InetSocketAddress>();
        addrs.add(addr1);
        addrs.add(addr2);
        addrs.add(addr3);
        addrs.add(addr4);
        addrs.add(addr5);
        addrs.add(addr6);
        addrs.add(addr7);
        addrs.add(addr8);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());
        try {
            ArrayList<InetSocketAddress> ensemble1 = repp.newEnsemble(3, 2, 2, new HashSet<InetSocketAddress>());
            assertEquals(3, getNumCoveredRegionsInWriteQuorum(ensemble1, 2));
            ArrayList<InetSocketAddress> ensemble2 = repp.newEnsemble(4, 2, 2, new HashSet<InetSocketAddress>());
            assertEquals(4, getNumCoveredRegionsInWriteQuorum(ensemble2, 2));
        } catch (BKNotEnoughBookiesException bnebe) {
            fail("Should not get not enough bookies exception even there is only one rack.");
        }
    }

    @Test(timeout = 60000)
    public void testNewEnsembleWithThreeRegions() throws Exception {
        repp.uninitalize();
        repp = new RegionAwareEnsemblePlacementPolicy();
        repp.initialize(conf, Optional.<DNSToSwitchMapping>absent(), null);
        InetSocketAddress addr1 = new InetSocketAddress("127.0.0.2", 3181);
        InetSocketAddress addr2 = new InetSocketAddress("127.0.0.3", 3181);
        InetSocketAddress addr3 = new InetSocketAddress("127.0.0.4", 3181);
        InetSocketAddress addr4 = new InetSocketAddress("127.0.0.5", 3181);
        InetSocketAddress addr5 = new InetSocketAddress("127.0.0.6", 3181);
        InetSocketAddress addr6 = new InetSocketAddress("127.0.0.7", 3181);
        InetSocketAddress addr7 = new InetSocketAddress("127.0.0.8", 3181);
        InetSocketAddress addr8 = new InetSocketAddress("127.0.0.9", 3181);
        InetSocketAddress addr9 = new InetSocketAddress("127.0.0.10", 3181);
        InetSocketAddress addr10 = new InetSocketAddress("127.0.0.11", 3181);
        // update dns mapping
        StaticDNSResolver.addNodeToRack(addr1.getAddress().getHostAddress(), "/region2/r1");
        StaticDNSResolver.addNodeToRack(addr2.getAddress().getHostAddress(), "/region1/r2");
        StaticDNSResolver.addNodeToRack(addr3.getAddress().getHostAddress(), "/region2/r3");
        StaticDNSResolver.addNodeToRack(addr4.getAddress().getHostAddress(), "/region3/r4");
        StaticDNSResolver.addNodeToRack(addr5.getAddress().getHostAddress(), "/region1/r11");
        StaticDNSResolver.addNodeToRack(addr6.getAddress().getHostAddress(), "/region1/r12");
        StaticDNSResolver.addNodeToRack(addr7.getAddress().getHostAddress(), "/region2/r13");
        StaticDNSResolver.addNodeToRack(addr8.getAddress().getHostAddress(), "/region3/r14");
        StaticDNSResolver.addNodeToRack(addr9.getAddress().getHostAddress(), "/region2/r23");
        StaticDNSResolver.addNodeToRack(addr10.getAddress().getHostAddress(), "/region1/r24");
        // Update cluster
        Set<InetSocketAddress> addrs = new HashSet<InetSocketAddress>();
        addrs.add(addr1);
        addrs.add(addr2);
        addrs.add(addr3);
        addrs.add(addr4);
        addrs.add(addr5);
        addrs.add(addr6);
        addrs.add(addr7);
        addrs.add(addr8);
        addrs.add(addr9);
        addrs.add(addr10);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());
        try {
            ArrayList<InetSocketAddress> ensemble = repp.newEnsemble(6, 6, 4, new HashSet<InetSocketAddress>());
            assert(ensemble.contains(addr4));
            assert(ensemble.contains(addr8));
            assert(ensemble.size() == 6);
            assertEquals(3, getNumRegionsInEnsemble(ensemble));
            ensemble = repp.newEnsemble(7, 7, 4, new HashSet<InetSocketAddress>());
            assert(ensemble.contains(addr4));
            assert(ensemble.contains(addr8));
            assert(ensemble.size() == 7);
            assertEquals(3, getNumRegionsInEnsemble(ensemble));
            ensemble = repp.newEnsemble(8, 8, 5, new HashSet<InetSocketAddress>());
            assert(ensemble.contains(addr4));
            assert(ensemble.contains(addr8));
            assert(ensemble.size() == 8);
            assertEquals(3, getNumRegionsInEnsemble(ensemble));
            ensemble = repp.newEnsemble(9, 9, 5, new HashSet<InetSocketAddress>());
            assert(ensemble.contains(addr4));
            assert(ensemble.contains(addr8));
            assert(ensemble.size() == 9);
            assertEquals(3, getNumRegionsInEnsemble(ensemble));
        } catch (BKNotEnoughBookiesException bnebe) {
            fail("Should not get not enough bookies exception even there is only one rack.");
        }
    }

    @Test(timeout = 60000)
    public void testNewEnsembleWithFiveRegions() throws Exception {
        repp.uninitalize();
        repp = new RegionAwareEnsemblePlacementPolicy();
        conf.setProperty(REPP_REGIONS_TO_WRITE, "region1;region2;region3;region4;region5");
        conf.setProperty(REPP_MINIMUM_REGIONS_FOR_DURABILITY, 5);
        repp.initialize(conf, Optional.<DNSToSwitchMapping>absent(), null);
        InetSocketAddress addr1 = new InetSocketAddress("127.1.0.2", 3181);
        InetSocketAddress addr2 = new InetSocketAddress("127.1.0.3", 3181);
        InetSocketAddress addr3 = new InetSocketAddress("127.1.0.4", 3181);
        InetSocketAddress addr4 = new InetSocketAddress("127.1.0.5", 3181);
        InetSocketAddress addr5 = new InetSocketAddress("127.1.0.6", 3181);
        InetSocketAddress addr6 = new InetSocketAddress("127.1.0.7", 3181);
        InetSocketAddress addr7 = new InetSocketAddress("127.1.0.8", 3181);
        InetSocketAddress addr8 = new InetSocketAddress("127.1.0.9", 3181);
        InetSocketAddress addr9 = new InetSocketAddress("127.1.0.10", 3181);
        InetSocketAddress addr10 = new InetSocketAddress("127.1.0.11", 3181);
        InetSocketAddress addr11 = new InetSocketAddress("127.1.0.12", 3181);
        InetSocketAddress addr12 = new InetSocketAddress("127.1.0.13", 3181);
        InetSocketAddress addr13 = new InetSocketAddress("127.1.0.14", 3181);
        InetSocketAddress addr14 = new InetSocketAddress("127.1.0.15", 3181);
        InetSocketAddress addr15 = new InetSocketAddress("127.1.0.16", 3181);
        // update dns mapping
        StaticDNSResolver.addNodeToRack(addr1.getAddress().getHostAddress(), "/region1/r1");
        StaticDNSResolver.addNodeToRack(addr2.getAddress().getHostAddress(), "/region1/r2");
        StaticDNSResolver.addNodeToRack(addr3.getAddress().getHostAddress(), "/region1/r3");
        StaticDNSResolver.addNodeToRack(addr4.getAddress().getHostAddress(), "/region2/r4");
        StaticDNSResolver.addNodeToRack(addr5.getAddress().getHostAddress(), "/region2/r11");
        StaticDNSResolver.addNodeToRack(addr6.getAddress().getHostAddress(), "/region2/r12");
        StaticDNSResolver.addNodeToRack(addr7.getAddress().getHostAddress(), "/region3/r13");
        StaticDNSResolver.addNodeToRack(addr8.getAddress().getHostAddress(), "/region3/r14");
        StaticDNSResolver.addNodeToRack(addr9.getAddress().getHostAddress(), "/region3/r23");
        StaticDNSResolver.addNodeToRack(addr10.getAddress().getHostAddress(), "/region4/r24");
        StaticDNSResolver.addNodeToRack(addr11.getAddress().getHostAddress(), "/region4/r31");
        StaticDNSResolver.addNodeToRack(addr12.getAddress().getHostAddress(), "/region4/r32");
        StaticDNSResolver.addNodeToRack(addr13.getAddress().getHostAddress(), "/region5/r33");
        StaticDNSResolver.addNodeToRack(addr14.getAddress().getHostAddress(), "/region5/r34");
        StaticDNSResolver.addNodeToRack(addr15.getAddress().getHostAddress(), "/region5/r35");
        // Update cluster
        Set<InetSocketAddress> addrs = new HashSet<InetSocketAddress>();
        addrs.add(addr1);
        addrs.add(addr2);
        addrs.add(addr3);
        addrs.add(addr4);
        addrs.add(addr5);
        addrs.add(addr6);
        addrs.add(addr7);
        addrs.add(addr8);
        addrs.add(addr9);
        addrs.add(addr10);
        addrs.add(addr11);
        addrs.add(addr12);
        addrs.add(addr13);
        addrs.add(addr14);
        addrs.add(addr15);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());

        try {
            ArrayList<InetSocketAddress> ensemble = repp.newEnsemble(10, 10, 10, new HashSet<InetSocketAddress>());
            assert(ensemble.size() == 10);
            assertEquals(5, getNumRegionsInEnsemble(ensemble));
        } catch (BKNotEnoughBookiesException bnebe) {
            LOG.error("BKNotEnoughBookiesException", bnebe);
            fail("Should not get not enough bookies exception even there is only one rack.");
        }

        try{
            Set<InetSocketAddress> excludedAddrs = new HashSet<InetSocketAddress>();
            excludedAddrs.add(addr10);
            ArrayList<InetSocketAddress> ensemble = repp.newEnsemble(10, 10, 10, excludedAddrs);
            assert(ensemble.contains(addr11) && ensemble.contains(addr12));
            assert(ensemble.size() == 10);
            assertEquals(5, getNumRegionsInEnsemble(ensemble));
        } catch (BKNotEnoughBookiesException bnebe) {
            fail("Should not get not enough bookies exception even there is only one rack.");
        }
    }

    @Test(timeout = 60000)
    public void testEnsembleWithThreeRegionsReplace() throws Exception {
        repp.uninitalize();
        repp = new RegionAwareEnsemblePlacementPolicy();
        conf.setProperty(REPP_REGIONS_TO_WRITE, "region1;region2;region3");
        conf.setProperty(REPP_MINIMUM_REGIONS_FOR_DURABILITY, 2);
        repp.initialize(conf, Optional.<DNSToSwitchMapping>absent(), null);
        InetSocketAddress addr1 = new InetSocketAddress("127.1.0.2", 3181);
        InetSocketAddress addr2 = new InetSocketAddress("127.1.0.3", 3181);
        InetSocketAddress addr3 = new InetSocketAddress("127.1.0.4", 3181);
        InetSocketAddress addr4 = new InetSocketAddress("127.1.0.5", 3181);
        InetSocketAddress addr5 = new InetSocketAddress("127.1.0.6", 3181);
        InetSocketAddress addr6 = new InetSocketAddress("127.1.0.7", 3181);
        InetSocketAddress addr7 = new InetSocketAddress("127.1.0.8", 3181);
        InetSocketAddress addr8 = new InetSocketAddress("127.1.0.9", 3181);
        InetSocketAddress addr9 = new InetSocketAddress("127.1.0.10", 3181);
        // update dns mapping
        StaticDNSResolver.addNodeToRack(addr1.getAddress().getHostAddress(), "/region1/r1");
        StaticDNSResolver.addNodeToRack(addr2.getAddress().getHostAddress(), "/region1/r2");
        StaticDNSResolver.addNodeToRack(addr3.getAddress().getHostAddress(), "/region1/r3");
        StaticDNSResolver.addNodeToRack(addr4.getAddress().getHostAddress(), "/region2/r4");
        StaticDNSResolver.addNodeToRack(addr5.getAddress().getHostAddress(), "/region2/r11");
        StaticDNSResolver.addNodeToRack(addr6.getAddress().getHostAddress(), "/region2/r12");
        StaticDNSResolver.addNodeToRack(addr7.getAddress().getHostAddress(), "/region3/r13");
        StaticDNSResolver.addNodeToRack(addr8.getAddress().getHostAddress(), "/region3/r14");
        StaticDNSResolver.addNodeToRack(addr9.getAddress().getHostAddress(), "/region3/r23");

        // Update cluster
        Set<InetSocketAddress> addrs = new HashSet<InetSocketAddress>();
        addrs.add(addr1);
        addrs.add(addr2);
        addrs.add(addr3);
        addrs.add(addr4);
        addrs.add(addr5);
        addrs.add(addr6);
        addrs.add(addr7);
        addrs.add(addr8);
        addrs.add(addr9);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());

        ArrayList<InetSocketAddress> ensemble;
        try {
            ensemble = repp.newEnsemble(6, 6, 4, new HashSet<InetSocketAddress>());
            assert(ensemble.size() == 6);
            assertEquals(3, getNumRegionsInEnsemble(ensemble));
        } catch (BKNotEnoughBookiesException bnebe) {
            LOG.error("BKNotEnoughBookiesException", bnebe);
            fail("Should not get not enough bookies exception even there is only one rack.");
            throw bnebe;
        }

        InetSocketAddress bookieToReplace;
        InetSocketAddress replacedBookieExpected;
        if (ensemble.contains(addr4)) {
            bookieToReplace = addr4;
            if (ensemble.contains(addr5)) {
                replacedBookieExpected = addr6;
            } else {
                replacedBookieExpected = addr5;
            }
        } else {
            replacedBookieExpected = addr4;
            bookieToReplace = addr5;
        }
        Set<InetSocketAddress> excludedAddrs = new HashSet<InetSocketAddress>();

        try{
            InetSocketAddress replacedBookie = repp.replaceBookie(6, 6, 4, ensemble, bookieToReplace, excludedAddrs);
            assert(replacedBookie.equals(replacedBookieExpected));
            assertEquals(3, getNumRegionsInEnsemble(ensemble));
        } catch (BKNotEnoughBookiesException bnebe) {
            fail("Should not get not enough bookies exception even there is only one rack.");
        }

        excludedAddrs.add(replacedBookieExpected);
        try{
            InetSocketAddress replacedBookie = repp.replaceBookie(6, 6, 4, ensemble, bookieToReplace, excludedAddrs);
            fail("Should throw BKNotEnoughBookiesException when there is not enough bookies");
        } catch (BKNotEnoughBookiesException bnebe) {
            // expected
        }

    }

    @Test(timeout = 60000)
    public void testNewEnsembleFailWithFiveRegions() throws Exception {
        repp.uninitalize();
        repp = new RegionAwareEnsemblePlacementPolicy();
        conf.setProperty(REPP_REGIONS_TO_WRITE, "region1;region2;region3;region4;region5");
        conf.setProperty(REPP_MINIMUM_REGIONS_FOR_DURABILITY, 5);
        conf.setProperty(REPP_ENABLE_VALIDATION, false);
        repp.initialize(conf, Optional.<DNSToSwitchMapping>absent(), null);
        InetSocketAddress addr1 = new InetSocketAddress("127.0.0.2", 3181);
        InetSocketAddress addr2 = new InetSocketAddress("127.0.0.3", 3181);
        InetSocketAddress addr3 = new InetSocketAddress("127.0.0.4", 3181);
        InetSocketAddress addr4 = new InetSocketAddress("127.0.0.5", 3181);
        InetSocketAddress addr5 = new InetSocketAddress("127.0.0.6", 3181);
        InetSocketAddress addr6 = new InetSocketAddress("127.0.0.7", 3181);
        InetSocketAddress addr7 = new InetSocketAddress("127.0.0.8", 3181);
        InetSocketAddress addr8 = new InetSocketAddress("127.0.0.9", 3181);
        InetSocketAddress addr9 = new InetSocketAddress("127.0.0.10", 3181);
        InetSocketAddress addr10 = new InetSocketAddress("127.0.0.11", 3181);
        // update dns mapping
        StaticDNSResolver.addNodeToRack(addr1.getAddress().getHostAddress(), "/region1/r1");
        StaticDNSResolver.addNodeToRack(addr2.getAddress().getHostAddress(), "/region1/r2");
        StaticDNSResolver.addNodeToRack(addr3.getAddress().getHostAddress(), "/region2/r3");
        StaticDNSResolver.addNodeToRack(addr4.getAddress().getHostAddress(), "/region2/r4");
        StaticDNSResolver.addNodeToRack(addr5.getAddress().getHostAddress(), "/region3/r11");
        StaticDNSResolver.addNodeToRack(addr6.getAddress().getHostAddress(), "/region3/r12");
        StaticDNSResolver.addNodeToRack(addr7.getAddress().getHostAddress(), "/region4/r13");
        StaticDNSResolver.addNodeToRack(addr8.getAddress().getHostAddress(), "/region4/r14");
        StaticDNSResolver.addNodeToRack(addr9.getAddress().getHostAddress(), "/region5/r23");
        StaticDNSResolver.addNodeToRack(addr10.getAddress().getHostAddress(), "/region5/r24");
        // Update cluster
        Set<InetSocketAddress> addrs = new HashSet<InetSocketAddress>();
        addrs.add(addr1);
        addrs.add(addr2);
        addrs.add(addr3);
        addrs.add(addr4);
        addrs.add(addr5);
        addrs.add(addr6);
        addrs.add(addr7);
        addrs.add(addr8);
        addrs.add(addr9);
        addrs.add(addr10);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());

        Set<InetSocketAddress> excludedAddrs = new HashSet<InetSocketAddress>();
        excludedAddrs.add(addr10);
        excludedAddrs.add(addr9);
        try {
            ArrayList<InetSocketAddress> list = repp.newEnsemble(5, 5, 5, excludedAddrs);
            LOG.info("Ensemble : {}", list);
            fail("Should throw BKNotEnoughBookiesException when there is not enough bookies");
        } catch (BKNotEnoughBookiesException bnebe) {
            // should throw not enou
        }
    }

    private void prepareNetworkTopologyForReorderTests(String myRegion) throws Exception {
        repp.uninitalize();
        updateMyRack("/" + myRegion);

        repp = new RegionAwareEnsemblePlacementPolicy();
        repp.initialize(conf, Optional.<DNSToSwitchMapping>absent(), null);

        InetSocketAddress addr1 = new InetSocketAddress("127.0.0.2", 3181);
        InetSocketAddress addr2 = new InetSocketAddress("127.0.0.3", 3181);
        InetSocketAddress addr3 = new InetSocketAddress("127.0.0.4", 3181);
        InetSocketAddress addr4 = new InetSocketAddress("127.0.0.5", 3181);
        InetSocketAddress addr5 = new InetSocketAddress("127.0.0.6", 3181);
        InetSocketAddress addr6 = new InetSocketAddress("127.0.0.7", 3181);
        InetSocketAddress addr7 = new InetSocketAddress("127.0.0.8", 3181);
        InetSocketAddress addr8 = new InetSocketAddress("127.0.0.9", 3181);
        InetSocketAddress addr9 = new InetSocketAddress("127.0.0.10", 3181);
        // update dns mapping
        StaticDNSResolver.addNodeToRack(addr1.getAddress().getHostAddress(), "/region1/r1");
        StaticDNSResolver.addNodeToRack(addr2.getAddress().getHostAddress(), "/region1/r2");
        StaticDNSResolver.addNodeToRack(addr3.getAddress().getHostAddress(), "/region1/r3");
        StaticDNSResolver.addNodeToRack(addr4.getAddress().getHostAddress(), "/region2/r1");
        StaticDNSResolver.addNodeToRack(addr5.getAddress().getHostAddress(), "/region2/r2");
        StaticDNSResolver.addNodeToRack(addr6.getAddress().getHostAddress(), "/region2/r3");
        StaticDNSResolver.addNodeToRack(addr7.getAddress().getHostAddress(), "/region3/r1");
        StaticDNSResolver.addNodeToRack(addr8.getAddress().getHostAddress(), "/region3/r2");
        StaticDNSResolver.addNodeToRack(addr9.getAddress().getHostAddress(), "/region3/r3");
        // Update cluster
        Set<InetSocketAddress> addrs = new HashSet<InetSocketAddress>();
        addrs.add(addr1);
        addrs.add(addr2);
        addrs.add(addr3);
        addrs.add(addr4);
        addrs.add(addr5);
        addrs.add(addr6);
        addrs.add(addr7);
        addrs.add(addr8);
        addrs.add(addr9);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());
    }

    @Test(timeout = 60000)
    public void testBasicReorderReadSequenceWithLocalRegion() throws Exception {
        basicReorderReadSequenceWithLocalRegionTest("region2", false);
    }

    @Test(timeout = 60000)
    public void testBasicReorderReadLACSequenceWithLocalRegion() throws Exception {
        basicReorderReadSequenceWithLocalRegionTest("region2", true);
    }

    private void basicReorderReadSequenceWithLocalRegionTest(String myRegion, boolean isReadLAC) throws Exception {
        prepareNetworkTopologyForReorderTests(myRegion);

        ArrayList<InetSocketAddress> ensemble = repp.newEnsemble(9, 9, 5, new HashSet<InetSocketAddress>());
        assertEquals(9, getNumCoveredRegionsInWriteQuorum(ensemble, 9));

        DistributionSchedule ds = new RoundRobinDistributionSchedule(9, 9, 9);

        LOG.info("My region is {}, ensemble : {}", repp.myRegion, ensemble);

        int ensembleSize = ensemble.size();
        for (int i = 0; i < ensembleSize; i++) {
            List<Integer> writeSet = ds.getWriteSet(i);
            List<Integer> readSet;
            if (isReadLAC) {
                readSet = repp.reorderReadLACSequence(ensemble, writeSet);
            } else {
                readSet = repp.reorderReadSequence(ensemble, writeSet);
            }

            LOG.info("Reorder {} => {}.", writeSet, readSet);

            // first few nodes less than REMOTE_NODE_IN_REORDER_SEQUENCE should be local region
            int k = 0;
            for (; k < RegionAwareEnsemblePlacementPolicy.REMOTE_NODE_IN_REORDER_SEQUENCE; k++) {
                InetSocketAddress address = ensemble.get(readSet.get(k));
                assertEquals(myRegion, StaticDNSResolver.getRegion(address.getAddress().getHostAddress()));
            }
            InetSocketAddress remoteAddress = ensemble.get(readSet.get(k));
            assertFalse(myRegion.equals(StaticDNSResolver.getRegion(remoteAddress.getAddress().getHostAddress())));
            k++;
            InetSocketAddress localAddress = ensemble.get(readSet.get(k));
            assertEquals(myRegion, StaticDNSResolver.getRegion(localAddress.getAddress().getHostAddress()));
            k++;
            for (; k < ensembleSize; k++) {
                InetSocketAddress address = ensemble.get(readSet.get(k));
                assertFalse(myRegion.equals(StaticDNSResolver.getRegion(address.getAddress().getHostAddress())));
            }
        }
    }

    @Test(timeout = 60000)
    public void testBasicReorderReadSequenceWithRemoteRegion() throws Exception {
        basicReorderReadSequenceWithRemoteRegionTest("region4", false);
    }

    @Test(timeout = 60000)
    public void testBasicReorderReadLACSequenceWithRemoteRegion() throws Exception {
        basicReorderReadSequenceWithRemoteRegionTest("region4", true);
    }

    private void basicReorderReadSequenceWithRemoteRegionTest(String myRegion, boolean isReadLAC) throws Exception {
        prepareNetworkTopologyForReorderTests(myRegion);

        ArrayList<InetSocketAddress> ensemble = repp.newEnsemble(9, 9, 5, new HashSet<InetSocketAddress>());
        assertEquals(9, getNumCoveredRegionsInWriteQuorum(ensemble, 9));

        DistributionSchedule ds = new RoundRobinDistributionSchedule(9, 9, 9);

        LOG.info("My region is {}, ensemble : {}", repp.myRegion, ensemble);

        int ensembleSize = ensemble.size();
        for (int i = 0; i < ensembleSize; i++) {
            List<Integer> writeSet = ds.getWriteSet(i);
            List<Integer> readSet;

            if (isReadLAC) {
                readSet = repp.reorderReadLACSequence(ensemble, writeSet);
            } else {
                readSet = repp.reorderReadSequence(ensemble, writeSet);
            }

            assertEquals(writeSet, readSet);
        }
    }

    @Test(timeout = 60000)
    public void testReorderReadSequenceWithUnavailableOrReadOnlyBookies() throws Exception {
        reorderReadSequenceWithUnavailableOrReadOnlyBookiesTest(false);
    }

    @Test(timeout = 60000)
    public void testReorderReadLACSequenceWithUnavailableOrReadOnlyBookies() throws Exception {
        reorderReadSequenceWithUnavailableOrReadOnlyBookiesTest(true);
    }

    static Set<InetSocketAddress> getBookiesForRegion(ArrayList<InetSocketAddress> ensemble, String region) {
        Set<InetSocketAddress> regionBookies = new HashSet<InetSocketAddress>();
        for (InetSocketAddress address : ensemble) {
            String r = StaticDNSResolver.getRegion(address.getAddress().getHostAddress());
            if (r.equals(region)) {
                regionBookies.add(address);
            }
        }
        return regionBookies;
    }

    static void appendBookieIndexByRegion(ArrayList<InetSocketAddress> ensemble,
                                          List<Integer> writeSet,
                                          String region,
                                          List<Integer> finalSet) {
        for (int bi : writeSet) {
            String r = StaticDNSResolver.getRegion(ensemble.get(bi).getAddress().getHostAddress());
            if (r.equals(region)) {
                finalSet.add(bi);
            }
        }
    }

    private void reorderReadSequenceWithUnavailableOrReadOnlyBookiesTest(boolean isReadLAC) throws Exception {
        String myRegion = "region4";
        String unavailableRegion = "region1";
        String writeRegion = "region2";
        String readOnlyRegion = "region3";

        prepareNetworkTopologyForReorderTests(myRegion);

        ArrayList<InetSocketAddress> ensemble = repp.newEnsemble(9, 9, 5, new HashSet<InetSocketAddress>());
        assertEquals(9, getNumCoveredRegionsInWriteQuorum(ensemble, 9));

        DistributionSchedule ds = new RoundRobinDistributionSchedule(9, 9, 9);

        LOG.info("My region is {}, ensemble : {}", repp.myRegion, ensemble);

        Set<InetSocketAddress> readOnlyBookies = getBookiesForRegion(ensemble, readOnlyRegion);
        Set<InetSocketAddress> writeBookies = getBookiesForRegion(ensemble, writeRegion);

        repp.onClusterChanged(writeBookies, readOnlyBookies);

        LOG.info("Writable Bookies {}, ReadOnly Bookies {}.", repp.knownBookies.keySet(), repp.readOnlyBookies);

        int ensembleSize = ensemble.size();
        for (int i = 0; i < ensembleSize; i++) {
            List<Integer> writeSet = ds.getWriteSet(i);
            List<Integer> readSet;
            if (isReadLAC) {
                readSet = repp.reorderReadLACSequence(ensemble, writeSet);
            } else {
                readSet = repp.reorderReadSequence(ensemble, writeSet);
            }

            LOG.info("Reorder {} => {}.", writeSet, readSet);

            List<Integer> expectedReadSet = new ArrayList<Integer>();
            // writable bookies
            appendBookieIndexByRegion(ensemble, writeSet, writeRegion, expectedReadSet);
            // readonly bookies
            appendBookieIndexByRegion(ensemble, writeSet, readOnlyRegion, expectedReadSet);
            // unavailable bookies
            appendBookieIndexByRegion(ensemble, writeSet, unavailableRegion, expectedReadSet);

            assertEquals(expectedReadSet, readSet);
        }
    }

    private int getNumRegionsInEnsemble(ArrayList<InetSocketAddress> ensemble) {
        Set<String> regions = new HashSet<String>();
        for(InetSocketAddress addr: ensemble) {
            regions.add(StaticDNSResolver.getRegion(addr.getAddress().getHostAddress()));
        }
        return regions.size();
    }

    private int getNumCoveredRegionsInWriteQuorum(ArrayList<InetSocketAddress> ensemble, int writeQuorumSize)
            throws Exception {
        int ensembleSize = ensemble.size();
        int numCoveredWriteQuorums = 0;
        for (int i = 0; i < ensembleSize; i++) {
            Set<String> regions = new HashSet<String>();
            for (int j = 0; j < writeQuorumSize; j++) {
                int bookieIdx = (i + j) % ensembleSize;
                InetSocketAddress addr = ensemble.get(bookieIdx);
                regions.add(StaticDNSResolver.getRegion(addr.getAddress().getHostAddress()));
            }
            numCoveredWriteQuorums += (regions.size() > 1 ? 1 : 0);
        }
        return numCoveredWriteQuorums;
    }

}