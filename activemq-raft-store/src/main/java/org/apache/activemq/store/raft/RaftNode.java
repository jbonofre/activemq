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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core RAFT consensus node implementing the Raft algorithm (Ongaro & Ousterhout, 2014).
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Leader election via randomized election timeouts and RequestVote RPCs</li>
 *   <li>Log replication via AppendEntries RPCs to all peers</li>
 *   <li>Committing entries once a quorum acknowledges them</li>
 *   <li>Applying committed entries to the {@link RaftStateMachine}</li>
 *   <li>Forwarding client writes from followers to the current leader</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * All RAFT state is guarded by {@code lock}. The scheduler runs election timeouts
 * and heartbeats; the RPC executor handles incoming and outgoing connections.
 */
public class RaftNode {

    private static final Logger LOG = LoggerFactory.getLogger(RaftNode.class);

    // ── Timing constants ────────────────────────────────────────────────────

    private static final int ELECTION_TIMEOUT_MIN_MS  = 150;
    private static final int ELECTION_TIMEOUT_MAX_MS  = 300;
    private static final int HEARTBEAT_INTERVAL_MS    = 50;
    private static final int PROPOSAL_TIMEOUT_MS      = 10_000;

    // ── Role ────────────────────────────────────────────────────────────────

    private enum Role { FOLLOWER, CANDIDATE, LEADER }

    // ── Identity and configuration ──────────────────────────────────────────

    private final String nodeId;
    private final int port;
    private final List<RaftPeer> peers;
    private final RaftLog log;
    private final RaftStateMachine stateMachine;

    // ── Persistent state (saved to disk before responding to RPCs) ──────────

    private long currentTerm;   // latest term seen
    private String votedFor;    // candidateId that received vote in currentTerm (null = none)

    // ── Volatile state ───────────────────────────────────────────────────────

    private long commitIndex = 0;    // highest log index known to be committed
    private long lastApplied = 0;    // highest log index applied to state machine
    private Role role = Role.FOLLOWER;
    private String leaderId = null;  // known leader (null if unknown)

    // ── Leader-only state (only valid when role == LEADER) ───────────────────

    private final Map<String, Long> nextIndex  = new ConcurrentHashMap<>();
    private final Map<String, Long> matchIndex = new ConcurrentHashMap<>();

    // ── Proposal tracking ────────────────────────────────────────────────────

    /** Maps log index → future completed when that entry is committed. */
    private final Map<Long, CompletableFuture<Void>> pendingProposals = new ConcurrentHashMap<>();

    // ── Threading ────────────────────────────────────────────────────────────

    private final ReentrantLock lock = new ReentrantLock();
    private final ScheduledExecutorService scheduler;
    private final ExecutorService rpcExecutor;

    private ScheduledFuture<?> electionFuture;
    private ScheduledFuture<?> heartbeatFuture;
    private ServerSocket serverSocket;
    private volatile boolean running = false;

    // ────────────────────────────────────────────────────────────────────────

