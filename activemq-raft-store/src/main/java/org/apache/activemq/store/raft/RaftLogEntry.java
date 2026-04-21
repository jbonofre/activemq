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

import java.io.Serializable;

/**
 * Represents a single entry in the RAFT replicated log.
 * Each entry encodes one operation to be applied to the message store state machine.
 */
public class RaftLogEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Type {
        /** Add a message to a queue or topic destination */
        ADD_MESSAGE,
        /** Remove a message from a queue destination by message ID */
        REMOVE_MESSAGE,
        /** Remove all messages from a destination */
        REMOVE_ALL_MESSAGES,
        /** Add a durable topic subscription */
        ADD_SUBSCRIPTION,
        /** Delete a durable topic subscription */
        DELETE_SUBSCRIPTION,
        /** Acknowledge a message for a durable topic subscription */
        ACKNOWLEDGE,
        /** Mark an XA transaction as prepared */
        PREPARE_TX,
        /** Commit an XA transaction (applies buffered operations) */
        COMMIT_TX,
        /** Rollback an XA transaction (discards buffered operations) */
        ROLLBACK_TX,
        /** Remove a destination entirely */
        REMOVE_DESTINATION
    }

    /** RAFT term in which this entry was created */
    private long term;

    /** 1-based position in the replicated log */
    private long index;

    /** The operation type */
    private final Type type;

    /** Physical destination name (e.g., "queue://orders" or "topic://events") */
    private final String destination;

    /** Message ID for message operations; null for other types */
    private final String messageId;

    /** Client ID for subscription operations; null for other types */
    private final String clientId;

    /** Subscription name for subscription operations; null for other types */
    private final String subscriptionName;

    /** String representation of the transaction ID for tx operations; null for other types */
    private final String transactionId;

    /** Serialized payload: OpenWire-marshaled Message or SubscriptionInfo bytes */
    private final byte[] payload;

    public RaftLogEntry(long term, Type type, String destination,
                        String messageId, String clientId, String subscriptionName,
                        String transactionId, byte[] payload) {
        this.term = term;
        this.type = type;
        this.destination = destination;
        this.messageId = messageId;
        this.clientId = clientId;
        this.subscriptionName = subscriptionName;
        this.transactionId = transactionId;
        this.payload = payload;
    }

    public long getTerm() {
        return term;
    }

    public void setTerm(long term) {
        this.term = term;
    }

    public long getIndex() {
        return index;
    }

    public void setIndex(long index) {
        this.index = index;
    }

    public Type getType() {
        return type;
    }

    public String getDestination() {
        return destination;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getClientId() {
        return clientId;
    }

    public String getSubscriptionName() {
        return subscriptionName;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public byte[] getPayload() {
        return payload;
    }

    @Override
    public String toString() {
        return "RaftLogEntry{term=" + term + ", index=" + index + ", type=" + type
                + ", dest='" + destination + "', msgId='" + messageId + "'}";
    }
}
