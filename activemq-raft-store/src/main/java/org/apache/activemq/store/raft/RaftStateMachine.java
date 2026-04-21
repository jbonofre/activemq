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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.activemq.command.ActiveMQDestination;
import org.apache.activemq.command.Message;
import org.apache.activemq.command.SubscriptionInfo;
import org.apache.activemq.openwire.OpenWireFormat;
import org.apache.activemq.util.ByteSequence;
import org.apache.activemq.util.SubscriptionKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory state machine that applies committed RAFT log entries.
 *
 * <p>Maintains the canonical committed view of:
 * <ul>
 *   <li>Queue messages per destination</li>
 *   <li>Topic messages per destination</li>
 *   <li>Durable topic subscriptions</li>
 *   <li>Per-subscription message acknowledgment tracking</li>
 *   <li>Prepared (but not yet committed) XA transactions</li>
 * </ul>
 *
 * <p>All state is held in memory. Durability is provided by the RAFT log on disk;
 * the state machine is rebuilt by replaying the log on startup.
 * All public methods are synchronized.
 */
public class RaftStateMachine {

    private static final Logger LOG = LoggerFactory.getLogger(RaftStateMachine.class);

    // destination → (messageId → Message), insertion-ordered for recovery
    private final Map<String, LinkedHashMap<String, Message>> queues       = new ConcurrentHashMap<>();
    private final Map<String, LinkedHashMap<String, Message>> topics       = new ConcurrentHashMap<>();

    // destination → (subKey → SubscriptionInfo)
    private final Map<String, Map<String, SubscriptionInfo>> subscriptions = new ConcurrentHashMap<>();

    // destination → (subKey → ordered set of pending messageIds)
    private final Map<String, Map<String, LinkedHashSet<String>>> subMessages = new ConcurrentHashMap<>();

    // txId → list of pending log entries (for XA prepared transactions)
    private final Map<String, List<RaftLogEntry>> preparedTxOps = new ConcurrentHashMap<>();

    private final OpenWireFormat wireFormat = new OpenWireFormat();

    // ── Apply ─────────────────────────────────────────────────────────────────

    /**
     * Apply a committed log entry to the state machine.
     */
    public synchronized void apply(RaftLogEntry entry) throws IOException {
        LOG.trace("Applying log entry: {}", entry);
        switch (entry.getType()) {
            case ADD_MESSAGE         -> applyAddMessage(entry);
            case REMOVE_MESSAGE      -> applyRemoveMessage(entry);
            case REMOVE_ALL_MESSAGES -> applyRemoveAllMessages(entry);
            case ADD_SUBSCRIPTION    -> applyAddSubscription(entry);
            case DELETE_SUBSCRIPTION -> applyDeleteSubscription(entry);
            case ACKNOWLEDGE         -> applyAcknowledge(entry);
            case PREPARE_TX          -> applyPrepareTx(entry);
            case COMMIT_TX           -> applyCommitTx(entry);
            case ROLLBACK_TX         -> applyRollbackTx(entry);
            case REMOVE_DESTINATION  -> applyRemoveDestination(entry);
        }
    }

    private void applyAddMessage(RaftLogEntry entry) throws IOException {
        Message msg = unmarshalMessage(entry.getPayload());
        String dest = entry.getDestination();
        String msgId = entry.getMessageId();

        if (isTopic(dest)) {
            topics.computeIfAbsent(dest, k -> new LinkedHashMap<>()).put(msgId, msg);
            // Distribute to all active durable subscriptions
            Map<String, SubscriptionInfo> subs = subscriptions.getOrDefault(dest, Collections.emptyMap());
            for (String subKey : subs.keySet()) {
                subMessages.computeIfAbsent(dest, k -> new ConcurrentHashMap<>())
                           .computeIfAbsent(subKey, k -> new LinkedHashSet<>())
                           .add(msgId);
            }
        } else {
            queues.computeIfAbsent(dest, k -> new LinkedHashMap<>()).put(msgId, msg);
        }
    }

    private void applyRemoveMessage(RaftLogEntry entry) {
        String dest = entry.getDestination();
        String msgId = entry.getMessageId();
        if (isTopic(dest)) {
            topics.getOrDefault(dest, new LinkedHashMap<>()).remove(msgId);
        } else {
            queues.getOrDefault(dest, new LinkedHashMap<>()).remove(msgId);
        }
    }