    public RaftNode(String nodeId, int port, List<RaftPeer> peers,
                    RaftLog log, RaftStateMachine stateMachine) {
        this.nodeId = nodeId;
        this.port = port;
        this.peers = Collections.unmodifiableList(new ArrayList<>(peers));
        this.log = log;
        this.stateMachine = stateMachine;

        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "raft-scheduler-" + nodeId);
            t.setDaemon(true);
            return t;
        });
        this.rpcExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "raft-rpc-" + nodeId);
            t.setDaemon(true);
            return t;
        });
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    public void start() throws IOException {
        log.load();
        lock.lock();
        try {
            currentTerm = log.getCurrentTerm();
            votedFor    = log.getVotedFor();
        } finally {
            lock.unlock();
        }

        running = true;
        serverSocket = new ServerSocket(port);
        rpcExecutor.submit(this::acceptLoop);
        resetElectionTimer();

        LOG.info("RAFT node '{}' started on port {} (term={}, votedFor={})",
                nodeId, port, currentTerm, votedFor);
    }

    public void stop() {
        running = false;

        lock.lock();
        try {
            cancelElectionTimer();
            cancelHeartbeatTimer();
        } finally {
            lock.unlock();
        }

        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            LOG.warn("Error closing server socket on node '{}'", nodeId, e);
        }

        scheduler.shutdownNow();
        rpcExecutor.shutdownNow();

        // Fail all in-flight proposals
        for (CompletableFuture<Void> f : pendingProposals.values()) {
            f.completeExceptionally(new IOException("RAFT node '" + nodeId + "' stopped"));
        }
        pendingProposals.clear();

        LOG.info("RAFT node '{}' stopped", nodeId);
    }

    // ── Server: accept incoming connections ──────────────────────────────────

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                rpcExecutor.submit(() -> handleConnection(socket));
            } catch (IOException e) {
                if (running) {
                    LOG.warn("Error accepting connection on node '{}'", nodeId, e);
                }
            }
        }
    }

    private void handleConnection(Socket socket) {
        try (socket) {
            // Flush OOS header before creating OIS to avoid header-read deadlock
            ObjectOutputStream out =
                    new ObjectOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            out.flush();
            ObjectInputStream in =
                    new ObjectInputStream(new BufferedInputStream(socket.getInputStream()));

            RaftProtocol.RpcMessage request = (RaftProtocol.RpcMessage) in.readObject();
            RaftProtocol.RpcMessage response = dispatchRpc(request);
            if (response != null) {
                out.writeObject(response);
                out.flush();
            }
        } catch (Exception e) {
            LOG.trace("RPC connection error on node '{}'", nodeId, e);
        }
    }

    private RaftProtocol.RpcMessage dispatchRpc(RaftProtocol.RpcMessage request) {
        return switch (request.type) {
            case APPEND_ENTRIES -> new RaftProtocol.RpcMessage(
                    RaftProtocol.MessageType.APPEND_ENTRIES_RESP,
                    handleAppendEntries((RaftProtocol.AppendEntriesRequest) request.payload));
            case REQUEST_VOTE -> new RaftProtocol.RpcMessage(
                    RaftProtocol.MessageType.REQUEST_VOTE_RESP,
                    handleRequestVote((RaftProtocol.RequestVoteRequest) request.payload));
            case FORWARD_PROPOSAL -> new RaftProtocol.RpcMessage(
                    RaftProtocol.MessageType.FORWARD_RESP,
                    handleForwardProposal((RaftProtocol.ForwardProposal) request.payload));
            default -> {
                LOG.warn("Unknown RPC type {} on node '{}'", request.type, nodeId);
                yield null;
            }
        };
    }

    // ── Election timer management ────────────────────────────────────────────

    private void resetElectionTimer() {
        lock.lock();
        try {
            cancelElectionTimer();
            int timeout = ELECTION_TIMEOUT_MIN_MS
                    + ThreadLocalRandom.current().nextInt(
                            ELECTION_TIMEOUT_MAX_MS - ELECTION_TIMEOUT_MIN_MS);
            electionFuture = scheduler.schedule(this::startElectionSafe, timeout, TimeUnit.MILLISECONDS);
        } finally {
            lock.unlock();
        }
    }

    private void cancelElectionTimer() {
        if (electionFuture != null) {
            electionFuture.cancel(false);
            electionFuture = null;
        }
    }

    private void cancelHeartbeatTimer() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
    }

    // ── Leader election ──────────────────────────────────────────────────────

    private void startElectionSafe() {
        try {
            startElection();
        } catch (Exception e) {
            LOG.error("Unexpected error during election on node '{}'", nodeId, e);
        }
    }

    private void startElection() throws IOException {
        long termForElection;
        long lastLogIndex;
        long lastLogTerm;

        lock.lock();
        try {
            currentTerm++;
            role      = Role.CANDIDATE;
            votedFor  = nodeId;
            leaderId  = null;
            saveMeta();
            termForElection = currentTerm;
            lastLogIndex    = log.getLastIndex();
            lastLogTerm     = log.getLastTerm();
            resetElectionTimer(); // Restart timer in case this election fails
        } finally {
            lock.unlock();
        }

        LOG.info("Node '{}' starting election for term {}", nodeId, termForElection);

        RaftProtocol.RequestVoteRequest req =
                new RaftProtocol.RequestVoteRequest(termForElection, nodeId, lastLogIndex, lastLogTerm);

        // Send RequestVote RPCs concurrently (using the RPC executor)
        List<RaftProtocol.RequestVoteResponse> responses = new ArrayList<>();
        List<CompletableFuture<RaftProtocol.RequestVoteResponse>> futures = new ArrayList<>();

        for (RaftPeer peer : peers) {
            CompletableFuture<RaftProtocol.RequestVoteResponse> f =
                    CompletableFuture.supplyAsync(() -> {
                        try {
                            return peer.sendRequestVote(req);
                        } catch (IOException e) {
                            LOG.debug("Vote request to {} failed: {}", peer, e.getMessage());
                            return new RaftProtocol.RequestVoteResponse(0, false);
                        }
                    }, rpcExecutor);
            futures.add(f);
        }

        // Gather results (with individual timeouts already baked into RaftPeer)
        for (CompletableFuture<RaftProtocol.RequestVoteResponse> f : futures) {
            try {
                responses.add(f.get(ELECTION_TIMEOUT_MAX_MS, TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                responses.add(new RaftProtocol.RequestVoteResponse(0, false));
            }
        }

        lock.lock();
        try {
            // Abort if term or role has changed while we were waiting for votes
            if (role != Role.CANDIDATE || currentTerm != termForElection) {
                return;
            }

            // Check for higher term – immediately revert to follower
            for (RaftProtocol.RequestVoteResponse resp : responses) {
                if (resp.term > currentTerm) {
                    becomeFollower(resp.term);
                    return;
                }
            }

            // Count votes: 1 (self) + granted votes from peers
            int votes = 1;
            for (RaftProtocol.RequestVoteResponse resp : responses) {
                if (resp.voteGranted) {
                    votes++;
                }
            }

            int quorum = quorum();
            LOG.info("Node '{}' received {}/{} votes in term {} (quorum={})",
                    nodeId, votes, clusterSize(), termForElection, quorum);

            if (votes >= quorum) {
                becomeLeader();
            } else {
                // Didn't win – stay candidate; the election timer will fire again
                LOG.info("Node '{}' did not win election for term {}", nodeId, termForElection);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Must be called while holding {@code lock}. */
    private void becomeLeader() {
        role     = Role.LEADER;
        leaderId = nodeId;
        cancelElectionTimer();

        long lastIdx = log.getLastIndex();
        nextIndex.clear();
        matchIndex.clear();
        for (RaftPeer peer : peers) {
            nextIndex.put(peer.getNodeId(), lastIdx + 1);
            matchIndex.put(peer.getNodeId(), 0L);
        }

        LOG.info("Node '{}' became LEADER for term {}", nodeId, currentTerm);

        // Start periodic heartbeat (also carries pending log entries)
        heartbeatFuture = scheduler.scheduleAtFixedRate(
                this::sendHeartbeatsSafe,
                0, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** Must be called while holding {@code lock}. */
    private void becomeFollower(long newTerm) {
        boolean wasLeader = role == Role.LEADER;
        role        = Role.FOLLOWER;
        currentTerm = newTerm;
        votedFor    = null;
        saveMeta();

        if (wasLeader) {
            cancelHeartbeatTimer();
            // Fail all in-flight proposals – we lost leadership
            for (CompletableFuture<Void> f : pendingProposals.values()) {
                f.completeExceptionally(new IOException("Node '" + nodeId + "' lost leadership"));
            }
            pendingProposals.clear();
        }

        resetElectionTimer();
        LOG.info("Node '{}' became FOLLOWER for term {}", nodeId, newTerm);
    }

    // ── Heartbeat / log replication ──────────────────────────────────────────

    private void sendHeartbeatsSafe() {
        try {
            lock.lock();
            try {
                if (role != Role.LEADER) {
                    return;
                }
            } finally {
                lock.unlock();
            }
            for (RaftPeer peer : peers) {
                rpcExecutor.submit(() -> replicateToPeer(peer));
            }
        } catch (Exception e) {
            LOG.error("Error sending heartbeats on node '{}'", nodeId, e);
        }
    }

    private void replicateToPeer(RaftPeer peer) {
        long term, ni, prevLogIndex, prevLogTerm, leaderCommit;
        List<RaftLogEntry> entries;

        lock.lock();
        try {
            if (role != Role.LEADER) {
                return;
            }
            term    = currentTerm;
            ni      = nextIndex.getOrDefault(peer.getNodeId(), 1L);
            prevLogIndex = ni - 1;
            RaftLogEntry prevEntry = log.getEntry(prevLogIndex);
            prevLogTerm  = prevEntry != null ? prevEntry.getTerm() : 0;
            entries      = log.getEntriesFrom(ni);
            leaderCommit = commitIndex;
        } finally {
            lock.unlock();
        }

        RaftProtocol.AppendEntriesRequest req = new RaftProtocol.AppendEntriesRequest(
                term, nodeId, prevLogIndex, prevLogTerm, entries, leaderCommit);

        RaftProtocol.AppendEntriesResponse resp;
        try {
            resp = peer.sendAppendEntries(req);
        } catch (IOException e) {
            LOG.debug("AppendEntries to '{}' failed: {}", peer, e.getMessage());
            return;
        }

        lock.lock();
        try {
            if (role != Role.LEADER) {
                return;
            }
            if (resp.term > currentTerm) {
                becomeFollower(resp.term);
                return;
            }
            if (resp.success) {
                long newMatch = resp.matchIndex;
                matchIndex.put(peer.getNodeId(), newMatch);
                nextIndex.put(peer.getNodeId(), newMatch + 1);
                advanceCommitIndex();
            } else {
                // Log inconsistency – back up nextIndex and retry on next heartbeat
                long ni2 = nextIndex.getOrDefault(peer.getNodeId(), 1L);
                if (ni2 > 1) {
                    nextIndex.put(peer.getNodeId(), ni2 - 1);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Advance {@code commitIndex} to the highest N such that:
     * <ul>
     *   <li>N &gt; commitIndex</li>
     *   <li>log[N].term == currentTerm</li>
     *   <li>A majority of nodes have matchIndex &ge; N</li>
     * </ul>
     * Must be called while holding {@code lock}.
     */
    private void advanceCommitIndex() {
        long lastIdx = log.getLastIndex();
        for (long n = commitIndex + 1; n <= lastIdx; n++) {
            RaftLogEntry entry = log.getEntry(n);
            if (entry == null || entry.getTerm() != currentTerm) {
                continue;
            }
            // Count servers that have replicated this entry (leader counts itself)
            int count = 1;
            for (RaftPeer peer : peers) {
                if (matchIndex.getOrDefault(peer.getNodeId(), 0L) >= n) {
                    count++;
                }
            }
            if (count >= quorum()) {
                commitIndex = n;
                LOG.debug("Node '{}' committed log index {}", nodeId, n);
            }
        }
        applyCommitted();
    }

    /**
     * Apply all committed-but-not-yet-applied log entries to the state machine.
     * Completes any pending proposal futures.
     * Must be called while holding {@code lock}.
     */
    private void applyCommitted() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            RaftLogEntry entry = log.getEntry(lastApplied);
            if (entry != null) {
                try {
                    stateMachine.apply(entry);
                } catch (IOException e) {
                    LOG.error("Failed to apply log entry {} on node '{}'", lastApplied, nodeId, e);
                }
            }
            CompletableFuture<Void> fut = pendingProposals.remove(lastApplied);
            if (fut != null) {
                fut.complete(null);
            }
        }
    }

    // ── RPC handlers ─────────────────────────────────────────────────────────

    private RaftProtocol.AppendEntriesResponse handleAppendEntries(
            RaftProtocol.AppendEntriesRequest req) {
        lock.lock();
        try {
            // § 5.1: Reply false if term < currentTerm
            if (req.term < currentTerm) {
                return new RaftProtocol.AppendEntriesResponse(currentTerm, false, 0);
            }

            // Any AppendEntries with term >= currentTerm converts us to follower
            if (req.term > currentTerm) {
                currentTerm = req.term;
                votedFor    = null;
                saveMeta();
            }
            if (role != Role.FOLLOWER) {
                cancelHeartbeatTimer();
                role = Role.FOLLOWER;
            }
            leaderId = req.leaderId;
            resetElectionTimer();

            // § 5.3: Reply false if log does not contain entry at prevLogIndex with prevLogTerm
            if (req.prevLogIndex > 0) {
                RaftLogEntry prev = log.getEntry(req.prevLogIndex);
                if (prev == null || prev.getTerm() != req.prevLogTerm) {
                    return new RaftProtocol.AppendEntriesResponse(currentTerm, false, 0);
                }
            }

            // § 5.3: If existing entry conflicts with new one, delete it and all following
            if (req.entries != null && !req.entries.isEmpty()) {
                for (int i = 0; i < req.entries.size(); i++) {
                    RaftLogEntry newEntry = req.entries.get(i);
                    long idx = req.prevLogIndex + 1 + i;
                    RaftLogEntry existing = log.getEntry(idx);
                    if (existing != null && existing.getTerm() != newEntry.getTerm()) {
                        try {
                            log.truncateFrom(idx);
                        } catch (IOException e) {
                            LOG.error("Failed to truncate log on node '{}'", nodeId, e);
                            return new RaftProtocol.AppendEntriesResponse(currentTerm, false, 0);
                        }
                        break;
                    }
                }

                // Append any entries not already in the log
                for (int i = 0; i < req.entries.size(); i++) {
                    RaftLogEntry newEntry = req.entries.get(i);
                    long idx = req.prevLogIndex + 1 + i;
                    if (log.getEntry(idx) == null) {
                        newEntry.setIndex(idx);
                        try {
                            log.append(newEntry);
                        } catch (IOException e) {
                            LOG.error("Failed to append log entry on node '{}'", nodeId, e);
                            return new RaftProtocol.AppendEntriesResponse(currentTerm, false, 0);
                        }
                    }
                }
            }

            // § 5.3: Update commitIndex
            if (req.leaderCommit > commitIndex) {
                commitIndex = Math.min(req.leaderCommit, log.getLastIndex());
                applyCommitted();
            }

            return new RaftProtocol.AppendEntriesResponse(currentTerm, true, log.getLastIndex());

        } finally {
            lock.unlock();
        }
    }

    private RaftProtocol.RequestVoteResponse handleRequestVote(
            RaftProtocol.RequestVoteRequest req) {
        lock.lock();
        try {
            // § 5.1: Reply false if term < currentTerm
            if (req.term < currentTerm) {
                return new RaftProtocol.RequestVoteResponse(currentTerm, false);
            }

            // Update term if candidate is more up-to-date
            if (req.term > currentTerm) {
                currentTerm = req.term;
                votedFor    = null;
                role        = Role.FOLLOWER;
                saveMeta();
                cancelHeartbeatTimer();
                resetElectionTimer();
            }

            // § 5.2 & § 5.4: Grant vote only if we haven't voted and candidate log is up-to-date
            boolean canVote = (votedFor == null || votedFor.equals(req.candidateId));
            boolean logOk   = isCandidateLogUpToDate(req.lastLogTerm, req.lastLogIndex);

            if (canVote && logOk) {
                votedFor = req.candidateId;
                saveMeta();
                resetElectionTimer();
                LOG.info("Node '{}' granted vote to '{}' for term {}", nodeId, req.candidateId, req.term);
                return new RaftProtocol.RequestVoteResponse(currentTerm, true);
            }

            return new RaftProtocol.RequestVoteResponse(currentTerm, false);

        } finally {
            lock.unlock();
        }
    }

    private RaftProtocol.ForwardResponse handleForwardProposal(
            RaftProtocol.ForwardProposal proposal) {
        lock.lock();
        boolean isLeader = role == Role.LEADER;
        lock.unlock();

        if (!isLeader) {
            return new RaftProtocol.ForwardResponse(false, "Not leader");
        }
        try {
            proposeAsLeader(proposal.entry);
            return new RaftProtocol.ForwardResponse(true, null);
        } catch (IOException e) {
            return new RaftProtocol.ForwardResponse(false, e.getMessage());
        }
    }

    // ── Public proposal API ───────────────────────────────────────────────────

    /**
     * Proposes a new log entry and blocks until it is committed to the cluster,
     * or throws {@link IOException} if the proposal fails or times out.
     *
     * <p>If this node is the leader, it appends the entry directly.
     * If this node is a follower with a known leader, it forwards the entry.
     * If no leader is known, it waits briefly for a leader to be elected.
     */
    public void propose(RaftLogEntry entry) throws IOException {
        for (int attempt = 0; attempt < 3; attempt++) {
            String leader;
            boolean amLeader;
            lock.lock();
            try {
                amLeader = role == Role.LEADER;
                leader   = leaderId;
            } finally {
                lock.unlock();
            }

            if (amLeader) {
                proposeAsLeader(entry);
                return;
            }
            if (leader != null) {
                forwardToLeader(entry, leader);
                return;
            }
            // No known leader – wait a bit and retry
            waitForLeaderOrTimeout(ELECTION_TIMEOUT_MAX_MS);
        }
        throw new IOException("No RAFT leader available after retries");
    }

    private void proposeAsLeader(RaftLogEntry entry) throws IOException {
        CompletableFuture<Void> future = new CompletableFuture<>();

        lock.lock();
        try {
            if (role != Role.LEADER) {
                throw new IOException("Node '" + nodeId + "' is no longer the RAFT leader");
            }
            entry.setTerm(currentTerm);
            log.append(entry);
            pendingProposals.put(entry.getIndex(), future);
            // Immediately try to advance commitIndex in case quorum is already satisfied
            // (e.g., single-node cluster where the leader is the only voter).
            advanceCommitIndex();
        } finally {
            lock.unlock();
        }

        // Eagerly trigger replication to peers (no-op if peers list is empty)
        for (RaftPeer peer : peers) {
            rpcExecutor.submit(() -> replicateToPeer(peer));
        }

        try {
            future.get(PROPOSAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pendingProposals.remove(entry.getIndex());
            throw new IOException("RAFT proposal timed out for entry " + entry, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pendingProposals.remove(entry.getIndex());
            throw new IOException("Interrupted waiting for RAFT proposal " + entry, e);
        } catch (ExecutionException e) {
            throw new IOException("RAFT proposal failed for entry " + entry, e.getCause());
        }
    }

    private void forwardToLeader(RaftLogEntry entry, String leaderId) throws IOException {
        RaftPeer leaderPeer = findPeer(leaderId);
        if (leaderPeer == null) {
            throw new IOException("Leader peer '" + leaderId + "' not found in cluster configuration");
        }
        RaftProtocol.ForwardResponse resp =
                leaderPeer.sendForwardProposal(new RaftProtocol.ForwardProposal(entry));
        if (!resp.success) {
            throw new IOException("Leader rejected forwarded proposal: " + resp.errorMessage);
        }
    }

    private void waitForLeaderOrTimeout(long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            lock.lock();
            try {
                if (role == Role.LEADER || leaderId != null) {
                    return;
                }
            } finally {
                lock.unlock();
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for RAFT leader");
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns true if the candidate's log is at least as up-to-date as ours (§ 5.4).
     * Must be called while holding {@code lock}.
     */
    private boolean isCandidateLogUpToDate(long candidateLastTerm, long candidateLastIndex) {
        long myLastTerm  = log.getLastTerm();
        long myLastIndex = log.getLastIndex();
        if (candidateLastTerm != myLastTerm) {
            return candidateLastTerm > myLastTerm;
        }
        return candidateLastIndex >= myLastIndex;
    }

    private RaftPeer findPeer(String id) {
        for (RaftPeer peer : peers) {
            if (peer.getNodeId().equals(id)) {
                return peer;
            }
        }
        return null;
    }

    /** Quorum = majority of the cluster (including this node). */
    private int quorum() {
        return (clusterSize() / 2) + 1;
    }

    private int clusterSize() {
        return peers.size() + 1; // peers + self
    }

    /**
     * Persist durable state. Must be called while holding {@code lock}
     * before responding to any RPC that changes term or votedFor.
     * Wraps IOException as unchecked to keep lock blocks tidy.
     */
    private void saveMeta() {
        try {
            log.saveMeta(currentTerm, votedFor);
        } catch (IOException e) {
            LOG.error("CRITICAL: Failed to persist RAFT state on node '{}' – node may be unsafe", nodeId, e);
        }
    }

    // ── Status queries ────────────────────────────────────────────────────────

    public boolean isLeader() {
        return role == Role.LEADER;
    }

    public String getLeaderId() {
        return leaderId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public long getCommitIndex() {
        return commitIndex;
    }

    public long getCurrentTerm() {
        lock.lock();
        try {
            return currentTerm;
        } finally {
            lock.unlock();
        }
    }
}
