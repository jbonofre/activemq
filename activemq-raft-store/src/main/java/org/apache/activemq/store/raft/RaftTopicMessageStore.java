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

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.activemq.broker.ConnectionContext;
import org.apache.activemq.command.ActiveMQTopic;
import org.apache.activemq.command.Message;
import org.apache.activemq.command.MessageAck;
import org.apache.activemq.command.MessageId;
import org.apache.activemq.command.SubscriptionInfo;
import org.apache.activemq.store.AbstractMessageStore;
import org.apache.activemq.store.MessageRecoveryListener;
import org.apache.activemq.store.MessageStoreSubscriptionStatistics;
import org.apache.activemq.store.TopicMessageStore;
import org.apache.activemq.util.SubscriptionKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RAFT-backed {@link TopicMessageStore} for durable topic subscriptions.
 *
 * <p>Topic-specific operations (addSubscription, acknowledge, etc.) are proposed
 * through the RAFT log and applied to the {@link RaftStateMachine} once committed.
 * Reads are served from the local committed state.
 */
public class RaftTopicMessageStore extends AbstractMessageStore implements TopicMessageStore {

    private static final Logger LOG = LoggerFactory.getLogger(RaftTopicMessageStore.class);

    private final RaftNode raftNode;
    private final RaftStateMachine stateMachine;
    private final RaftTransactionStore transactionStore;
    private final String physicalName;

    private final MessageStoreSubscriptionStatistics subStats =
            new MessageStoreSubscriptionStatistics(false);

    /** Per-subscription cursor offsets for recoverNextMessages. */
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> batchOffsets =
            new java.util.concurrent.ConcurrentHashMap<>();

    public RaftTopicMessageStore(ActiveMQTopic destination, RaftNode raftNode,
                                 RaftStateMachine stateMachine,
                                 RaftTransactionStore transactionStore) {
        super(destination);
        this.raftNode         = raftNode;
        this.stateMachine     = stateMachine;
        this.transactionStore = transactionStore;
        this.physicalName     = "topic://" + destination.getPhysicalName();
    }

    // ── MessageStore (topic messages) ─────────────────────────────────────────

    @Override
    public void addMessage(ConnectionContext context, Message message) throws IOException {
        byte[] payload = stateMachine.marshalMessage(message);
        String msgId   = message.getMessageId().toString();

        RaftLogEntry entry = new RaftLogEntry(
                0, RaftLogEntry.Type.ADD_MESSAGE,
                physicalName, msgId, null, null, null, payload);

        if (isTransactional(context)) {
            transactionStore.bufferOperation(context.getTransaction().getTransactionId(), entry);
        } else {
            raftNode.propose(entry);
        }
    }

    @Override
    public void removeMessage(ConnectionContext context, MessageAck ack) throws IOException {
        // For topics, message removal happens via acknowledge()
        String msgId = ack.getLastMessageId().toString();
        RaftLogEntry entry = new RaftLogEntry(
                0, RaftLogEntry.Type.REMOVE_MESSAGE,
                physicalName, msgId, null, null, null, null);
        raftNode.propose(entry);
    }

    @Override
    public void removeAllMessages(ConnectionContext context) throws IOException {
        RaftLogEntry entry = new RaftLogEntry(
                0, RaftLogEntry.Type.REMOVE_ALL_MESSAGES,
                physicalName, null, null, null, null, null);
        raftNode.propose(entry);
    }

    @Override
    public Message getMessage(MessageId identity) throws IOException {
        return stateMachine.getTopicMessage(physicalName, identity.toString());
    }

    @Override
    public void recover(MessageRecoveryListener container) throws Exception {
        List<Message> messages = stateMachine.getTopicMessages(physicalName);
        for (Message msg : messages) {
            if (!container.recoverMessage(msg)) {
                break;
            }
        }
    }

    @Override
    public void recoverNextMessages(int maxReturned, MessageRecoveryListener listener) throws Exception {
        List<Message> messages = stateMachine.getTopicMessages(physicalName);
        // Use a generic "all" cursor for non-subscription recovery
        int offset = batchOffsets.getOrDefault("__all__", 0);
        int count = 0;
        for (int i = offset; i < messages.size() && count < maxReturned; i++, count++) {
            if (!listener.recoverMessage(messages.get(i))) {
                break;
            }
            batchOffsets.put("__all__", i + 1);
        }
    }

    @Override
    public void resetBatching() {
        batchOffsets.clear();
    }

    @Override
    public int getMessageCount() throws IOException {
        return stateMachine.getTopicMessages(physicalName).size();
    }

    @Override
    public long getMessageSize() throws IOException {
        long size = 0;
        for (Message msg : stateMachine.getTopicMessages(physicalName)) {
            size += msg.getSize();
        }
        return size;
    }

    @Override
    public StoreType getType() {
        return StoreType.RAFT;
    }

    @Override
    public void start() throws Exception {
    }

