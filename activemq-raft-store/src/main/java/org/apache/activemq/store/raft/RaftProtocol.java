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
import java.util.List;

/**
 * Serializable message types for the RAFT inter-node RPC protocol.
 */
public final class RaftProtocol {

    private RaftProtocol() {}

    public enum MessageType {
        APPEND_ENTRIES,
        APPEND_ENTRIES_RESP,
        REQUEST_VOTE,
        REQUEST_VOTE_RESP,
        FORWARD_PROPOSAL,
        FORWARD_RESP
    }

    /** Envelope wrapping any RPC request or response. */
    public static class RpcMessage implements Serializable {
        private static final long serialVersionUID = 1L;
        public final MessageType type;
        public final Object payload;

        public RpcMessage(MessageType type, Object payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    /**
     * AppendEntries RPC – sent by leader to replicate log entries and as heartbeat.
     */
    public static class AppendEntriesRequest implements Serializable {
        private static final long serialVersionUID = 1L;

        /** Leader's current term */
        public final long term;
        /** Leader's node ID (so followers can redirect clients) */
        public final String leaderId;
        /** Index of log entry immediately preceding the new ones */
        public final long prevLogIndex;
        /** Term of prevLogIndex entry */
        public final long prevLogTerm;
        /** Log entries to store (empty for heartbeat) */
        public final List<RaftLogEntry> entries;
        /** Leader's commit index */
        public final long leaderCommit;

        public AppendEntriesRequest(long term, String leaderId, long prevLogIndex,
                long prevLogTerm, List<RaftLogEntry> entries, long leaderCommit) {
            this.term = term;
            this.leaderId = leaderId;
            this.prevLogIndex = prevLogIndex;
            this.prevLogTerm = prevLogTerm;
            this.entries = entries;
            this.leaderCommit = leaderCommit;
        }
    }

    /**
     * Response to AppendEntries RPC.
     */
    public static class AppendEntriesResponse implements Serializable {
        private static final long serialVersionUID = 1L;

        /** Follower's current term (leader uses this to update itself if stale) */
        public final long term;
        /** True if the follower accepted the entries */
        public final boolean success;
        /** Highest log index replicated on the follower (set when success=true) */
        public final long matchIndex;

        public AppendEntriesResponse(long term, boolean success, long matchIndex) {
            this.term = term;
            this.success = success;
            this.matchIndex = matchIndex;
        }
    }

    /**
     * RequestVote RPC – sent by candidates during leader election.
     */
    public static class RequestVoteRequest implements Serializable {
        private static final long serialVersionUID = 1L;

        /** Candidate's current term */
        public final long term;
        /** Candidate's node ID */
        public final String candidateId;
        /** Index of candidate's last log entry */
        public final long lastLogIndex;
        /** Term of candidate's last log entry */
        public final long lastLogTerm;

        public RequestVoteRequest(long term, String candidateId, long lastLogIndex, long lastLogTerm) {
            this.term = term;
            this.candidateId = candidateId;
            this.lastLogIndex = lastLogIndex;
            this.lastLogTerm = lastLogTerm;
        }
    }

    /**
     * Response to RequestVote RPC.
     */
    public static class RequestVoteResponse implements Serializable {
        private static final long serialVersionUID = 1L;

        /** Voter's current term */
        public final long term;
        /** True if the candidate received the vote */
        public final boolean voteGranted;

        public RequestVoteResponse(long term, boolean voteGranted) {
            this.term = term;
            this.voteGranted = voteGranted;
        }
    }

    /**
     * ForwardProposal – sent by a follower to forward a client write to the current leader.
     */
    public static class ForwardProposal implements Serializable {
        private static final long serialVersionUID = 1L;

        public final RaftLogEntry entry;

        public ForwardProposal(RaftLogEntry entry) {
            this.entry = entry;
        }
    }

    /**
     * Response to a ForwardProposal.
     */
    public static class ForwardResponse implements Serializable {
        private static final long serialVersionUID = 1L;

        public final boolean success;
        public final String errorMessage;

        public ForwardResponse(boolean success, String errorMessage) {
            this.success = success;
            this.errorMessage = errorMessage;
        }
    }
}
