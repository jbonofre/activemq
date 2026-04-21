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
import java.net.InetSocketAddress;
import java.net.Socket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents a remote RAFT cluster peer and handles outbound RPC communication.
 *
 * <p>Each RPC call uses a fresh short-lived TCP connection for simplicity and
 * to avoid half-open connection issues during node failures. Both sides flush
 * their {@link ObjectOutputStream} header before reading to prevent deadlocks.
 */
public class RaftPeer {

    private static final Logger LOG = LoggerFactory.getLogger(RaftPeer.class);

    private static final int CONNECT_TIMEOUT_MS = 200;
    private static final int READ_TIMEOUT_MS = 500;

    private final String nodeId;
    private final String host;
    private final int port;

    public RaftPeer(String nodeId, String host, int port) {
        this.nodeId = nodeId;
        this.host = host;
        this.port = port;
    }

    /**
     * Send an AppendEntries RPC to this peer and return the response.
     *
     * @throws IOException if the peer is unreachable or returns an unexpected response
     */
    public RaftProtocol.AppendEntriesResponse sendAppendEntries(
            RaftProtocol.AppendEntriesRequest request) throws IOException {
        RaftProtocol.RpcMessage resp = sendRpc(
                new RaftProtocol.RpcMessage(RaftProtocol.MessageType.APPEND_ENTRIES, request));
        return (RaftProtocol.AppendEntriesResponse) resp.payload;
    }

    /**
     * Send a RequestVote RPC to this peer and return the response.
     *
     * @throws IOException if the peer is unreachable or returns an unexpected response
     */
    public RaftProtocol.RequestVoteResponse sendRequestVote(
            RaftProtocol.RequestVoteRequest request) throws IOException {
        RaftProtocol.RpcMessage resp = sendRpc(
                new RaftProtocol.RpcMessage(RaftProtocol.MessageType.REQUEST_VOTE, request));
        return (RaftProtocol.RequestVoteResponse) resp.payload;
    }

    /**
     * Forward a client proposal to this peer (which should be the current leader).
     *
     * @throws IOException if the peer is unreachable or the forward fails
     */
    public RaftProtocol.ForwardResponse sendForwardProposal(
            RaftProtocol.ForwardProposal proposal) throws IOException {
        RaftProtocol.RpcMessage resp = sendRpc(
                new RaftProtocol.RpcMessage(RaftProtocol.MessageType.FORWARD_PROPOSAL, proposal));
        return (RaftProtocol.ForwardResponse) resp.payload;
    }

    private RaftProtocol.RpcMessage sendRpc(RaftProtocol.RpcMessage request) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);

            // Write OOS header first, flush, then read OIS header to avoid deadlock.
            // The server side does the same (flush before creating OIS).
            ObjectOutputStream out =
                    new ObjectOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            out.flush(); // Send serialization header to the server

            ObjectInputStream in =
                    new ObjectInputStream(new BufferedInputStream(socket.getInputStream()));

            out.writeObject(request);
            out.flush();

            return (RaftProtocol.RpcMessage) in.readObject();

        } catch (ClassNotFoundException e) {
            throw new IOException("Unexpected RPC response type from peer " + nodeId, e);
        }
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    @Override
    public String toString() {
        return nodeId + "@" + host + ":" + port;
    }
}
