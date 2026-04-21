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
package org.apache.activemq.store.raft;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.activemq.command.ActiveMQQueue;
import org.apache.activemq.command.ActiveMQTextMessage;
import org.apache.activemq.command.ActiveMQTopic;
import org.apache.activemq.command.Message;
import org.apache.activemq.command.MessageAck;
import org.apache.activemq.command.MessageId;
import org.apache.activemq.command.ProducerId;
import org.apache.activemq.command.SubscriptionInfo;
import org.apache.activemq.store.MessageRecoveryListener;
import org.apache.activemq.store.MessageStore;
import org.apache.activemq.store.TopicMessageStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Integration tests for a single-node RAFT cluster.
 * A single node with no peers is always the leader and commits immediately.
 */
public class RaftPersistenceAdapterTest {

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    private RaftPersistenceAdapter adapter;

    private static final ProducerId PRODUCER;
    private static final AtomicLong SEQ = new AtomicLong(1);

    static {
        PRODUCER = new ProducerId();
        PRODUCER.setConnectionId("test-connection");
        PRODUCER.setSessionId(1);
        PRODUCER.setValue(1);
    }

    @Before
    public void setUp() throws Exception {
        adapter = new RaftPersistenceAdapter();
        adapter.setNodeId("test-node");
        adapter.setPort(17150);
        adapter.setPeers(""); // single-node cluster → always leader
        adapter.setDirectory(tmpDir.newFolder("raft-data"));
        adapter.start();

        // Wait for leadership (single node wins election immediately)
        long deadline = System.currentTimeMillis() + 5_000;
        while (!adapter.isLeader() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue("Node should become leader in a single-node cluster", adapter.isLeader());
    }

    @After
    public void tearDown() throws Exception {
        if (adapter != null) {
            adapter.stop();
        }
    }

    // ── Queue tests ───────────────────────────────────────────────────────────

    @Test
    public void testAddAndGetQueueMessage() throws Exception {
        MessageStore store = adapter.createQueueMessageStore(new ActiveMQQueue("TEST.QUEUE"));
        store.start();

        ActiveMQTextMessage msg = buildTextMessage("Hello RAFT");
        MessageId mid = msg.getMessageId();
        store.addMessage(null, msg);

        Message retrieved = store.getMessage(mid);
        assertNotNull("Message should be retrievable after add", retrieved);
        assertEquals(mid.toString(), retrieved.getMessageId().toString());
    }

    @Test
    public void testRemoveQueueMessage() throws Exception {
        MessageStore store = adapter.createQueueMessageStore(new ActiveMQQueue("TEST.REMOVE"));
        store.start();

        ActiveMQTextMessage msg = buildTextMessage("to delete");
        MessageId mid = msg.getMessageId();
        store.addMessage(null, msg);
        assertNotNull(store.getMessage(mid));

        MessageAck ack = new MessageAck();
        ack.setLastMessageId(mid);
        store.removeMessage(null, ack);

        assertNull("Message should be gone after remove", store.getMessage(mid));
    }

    @Test
    public void testQueueMessageCount() throws Exception {
        MessageStore store = adapter.createQueueMessageStore(new ActiveMQQueue("TEST.COUNT"));
        store.start();

        assertEquals(0, store.getMessageCount());

        for (int i = 0; i < 5; i++) {
            store.addMessage(null, buildTextMessage("body-" + i));
        }
        assertEquals(5, store.getMessageCount());
    }

    @Test
    public void testRecoverQueueMessages() throws Exception {
        MessageStore store = adapter.createQueueMessageStore(new ActiveMQQueue("TEST.RECOVER"));
        store.start();

        List<MessageId> added = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ActiveMQTextMessage msg = buildTextMessage("body-" + i);
            added.add(msg.getMessageId());
            store.addMessage(null, msg);
        }

        final List<String> recovered = new ArrayList<>();
        store.recover(new CollectingListener(recovered));

        assertEquals(3, recovered.size());
        for (MessageId mid : added) {
            assertTrue("Should have recovered " + mid, recovered.contains(mid.toString()));
        }
    }

    @Test
    public void testRemoveAllQueueMessages() throws Exception {
        MessageStore store = adapter.createQueueMessageStore(new ActiveMQQueue("TEST.CLEAR"));
        store.start();

        store.addMessage(null, buildTextMessage("body1"));
        store.addMessage(null, buildTextMessage("body2"));
        assertEquals(2, store.getMessageCount());

        store.removeAllMessages(null);
        assertEquals(0, store.getMessageCount());
    }

    // ── Topic tests ───────────────────────────────────────────────────────────

