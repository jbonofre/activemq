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
import java.util.Iterator;
import java.util.List;

import org.apache.activemq.broker.ConnectionContext;
import org.apache.activemq.command.ActiveMQDestination;
import org.apache.activemq.command.ActiveMQQueue;
import org.apache.activemq.command.Message;
import org.apache.activemq.command.MessageAck;
import org.apache.activemq.command.MessageId;
import org.apache.activemq.store.AbstractMessageStore;
import org.apache.activemq.store.MessageRecoveryListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RAFT-backed {@link org.apache.activemq.store.MessageStore} for queue destinations.
 *
 * <p>Writes (add/remove) are proposed through the RAFT log and block until the
 * entry is committed to a quorum. Reads are served directly from the local
 * {@link RaftStateMachine}, which reflects all committed state.
 *
 * <p>Operations that arrive within an active JMS/XA transaction are buffered via
 * {@link RaftTransactionStore} and applied atomically when the transaction commits.
 */
public class RaftMessageStore extends AbstractMessageStore {

    private static final Logger LOG = LoggerFactory.getLogger(RaftMessageStore.class);

    private final RaftNode raftNode;
    private final RaftStateMachine stateMachine;
    private final RaftTransactionStore transactionStore;
    private final String physicalName;

    /** Cursor index used by recoverNextMessages; position within the message list. */
    private volatile int batchOffset = 0;

    public RaftMessageStore(ActiveMQQueue destination, RaftNode raftNode,
                            RaftStateMachine stateMachine, RaftTransactionStore transactionStore) {
        super(destination);
        this.raftNode         = raftNode;
        this.stateMachine     = stateMachine;
        this.transactionStore = transactionStore;
        this.physicalName     = "queue://" + destination.getPhysicalName();
    }

    // ── MessageStore ──────────────────────────────────────────────────────────

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
            updateStats(message, true);
        }
    }

    @Override
    public void removeMessage(ConnectionContext context, MessageAck ack) throws IOException {
        String msgId = ack.getLastMessageId().toString();

        RaftLogEntry entry = new RaftLogEntry(
                0, RaftLogEntry.Type.REMOVE_MESSAGE,
                physicalName, msgId, null, null, null, null);

        if (isTransactional(context)) {
            transactionStore.bufferOperation(context.getTransaction().getTransactionId(), entry);
        } else {
            Message toRemove = stateMachine.getQueueMessage(physicalName, msgId);
            raftNode.propose(entry);
            if (toRemove != null) {
                updateStats(toRemove, false);
            }
        }
    }

    @Override
    public void removeAllMessages(ConnectionContext context) throws IOException {
        RaftLogEntry entry = new RaftLogEntry(
                0, RaftLogEntry.Type.REMOVE_ALL_MESSAGES,
                physicalName, null, null, null, null, null);
        raftNode.propose(entry);
        messageStoreStatistics.getMessageCount().setCount(0);
        messageStoreStatistics.getMessageSize().setTotalSize(0);
    }

    @Override
    public Message getMessage(MessageId identity) throws IOException {
        return stateMachine.getQueueMessage(physicalName, identity.toString());
    }

    @Override
    public void recover(MessageRecoveryListener container) throws Exception {
        List<Message> messages = stateMachine.getQueueMessages(physicalName);
        for (Message msg : messages) {
            if (!container.recoverMessage(msg)) {
                break;
            }
        }
    }

    @Override
    public void recoverNextMessages(int maxReturned, MessageRecoveryListener listener) throws Exception {
        List<Message> messages = stateMachine.getQueueMessages(physicalName);
        int count = 0;
        for (int i = batchOffset; i < messages.size() && count < maxReturned; i++, count++) {
            if (!listener.recoverMessage(messages.get(i))) {
                break;
            }
            batchOffset = i + 1;
        }
    }

    @Override
    public void resetBatching() {
        batchOffset = 0;
    }

    @Override
    public void setBatch(MessageId messageId) throws Exception {
        List<Message> messages = stateMachine.getQueueMessages(physicalName);
        String target = messageId.toString();
        for (int i = 0; i < messages.size(); i++) {
            if (target.equals(messages.get(i).getMessageId().toString())) {
                batchOffset = i + 1;
                return;
            }
        }
    }

    @Override
    public int getMessageCount() throws IOException {
        return stateMachine.getQueueMessageCount(physicalName);
    }

    @Override
    public long getMessageSize() throws IOException {
        return stateMachine.getQueueMessageSize(physicalName);
    }

    @Override
    public void updateMessage(Message message) throws IOException {
        // Re-use ADD_MESSAGE: the state machine will overwrite by msgId
        byte[] payload = stateMachine.marshalMessage(message);
        String msgId   = message.getMessageId().toString();
        RaftLogEntry entry = new RaftLogEntry(
                0, RaftLogEntry.Type.ADD_MESSAGE,
                physicalName, msgId, null, null, null, payload);
        raftNode.propose(entry);
    }

    @Override
    public StoreType getType() {
        return StoreType.RAFT;
    }

    @Override
    public void start() throws Exception {
        // Statistics are rebuilt from the state machine on first access
    }

    @Override
    public void stop() throws Exception {
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isTransactional(ConnectionContext context) {
        return context != null
                && context.getTransaction() != null
                && context.getTransaction().getTransactionId() != null;
    }

    private void updateStats(Message message, boolean adding) {
        if (adding) {
            messageStoreStatistics.getMessageCount().increment();
            messageStoreStatistics.getMessageSize().addSize(message.getSize());
        } else {
            messageStoreStatistics.getMessageCount().decrement();
            messageStoreStatistics.getMessageSize().addSize(-message.getSize());
        }
    }
}
