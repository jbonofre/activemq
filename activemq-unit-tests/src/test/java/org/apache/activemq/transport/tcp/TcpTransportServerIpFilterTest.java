/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.transport.tcp;

import java.net.Socket;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.jms.Connection;
import jakarta.jms.JMSException;

import junit.framework.TestCase;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;

public class TcpTransportServerIpFilterTest extends TestCase {

    private BrokerService broker;

    @Override
    protected void tearDown() throws Exception {
        if (broker != null) {
            broker.stop();
            broker.waitUntilStopped();
        }
    }

    public void testAllowedIpsAcceptsMatchingConnection() throws Exception {
        broker = createBroker("tcp://127.0.0.1:0?allowedIps=127.0.0.0/8");
        broker.start();
        broker.waitUntilStarted();

        String connectUri = broker.getTransportConnectorByScheme("tcp").getPublishableConnectString();
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(connectUri);
        Connection connection = factory.createConnection();
        connection.start();
        connection.close();
    }

    public void testAllowedIpsRejectsNonMatchingConnection() throws Exception {
        // Allow only 10.0.0.0/8 - connections from 127.0.0.1 should be rejected
        broker = createBroker("tcp://127.0.0.1:0?allowedIps=10.0.0.0/8");
        broker.start();
        broker.waitUntilStarted();

        String connectUri = broker.getTransportConnectorByScheme("tcp").getPublishableConnectString();
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(connectUri);
        factory.setConnectResponseTimeout(3000);
        try {
            Connection connection = factory.createConnection();
            connection.start();
            connection.close();
            fail("Connection should have been rejected");
        } catch (JMSException e) {
            // expected - connection rejected
        }
    }

    public void testDeniedIpsRejectsMatchingConnection() throws Exception {
        // Deny 127.0.0.0/8 - connections from localhost should be rejected
        broker = createBroker("tcp://127.0.0.1:0?deniedIps=127.0.0.0/8");
        broker.start();
        broker.waitUntilStarted();

        String connectUri = broker.getTransportConnectorByScheme("tcp").getPublishableConnectString();
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(connectUri);
        factory.setConnectResponseTimeout(3000);
        try {
            Connection connection = factory.createConnection();
            connection.start();
            connection.close();
            fail("Connection should have been rejected");
        } catch (JMSException e) {
            // expected - connection denied
        }
    }

    public void testDenyListTakesPrecedenceOverAllowList() throws Exception {
        // Allow 127.0.0.0/8 but also deny 127.0.0.1 specifically
        broker = createBroker("tcp://127.0.0.1:0?allowedIps=127.0.0.0/8&deniedIps=127.0.0.1");
        broker.start();
        broker.waitUntilStarted();

        String connectUri = broker.getTransportConnectorByScheme("tcp").getPublishableConnectString();
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(connectUri);
        factory.setConnectResponseTimeout(3000);
        try {
            Connection connection = factory.createConnection();
            connection.start();
            connection.close();
            fail("Connection should have been rejected because deny list takes precedence");
        } catch (JMSException e) {
            // expected - deny list takes precedence
        }
    }

    private BrokerService createBroker(String transportUri) throws Exception {
        BrokerService broker = new BrokerService();
        broker.setPersistent(false);
        broker.setUseJmx(false);
        broker.addConnector(new URI(transportUri));
        return broker;
    }
}