    @Test
    public void testTopicMessageAndSubscription() throws Exception {
        ActiveMQTopic dest = new ActiveMQTopic("TEST.TOPIC");
        TopicMessageStore store = adapter.createTopicMessageStore(dest);
        store.start();

        store.addSubscription(buildSubscriptionInfo("client1", "sub1", dest), false);

        ActiveMQTextMessage msg = buildTextMessage("topic body");
        MessageId mid = msg.getMessageId();
        store.addMessage(null, msg);

        final List<Message> subMessages = new ArrayList<>();
        store.recoverSubscription("client1", "sub1", new CollectingListener(null) {
            @Override
            public boolean recoverMessage(Message m) throws Exception {
                subMessages.add(m);
                return true;
            }
        });

        assertEquals(1, subMessages.size());
        assertEquals(mid.toString(), subMessages.get(0).getMessageId().toString());
    }

    @Test
    public void testTopicAcknowledge() throws Exception {
        ActiveMQTopic dest = new ActiveMQTopic("TEST.TOPIC.ACK");
        TopicMessageStore store = adapter.createTopicMessageStore(dest);
        store.start();

        store.addSubscription(buildSubscriptionInfo("client2", "sub2", dest), false);

        ActiveMQTextMessage msg = buildTextMessage("ack me");
        MessageId mid = msg.getMessageId();
        store.addMessage(null, msg);

        assertEquals(1, store.getMessageCount("client2", "sub2"));

        MessageAck ack = new MessageAck();
        ack.setLastMessageId(mid);
        store.acknowledge(null, "client2", "sub2", mid, ack);

        assertEquals(0, store.getMessageCount("client2", "sub2"));
    }

    @Test
    public void testTopicLookupAndDeleteSubscription() throws Exception {
        ActiveMQTopic dest = new ActiveMQTopic("TEST.TOPIC.SUB");
        TopicMessageStore store = adapter.createTopicMessageStore(dest);
        store.start();

        store.addSubscription(buildSubscriptionInfo("clientX", "subX", dest), false);

        SubscriptionInfo found = store.lookupSubscription("clientX", "subX");
        assertNotNull(found);
        assertEquals("clientX", found.getClientId());

        store.deleteSubscription("clientX", "subX");
        assertNull(store.lookupSubscription("clientX", "subX"));
    }

    // ── Adapter-level tests ───────────────────────────────────────────────────

    @Test
    public void testStoreType() throws Exception {
        MessageStore store = adapter.createQueueMessageStore(new ActiveMQQueue("TYPE.TEST"));
        assertEquals(MessageStore.StoreType.RAFT, store.getType());
    }

    @Test
    public void testLastBrokerSequenceIdAdvances() throws Exception {
        long seqBefore = adapter.getLastMessageBrokerSequenceId();
        MessageStore store = adapter.createQueueMessageStore(new ActiveMQQueue("SEQ.QUEUE"));
        store.addMessage(null, buildTextMessage("body"));
        long seqAfter = adapter.getLastMessageBrokerSequenceId();
        assertTrue("Commit index should have advanced after a write", seqAfter > seqBefore);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Build a text message with an auto-incremented ProducerId-based MessageId. */
    private static ActiveMQTextMessage buildTextMessage(String text) throws Exception {
        ActiveMQTextMessage msg = new ActiveMQTextMessage();
        MessageId mid = new MessageId(PRODUCER, SEQ.getAndIncrement());
        msg.setMessageId(mid);
        msg.setText(text);
        msg.setDestination(new ActiveMQQueue("test"));
        return msg;
    }

    private static SubscriptionInfo buildSubscriptionInfo(String clientId, String subName,
                                                          ActiveMQTopic dest) {
        SubscriptionInfo info = new SubscriptionInfo();
        info.setClientId(clientId);
        info.setSubcriptionName(subName);
        info.setDestination(dest);
        return info;
    }

    /** A simple MessageRecoveryListener that collects recovered message IDs. */
    private static class CollectingListener implements MessageRecoveryListener {
        private final List<String> ids;

        CollectingListener(List<String> ids) {
            this.ids = ids;
        }

        @Override
        public boolean recoverMessage(Message message) throws Exception {
            if (ids != null) {
                ids.add(message.getMessageId().toString());
            }
            return true;
        }

        @Override
        public boolean recoverMessageReference(MessageId ref) throws Exception {
            return true;
        }

        @Override
        public boolean hasSpace() {
            return true;
        }

        @Override
        public boolean isDuplicate(MessageId ref) {
            return false;
        }
    }
}