    private void applyRemoveAllMessages(RaftLogEntry entry) {
        String dest = entry.getDestination();
        if (isTopic(dest)) {
            topics.getOrDefault(dest, new LinkedHashMap<>()).clear();
            subMessages.getOrDefault(dest, Collections.emptyMap()).values().forEach(Set::clear);
        } else {
            queues.getOrDefault(dest, new LinkedHashMap<>()).clear();
        }
    }

    private void applyAddSubscription(RaftLogEntry entry) throws IOException {
        String dest   = entry.getDestination();
        String subKey = subKey(entry.getClientId(), entry.getSubscriptionName());
        SubscriptionInfo info = unmarshalSubscriptionInfo(entry.getPayload());
        subscriptions.computeIfAbsent(dest, k -> new HashMap<>()).put(subKey, info);
        // Create an empty pending message set for the new subscription
        subMessages.computeIfAbsent(dest, k -> new ConcurrentHashMap<>())
                   .computeIfAbsent(subKey, k -> new LinkedHashSet<>());

        // Retroactive: if the entry's payload includes a retroactive flag,
        // we would add all existing topic messages. For now, non-retroactive by default.
    }

    private void applyDeleteSubscription(RaftLogEntry entry) {
        String dest   = entry.getDestination();
        String subKey = subKey(entry.getClientId(), entry.getSubscriptionName());
        Map<String, SubscriptionInfo> subs = subscriptions.get(dest);
        if (subs != null) {
            subs.remove(subKey);
        }
        Map<String, LinkedHashSet<String>> msgs = subMessages.get(dest);
        if (msgs != null) {
            msgs.remove(subKey);
        }
    }

    private void applyAcknowledge(RaftLogEntry entry) {
        String dest   = entry.getDestination();
        String subKey = subKey(entry.getClientId(), entry.getSubscriptionName());
        String msgId  = entry.getMessageId();

        Map<String, LinkedHashSet<String>> msgs = subMessages.get(dest);
        if (msgs != null) {
            LinkedHashSet<String> pendingIds = msgs.get(subKey);
            if (pendingIds != null) {
                pendingIds.remove(msgId);
            }
        }
        // Remove the actual message from the topic store if no subscriptions still reference it
        if (noSubscriptionHasMessage(dest, msgId)) {
            topics.getOrDefault(dest, new LinkedHashMap<>()).remove(msgId);
        }
    }

    private boolean noSubscriptionHasMessage(String dest, String msgId) {
        Map<String, LinkedHashSet<String>> msgs = subMessages.get(dest);
        if (msgs == null) {
            return true;
        }
        for (LinkedHashSet<String> pending : msgs.values()) {
            if (pending.contains(msgId)) {
                return false;
            }
        }
        return true;
    }

    private void applyPrepareTx(RaftLogEntry entry) {
        preparedTxOps.put(entry.getTransactionId(), new ArrayList<>());
    }

    private void applyCommitTx(RaftLogEntry entry) throws IOException {
        List<RaftLogEntry> ops = preparedTxOps.remove(entry.getTransactionId());
        if (ops != null) {
            for (RaftLogEntry op : ops) {
                apply(op);
            }
        }
    }

    private void applyRollbackTx(RaftLogEntry entry) {
        preparedTxOps.remove(entry.getTransactionId());
    }

    private void applyRemoveDestination(RaftLogEntry entry) {
        String dest = entry.getDestination();
        queues.remove(dest);
        topics.remove(dest);
        subscriptions.remove(dest);
        subMessages.remove(dest);
    }

    /**
     * Buffer a log entry as part of an in-progress XA transaction.
     * The entry will be applied when the transaction is committed.
     */
    public synchronized void bufferTxOperation(String txId, RaftLogEntry entry) {
        preparedTxOps.computeIfAbsent(txId, k -> new ArrayList<>()).add(entry);
    }

    // ── Read methods (used by the store implementations) ─────────────────────

    public synchronized Message getQueueMessage(String dest, String msgId) {
        return queues.getOrDefault(dest, new LinkedHashMap<>()).get(msgId);
    }

    public synchronized List<Message> getQueueMessages(String dest) {
        return new ArrayList<>(queues.getOrDefault(dest, new LinkedHashMap<>()).values());
    }

    public synchronized int getQueueMessageCount(String dest) {
        return queues.getOrDefault(dest, new LinkedHashMap<>()).size();
    }

    public synchronized long getQueueMessageSize(String dest) {
        long size = 0;
        for (Message msg : queues.getOrDefault(dest, new LinkedHashMap<>()).values()) {
            size += msg.getSize();
        }
        return size;
    }