    @Override
    public void stop() throws Exception {
    }

    // ── TopicMessageStore ─────────────────────────────────────────────────────

    @Override
    public void addSubscription(SubscriptionInfo subscriptionInfo, boolean retroactive)
            throws IOException {
        byte[] payload = stateMachine.marshalSubscriptionInfo(subscriptionInfo);

        RaftLogEntry entry = new RaftLogEntry(
                0, RaftLogEntry.Type.ADD_SUBSCRIPTION,
                physicalName, null,
                subscriptionInfo.getClientId(),
                subscriptionInfo.getSubcriptionName(),
                null, payload);
        raftNode.propose(entry);
        LOG.debug("Subscription added: {}:{} on {}", subscriptionInfo.getClientId(),
                subscriptionInfo.getSubcriptionName(), physicalName);
    }

    @Override
    public void deleteSubscription(String clientId, String subscriptionName) throws IOException {
        RaftLogEntry entry = new RaftLogEntry(
                0, RaftLogEntry.Type.DELETE_SUBSCRIPTION,
                physicalName, null, clientId, subscriptionName, null, null);
        raftNode.propose(entry);
        LOG.debug("Subscription deleted: {}:{} on {}", clientId, subscriptionName, physicalName);
    }

    @Override
    public void acknowledge(ConnectionContext context, String clientId, String subscriptionName,
                            MessageId messageId, MessageAck ack) throws IOException {
        String msgId = messageId.toString();
        RaftLogEntry entry = new RaftLogEntry(
                0, RaftLogEntry.Type.ACKNOWLEDGE,
                physicalName, msgId, clientId, subscriptionName, null, null);

        if (isTransactional(context)) {
            transactionStore.bufferOperation(context.getTransaction().getTransactionId(), entry);
        } else {
            raftNode.propose(entry);
        }
    }

    @Override
    public void recoverSubscription(String clientId, String subscriptionName,
                                    MessageRecoveryListener listener) throws Exception {
        List<Message> messages = stateMachine.getSubscriptionMessages(physicalName, clientId, subscriptionName);
        for (Message msg : messages) {
            if (!listener.recoverMessage(msg)) {
                break;
            }
        }
    }

    @Override
    public void recoverNextMessages(String clientId, String subscriptionName,
                                    int maxReturned, MessageRecoveryListener listener) throws Exception {
        List<Message> messages = stateMachine.getSubscriptionMessages(
                physicalName, clientId, subscriptionName);
        String cursorKey = clientId + ":" + subscriptionName;
        int offset = batchOffsets.getOrDefault(cursorKey, 0);
        int count = 0;
        for (int i = offset; i < messages.size() && count < maxReturned; i++, count++) {
            if (!listener.recoverMessage(messages.get(i))) {
                break;
            }
            batchOffsets.put(cursorKey, i + 1);
        }
    }

    @Override
    public void resetBatching(String clientId, String subscriptionName) {
        batchOffsets.remove(clientId + ":" + subscriptionName);
    }

    @Override
    public SubscriptionInfo lookupSubscription(String clientId, String subscriptionName)
            throws IOException {
        return stateMachine.lookupSubscription(physicalName, clientId, subscriptionName);
    }

    @Override
    public SubscriptionInfo[] getAllSubscriptions() throws IOException {
        return stateMachine.getAllSubscriptions(physicalName);
    }

    @Override
    public int getMessageCount(String clientId, String subscriberName) throws IOException {
        return stateMachine.getSubscriptionMessageCount(physicalName, clientId, subscriberName);
    }

    @Override
    public long getMessageSize(String clientId, String subscriberName) throws IOException {
        return stateMachine.getSubscriptionMessageSize(physicalName, clientId, subscriberName);
    }

    @Override
    public Map<SubscriptionKey, List<Message>> recoverExpired(
            Set<SubscriptionKey> subs, int maxBrowse, MessageRecoveryListener listener)
            throws Exception {
        // Basic implementation: check each subscription for expired messages
        java.util.Map<SubscriptionKey, List<Message>> result = new java.util.HashMap<>();
        for (SubscriptionKey key : subs) {
            List<Message> messages = stateMachine.getSubscriptionMessages(
                    physicalName, key.getClientId(), key.getSubscriptionName());
            long now = System.currentTimeMillis();
            List<Message> expired = new java.util.ArrayList<>();
            for (Message msg : messages) {
                if (msg.isExpired()) {
                    expired.add(msg);
                    listener.recoverMessage(msg);
                }
                if (expired.size() >= maxBrowse) {
                    break;
                }
            }
            if (!expired.isEmpty()) {
                result.put(key, expired);
            }
        }
        return result;
    }

    @Override
    public MessageStoreSubscriptionStatistics getMessageStoreSubStatistics() {
        return subStats;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isTransactional(ConnectionContext context) {
        return context != null
                && context.getTransaction() != null
                && context.getTransaction().getTransactionId() != null;
    }
}
