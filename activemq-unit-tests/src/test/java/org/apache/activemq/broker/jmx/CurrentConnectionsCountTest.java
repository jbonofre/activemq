/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.broker.jmx;

import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.jms.Connection;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.Broker;
import org.apache.activemq.broker.BrokerFilter;
import org.apache.activemq.broker.BrokerPlugin;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.ConnectionContext;
import org.apache.activemq.command.ConnectionInfo;
import org.apache.activemq.test.annotations.ParallelTest;
import org.apache.activemq.util.Wait;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests that CurrentConnectionsCount remains accurate even when broker
 * filter plugins throw exceptions after addConnection has partially
 * completed through the inner layers of the filter chain.
 */
@Category(ParallelTest.class)
public class CurrentConnectionsCountTest {

    private BrokerService brokerService;
    private ActiveMQConnectionFactory connectionFactory;
    private final AtomicBoolean failOnAddConnection = new AtomicBoolean(false);
    private final AtomicBoolean failOnRemoveConnection = new AtomicBoolean(false);

    @Before
    public void setUp() throws Exception {
        brokerService = new BrokerService();
        brokerService.setPersistent(false);
        brokerService.setUseJmx(true);

        // Install a plugin that can simulate a filter throwing after
        // the inner chain has already completed (like AdvisoryBroker
        // failing in fireAdvisory after ManagedRegionBroker incremented
        // the counter).
        brokerService.setPlugins(new BrokerPlugin[]{
            new BrokerPlugin() {
                @Override
                public Broker installPlugin(Broker broker) {
                    return new BrokerFilter(broker) {
                        @Override
                        public void addConnection(ConnectionContext context, ConnectionInfo info) throws Exception {
                            // Delegate to the inner chain first (counter gets incremented)
                            super.addConnection(context, info);
                            // Then throw, simulating e.g. AdvisoryBroker.fireAdvisory() failure
                            if (failOnAddConnection.get()) {
                                throw new RuntimeException("Simulated post-addConnection failure");
                            }
                        }

                        @Override
                        public void removeConnection(ConnectionContext context, ConnectionInfo info, Throwable error) throws Exception {
                            super.removeConnection(context, info, error);
                            if (failOnRemoveConnection.get()) {
                                throw new RuntimeException("Simulated post-removeConnection failure");
                            }
                        }
                    };
                }
            }
        });

        brokerService.addConnector("tcp://localhost:0");
        brokerService.start();
        brokerService.waitUntilStarted();

        connectionFactory = new ActiveMQConnectionFactory(
                brokerService.getTransportConnectors().get(0).getPublishableConnectString());
    }

    @After
    public void tearDown() throws Exception {
        failOnAddConnection.set(false);
        failOnRemoveConnection.set(false);
        if (brokerService != null) {
            brokerService.stop();
            brokerService.waitUntilStopped();
        }
    }

    @Test(timeout = 30000)
    public void testNormalConnectionCount() throws Exception {
        BrokerView view = brokerService.getAdminView();
        assertEquals(0, view.getCurrentConnectionsCount());

        Connection conn = connectionFactory.createConnection();
        conn.start();
        assertTrue(Wait.waitFor(() -> view.getCurrentConnectionsCount() == 1, 5000));

        conn.close();
        assertTrue(Wait.waitFor(() -> view.getCurrentConnectionsCount() == 0, 5000));
        assertEquals(1, view.getTotalConnectionsCount());
    }

    /**
     * Reproduces the original bug: a broker filter throws after
     * addConnection has completed in the inner chain. Without the fix,
     * the counter is incremented but never decremented, causing permanent
     * drift.
     */
    @Test(timeout = 30000)
    public void testCounterDoesNotDriftWhenFilterThrowsAfterAddConnection() throws Exception {
        BrokerView view = brokerService.getAdminView();
        assertEquals(0, view.getCurrentConnectionsCount());

        // Enable failure after inner chain completes addConnection
        failOnAddConnection.set(true);

        // Attempt several connections that will fail due to the plugin
        for (int i = 0; i < 10; i++) {
            try {
                Connection conn = connectionFactory.createConnection();
                conn.start();
            } catch (Exception expected) {
                // Connection should fail
            }
        }

        // Allow time for async cleanup
        failOnAddConnection.set(false);

        // The counter must return to 0 — no drift
        assertTrue("CurrentConnectionsCount should be 0 but was " + view.getCurrentConnectionsCount(),
                Wait.waitFor(() -> view.getCurrentConnectionsCount() == 0, 10000));
    }

    /**
     * Verify that after failed connections, normal connections still
     * track correctly.
     */
    @Test(timeout = 30000)
    public void testCounterAccurateAfterMixedFailuresAndSuccesses() throws Exception {
        BrokerView view = brokerService.getAdminView();

        // Cause some failed connections
        failOnAddConnection.set(true);
        for (int i = 0; i < 5; i++) {
            try {
                Connection conn = connectionFactory.createConnection();
                conn.start();
            } catch (Exception expected) {
            }
        }
        failOnAddConnection.set(false);

        // Allow async cleanup
        assertTrue("Counter should return to 0 after failures",
                Wait.waitFor(() -> view.getCurrentConnectionsCount() == 0, 10000));

        // Now establish real connections
        Connection conn1 = connectionFactory.createConnection();
        conn1.start();
        Connection conn2 = connectionFactory.createConnection();
        conn2.start();

        assertTrue(Wait.waitFor(() -> view.getCurrentConnectionsCount() == 2, 5000));

        conn1.close();
        assertTrue(Wait.waitFor(() -> view.getCurrentConnectionsCount() == 1, 5000));

        conn2.close();
        assertTrue(Wait.waitFor(() -> view.getCurrentConnectionsCount() == 0, 5000));
    }

    /**
     * Verify that the counter still decrements correctly when
     * super.removeConnection throws in the filter chain.
     */
    @Test(timeout = 30000)
    public void testCounterDecrementsWhenRemoveConnectionFilterThrows() throws Exception {
        BrokerView view = brokerService.getAdminView();

        Connection conn = connectionFactory.createConnection();
        conn.start();
        assertTrue(Wait.waitFor(() -> view.getCurrentConnectionsCount() == 1, 5000));

        // Enable failure in removeConnection post-processing
        failOnRemoveConnection.set(true);
        conn.close();
        failOnRemoveConnection.set(false);

        // Counter must still go back to 0 despite the exception
        assertTrue("CurrentConnectionsCount should be 0 but was " + view.getCurrentConnectionsCount(),
                Wait.waitFor(() -> view.getCurrentConnectionsCount() == 0, 10000));
    }
}
