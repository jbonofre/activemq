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

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.BrokerServiceAware;
import org.apache.activemq.broker.ConnectionContext;
import org.apache.activemq.broker.scheduler.JobSchedulerStore;
import org.apache.activemq.command.ActiveMQDestination;
import org.apache.activemq.command.ActiveMQQueue;
import org.apache.activemq.command.ActiveMQTopic;
import org.apache.activemq.command.ProducerId;
import org.apache.activemq.store.MessageStore;
import org.apache.activemq.store.PersistenceAdapter;
import org.apache.activemq.store.TopicMessageStore;
import org.apache.activemq.store.TransactionStore;
import org.apache.activemq.usage.SystemUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ActiveMQ {@link PersistenceAdapter} backed by the RAFT consensus protocol.
 *
 * <h2>Overview</h2>
 * <p>This adapter forms a RAFT cluster of ActiveMQ broker nodes. All write
 * operations (message add/remove, subscription changes, transactions) are
 * proposed as entries in the RAFT replicated log. An entry is durably applied
 * to every node's {@link RaftStateMachine} once it has been acknowledged by a
 * quorum (majority) of nodes. Read operations are served from the local
 * committed state and are therefore linearizable from the leader and
 * eventually consistent on followers.
 *
 * <h2>Configuration</h2>
 * <pre>{@code
 * <broker ...>
 *   <persistenceAdapter>
 *     <raftPersistenceAdapter
 *       nodeId="broker1"
 *       port="7150"
 *       peers="broker2:7151,broker3:7152"
 *       directory="${activemq.data}/raft"/>
 *   </persistenceAdapter>
 * </broker>
 * }</pre>
 *
 * <h2>HA model</h2>
 * <p>The RAFT leader is the canonical active broker. All writes are routed to
 * the leader (automatically forwarded if a follower receives a write).
 * {@link #isLeader()} can be used by the broker or monitoring tools to
 * determine whether this node is currently the active master.
 */
public class RaftPersistenceAdapter implements PersistenceAdapter, BrokerServiceAware {

    private static final Logger LOG = LoggerFactory.getLogger(RaftPersistenceAdapter.class);

    // ── Configuration ─────────────────────────────────────────────────────────

    /** Unique ID for this node in the RAFT cluster (defaults to hostname). */
    private String nodeId;

    /** TCP port on which this node's RAFT RPC server listens. */
    private int port = 7150;

    /**
     * Comma-separated list of peer addresses in the form {@code nodeId:host:port}
     * or {@code host:port} (nodeId defaults to host).
     * Example: {@code "broker2:192.168.1.2:7151,broker3:192.168.1.3:7152"}
     */
    private String peers = "";

    /** Directory for RAFT log and metadata files. */
    private File directory = new File("activemq-raft-data");

    // ── Internal components ───────────────────────────────────────────────────

    private RaftNode raftNode;
    private RaftLog raftLog;
    private RaftStateMachine stateMachine;
    private RaftTransactionStore transactionStore;

    private final Map<ActiveMQDestination, MessageStore>      queueStores = new ConcurrentHashMap<>();
    private final Map<ActiveMQDestination, TopicMessageStore> topicStores = new ConcurrentHashMap<>();

    private BrokerService brokerService;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void start() throws Exception {
        if (nodeId == null || nodeId.isEmpty()) {
            try {
                nodeId = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
                nodeId = "raft-node-" + port;
            }
        }
        directory.mkdirs();

        stateMachine = new RaftStateMachine();
        raftLog      = new RaftLog(directory);

        List<RaftPeer> peerList = parsePeers(peers);
        raftNode = new RaftNode(nodeId, port, peerList, raftLog, stateMachine);
        raftNode.start();

        transactionStore = new RaftTransactionStore(raftNode, stateMachine);

        LOG.info("RaftPersistenceAdapter started: nodeId='{}', port={}, peers={}",
                nodeId, port, peers);
    }

    @Override
    public void stop() throws Exception {
        if (raftNode != null) {
            raftNode.stop();
        }
        queueStores.clear();
        topicStores.clear();
        LOG.info("RaftPersistenceAdapter stopped: nodeId='{}'", nodeId);
    }

    // ── PersistenceAdapter ────────────────────────────────────────────────────

    @Override
    public Set<ActiveMQDestination> getDestinations() {
        Set<ActiveMQDestination> result = new HashSet<>();
        for (String name : stateMachine.getAllDestinations()) {
            if (name.startsWith("queue://")) {
                result.add(new ActiveMQQueue(name.substring("queue://".length())));
            } else if (name.startsWith("topic://")) {
                result.add(new ActiveMQTopic(name.substring("topic://".length())));
            }
        }
        return result;
    }

    @Override
    public MessageStore createQueueMessageStore(ActiveMQQueue destination) throws IOException {
        return queueStores.computeIfAbsent(destination, dest ->
                new RaftMessageStore((ActiveMQQueue) dest, raftNode, stateMachine, transactionStore));
    }

    @Override
    public TopicMessageStore createTopicMessageStore(ActiveMQTopic destination) throws IOException {
        return topicStores.computeIfAbsent(destination, dest ->
                new RaftTopicMessageStore((ActiveMQTopic) dest, raftNode, stateMachine, transactionStore));
    }

    @Override
    public void removeQueueMessageStore(ActiveMQQueue destination) {
        queueStores.remove(destination);
        String physicalName = destination.getPhysicalName();
        try {
            RaftLogEntry entry = new RaftLogEntry(
                    0, RaftLogEntry.Type.REMOVE_DESTINATION,
                    physicalName, null, null, null, null, null);
            raftNode.propose(entry);
        } catch (IOException e) {
            LOG.warn("Failed to propose REMOVE_DESTINATION for queue '{}'", physicalName, e);
        }
    }

    @Override
    public void removeTopicMessageStore(ActiveMQTopic destination) {
        topicStores.remove(destination);
        String physicalName = destination.getPhysicalName();
        try {
            RaftLogEntry entry = new RaftLogEntry(
                    0, RaftLogEntry.Type.REMOVE_DESTINATION,
                    physicalName, null, null, null, null, null);
            raftNode.propose(entry);
        } catch (IOException e) {
            LOG.warn("Failed to propose REMOVE_DESTINATION for topic '{}'", physicalName, e);
        }
    }

    @Override
    public TransactionStore createTransactionStore() throws IOException {
        return transactionStore;
    }

    @Override
    public void beginTransaction(ConnectionContext context) throws IOException {
        // No-op: operations within the transaction are buffered by RaftTransactionStore
    }

    @Override
    public void commitTransaction(ConnectionContext context) throws IOException {
        // No-op: committed via TransactionStore.commit()
    }

    @Override
    public void rollbackTransaction(ConnectionContext context) throws IOException {
        // No-op: rolled back via TransactionStore.rollback()
    }

    @Override
    public long getLastMessageBrokerSequenceId() throws IOException {
        return raftNode.getCommitIndex();
    }

    @Override
    public void deleteAllMessages() throws IOException {
        for (String dest : stateMachine.getAllDestinations()) {
            RaftLogEntry entry = new RaftLogEntry(
                    0, RaftLogEntry.Type.REMOVE_ALL_MESSAGES,
                    dest, null, null, null, null, null);
            raftNode.propose(entry);
        }
    }

    @Override
    public void setUsageManager(SystemUsage usageManager) {
        // No-op: memory usage is not bounded in this implementation
    }

    @Override
    public void setBrokerName(String brokerName) {
        if (nodeId == null || nodeId.isEmpty()) {
            nodeId = brokerName;
        }
    }

    @Override
    public void setDirectory(File dir) {
        this.directory = dir;
    }

    @Override
    public File getDirectory() {
        return directory;
    }

    @Override
    public void checkpoint(boolean cleanup) throws IOException {
        // The RAFT log serves as the durability mechanism.
        // A full implementation would write a snapshot here.
        LOG.debug("checkpoint(cleanup={}) called on RaftPersistenceAdapter – no-op in this version", cleanup);
    }

    @Override
    public long size() {
        return raftLog != null ? raftLog.fileSize() : 0;
    }

    @Override
    public long getLastProducerSequenceId(ProducerId id) throws IOException {
        // Producer sequence deduplication is not tracked in this implementation
        return -1;
    }

    @Override
    public void allowIOResumption() {
        // No-op
    }

    @Override
    public JobSchedulerStore createJobSchedulerStore() throws IOException, UnsupportedOperationException {
        throw new UnsupportedOperationException(
                "RaftPersistenceAdapter does not provide a JobSchedulerStore. " +
                "Configure a separate scheduler store.");
    }

    // ── BrokerServiceAware ────────────────────────────────────────────────────

    @Override
    public void setBrokerService(BrokerService brokerService) {
        this.brokerService = brokerService;
    }

    // ── Status queries ────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if this node is currently the RAFT leader.
     * The broker can use this to decide whether to accept client connections.
     */
    public boolean isLeader() {
        return raftNode != null && raftNode.isLeader();
    }

    /**
     * Returns the node ID of the current RAFT leader, or {@code null} if unknown.
     */
    public String getLeaderId() {
        return raftNode != null ? raftNode.getLeaderId() : null;
    }

    // ── Bean properties ───────────────────────────────────────────────────────

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getPeers() {
        return peers;
    }

    /**
     * Set the comma-separated list of peer nodes.
     * Format: {@code nodeId:host:port} or {@code host:port} (per peer).
     * Example: {@code "node2:192.168.1.2:7151,node3:192.168.1.3:7152"}
     */
    public void setPeers(String peers) {
        this.peers = peers;
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Parse the comma-separated peers string into a list of {@link RaftPeer} objects.
     *
     * <p>Accepted formats per entry:
     * <ul>
     *   <li>{@code nodeId:host:port} — all three components explicit</li>
     *   <li>{@code host:port} — nodeId defaults to host</li>
     * </ul>
     */
    private static List<RaftPeer> parsePeers(String peersConfig) {
        List<RaftPeer> result = new ArrayList<>();
        if (peersConfig == null || peersConfig.isBlank()) {
            return result;
        }
        for (String token : peersConfig.split(",")) {
            token = token.strip();
            if (token.isEmpty()) {
                continue;
            }
            String[] parts = token.split(":");
            try {
                if (parts.length == 3) {
                    // nodeId:host:port
                    result.add(new RaftPeer(parts[0], parts[1], Integer.parseInt(parts[2])));
                } else if (parts.length == 2) {
                    // host:port (nodeId = host)
                    result.add(new RaftPeer(parts[0], parts[0], Integer.parseInt(parts[1])));
                } else {
                    LOG.warn("Ignoring malformed peer entry: '{}'", token);
                }
            } catch (NumberFormatException e) {
                LOG.warn("Ignoring peer entry with invalid port: '{}'", token);
            }
        }
        return result;
    }

    @Override
    public String toString() {
        return "RaftPersistenceAdapter{nodeId='" + nodeId + "', port=" + port + ", peers='" + peers + "'}";
    }
}