    public synchronized Message getTopicMessage(String dest, String msgId) {
        return topics.getOrDefault(dest, new LinkedHashMap<>()).get(msgId);
    }

    public synchronized List<Message> getTopicMessages(String dest) {
        return new ArrayList<>(topics.getOrDefault(dest, new LinkedHashMap<>()).values());
    }

    public synchronized SubscriptionInfo lookupSubscription(String dest, String clientId, String subName) {
        return subscriptions.getOrDefault(dest, Collections.emptyMap()).get(subKey(clientId, subName));
    }

    public synchronized SubscriptionInfo[] getAllSubscriptions(String dest) {
        Map<String, SubscriptionInfo> subs = subscriptions.getOrDefault(dest, Collections.emptyMap());
        return subs.values().toArray(new SubscriptionInfo[0]);
    }

    public synchronized List<Message> getSubscriptionMessages(String dest, String clientId, String subName) {
        Map<String, LinkedHashSet<String>> msgs = subMessages.get(dest);
        if (msgs == null) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> ids = msgs.get(subKey(clientId, subName));
        if (ids == null) {
            return Collections.emptyList();
        }
        Map<String, Message> topicMsgs = topics.getOrDefault(dest, new LinkedHashMap<>());
        List<Message> result = new ArrayList<>();
        for (String id : ids) {
            Message msg = topicMsgs.get(id);
            if (msg != null) {
                result.add(msg);
            }
        }
        return result;
    }

    public synchronized int getSubscriptionMessageCount(String dest, String clientId, String subName) {
        Map<String, LinkedHashSet<String>> msgs = subMessages.get(dest);
        if (msgs == null) return 0;
        LinkedHashSet<String> ids = msgs.get(subKey(clientId, subName));
        return ids == null ? 0 : ids.size();
    }

    public synchronized long getSubscriptionMessageSize(String dest, String clientId, String subName) {
        List<Message> messages = getSubscriptionMessages(dest, clientId, subName);
        long size = 0;
        for (Message msg : messages) {
            size += msg.getSize();
        }
        return size;
    }

    public synchronized Set<String> getAllDestinations() {
        Set<String> dests = new HashSet<>();
        dests.addAll(queues.keySet());
        dests.addAll(topics.keySet());
        return Collections.unmodifiableSet(dests);
    }

    public synchronized Map<String, SubscriptionInfo> getAllPreparedTransactions() {
        // Returns a map of txId → null (we track keys only here for simplicity)
        Map<String, SubscriptionInfo> result = new HashMap<>();
        for (String txId : preparedTxOps.keySet()) {
            result.put(txId, null);
        }
        return result;
    }

    public synchronized Set<String> getPreparedTransactionIds() {
        return new HashSet<>(preparedTxOps.keySet());
    }

    // ── Serialization helpers ─────────────────────────────────────────────────

    /** Serialize an ActiveMQ Message to bytes using OpenWireFormat. */
    public byte[] marshalMessage(Message message) throws IOException {
        synchronized (wireFormat) {
            ByteSequence bs = wireFormat.marshal(message);
            // ByteSequence may have extra capacity – copy only the live bytes
            byte[] result = new byte[bs.getLength()];
            System.arraycopy(bs.getData(), bs.getOffset(), result, 0, bs.getLength());
            return result;
        }
    }

    /** Deserialize bytes back to an ActiveMQ Message using OpenWireFormat. */
    public Message unmarshalMessage(byte[] bytes) throws IOException {
        synchronized (wireFormat) {
            return (Message) wireFormat.unmarshal(new ByteSequence(bytes));
        }
    }

    /** Serialize a SubscriptionInfo to bytes using OpenWireFormat. */
    public byte[] marshalSubscriptionInfo(SubscriptionInfo info) throws IOException {
        synchronized (wireFormat) {
            ByteSequence bs = wireFormat.marshal(info);
            byte[] result = new byte[bs.getLength()];
            System.arraycopy(bs.getData(), bs.getOffset(), result, 0, bs.getLength());
            return result;
        }
    }

    /** Deserialize bytes back to a SubscriptionInfo using OpenWireFormat. */
    public SubscriptionInfo unmarshalSubscriptionInfo(byte[] bytes) throws IOException {
        synchronized (wireFormat) {
            return (SubscriptionInfo) wireFormat.unmarshal(new ByteSequence(bytes));
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static boolean isTopic(String dest) {
        return dest != null && dest.startsWith("topic://");
    }

    private static String subKey(String clientId, String subscriptionName) {
        return clientId + ":" + subscriptionName;
    }
}
