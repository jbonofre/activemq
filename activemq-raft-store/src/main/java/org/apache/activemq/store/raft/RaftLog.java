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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File-backed persistent RAFT log.
 *
 * <p>Uses two files:
 * <ul>
 *   <li>{@code raft-meta.properties}: stores currentTerm and votedFor (durable RAFT state)</li>
 *   <li>{@code raft-log.bin}: stores log entries as length-prefixed serialized objects</li>
 * </ul>
 *
 * <p>Log entries are 1-based. Index 0 is a sentinel placeholder (null).
 * All public methods are synchronized for thread safety.
 */
public class RaftLog {

    private static final Logger LOG = LoggerFactory.getLogger(RaftLog.class);

    private final File dataDir;
    private final File logFile;
    private final File metaFile;

    private final List<RaftLogEntry> entries = new ArrayList<>();

    private long currentTerm = 0;
    private String votedFor = null;

    public RaftLog(File dataDir) {
        this.dataDir = dataDir;
        this.logFile = new File(dataDir, "raft-log.bin");
        this.metaFile = new File(dataDir, "raft-meta.properties");
        entries.add(null); // Sentinel at index 0 (RAFT uses 1-based indexing)
    }

    /**
     * Load persisted state from disk. Must be called before any other operation.
     */
    public synchronized void load() throws IOException {
        dataDir.mkdirs();
        loadMeta();
        loadEntries();
        LOG.info("Loaded RAFT log: {} entries, currentTerm={}, votedFor={}",
                entries.size() - 1, currentTerm, votedFor);
    }

    private void loadMeta() throws IOException {
        if (!metaFile.exists()) {
            return;
        }
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(metaFile)) {
            props.load(fis);
        }
        currentTerm = Long.parseLong(props.getProperty("currentTerm", "0"));
        String vf = props.getProperty("votedFor", "");
        votedFor = vf.isEmpty() ? null : vf;
    }

    private void loadEntries() throws IOException {
        entries.clear();
        entries.add(null); // Re-add sentinel

        if (!logFile.exists()) {
            return;
        }

        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new FileInputStream(logFile)))) {
            while (true) {
                try {
                    int len = dis.readInt();
                    if (len <= 0) {
                        break;
                    }
                    byte[] bytes = new byte[len];
                    dis.readFully(bytes);
                    try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
                        RaftLogEntry entry = (RaftLogEntry) ois.readObject();
                        entries.add(entry);
                    } catch (ClassNotFoundException e) {
                        throw new IOException("Failed to deserialize log entry", e);
                    }
                } catch (EOFException e) {
                    break;
                }
            }
        }
        LOG.debug("Loaded {} log entries from disk", entries.size() - 1);
    }

    /**
     * Persist the RAFT durable state: currentTerm and votedFor.
     * Must be called before responding to any RPC that changes these values.
     */
    public synchronized void saveMeta(long term, String votedFor) throws IOException {
        this.currentTerm = term;
        this.votedFor = votedFor;

        Properties props = new Properties();
        props.setProperty("currentTerm", String.valueOf(term));
        props.setProperty("votedFor", votedFor != null ? votedFor : "");

        File tmp = new File(dataDir, "raft-meta.tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            props.store(fos, "RAFT persistent state - do not edit manually");
            fos.flush();
        }
        atomicRename(tmp, metaFile);
    }

    /**
     * Append a new entry to the log. Sets the entry's index to the next available slot.
     */
    public synchronized void append(RaftLogEntry entry) throws IOException {
        long newIndex = entries.size(); // 1-based: next available position
        entry.setIndex(newIndex);
        entries.add(entry);
        appendEntryToFile(entry);
        LOG.debug("Appended log entry at index {}: {}", newIndex, entry);
    }

    /**
     * Truncate the log, removing all entries at and after {@code fromIndex} (1-based).
     * Rewrites the log file.
     */
    public synchronized void truncateFrom(long fromIndex) throws IOException {
        if (fromIndex <= 0 || fromIndex >= entries.size()) {
            return;
        }
        int from = (int) fromIndex;
        LOG.info("Truncating RAFT log from index {} (had {} entries)", fromIndex, entries.size() - 1);
        entries.subList(from, entries.size()).clear();
        rewriteLog();
    }

    /**
     * Returns the entry at the given 1-based index, or {@code null} if the index is out of range.
     */
    public synchronized RaftLogEntry getEntry(long index) {
        if (index <= 0 || index >= entries.size()) {
            return null;
        }
        return entries.get((int) index);
    }

    /**
     * Returns the index of the last log entry (0 if the log is empty).
     */
    public synchronized long getLastIndex() {
        return entries.size() - 1;
    }

    /**
     * Returns the term of the last log entry (0 if the log is empty).
     */
    public synchronized long getLastTerm() {
        if (entries.size() <= 1) {
            return 0;
        }
        return entries.get(entries.size() - 1).getTerm();
    }

    /**
     * Returns all entries with index >= {@code startIndex} (1-based).
     */
    public synchronized List<RaftLogEntry> getEntriesFrom(long startIndex) {
        if (startIndex <= 0 || startIndex >= entries.size()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(entries.subList((int) startIndex, entries.size()));
    }

    /**
     * Returns the total size of the log file in bytes, or 0 if it does not exist.
     */
    public long fileSize() {
        return logFile.exists() ? logFile.length() : 0;
    }

    public synchronized long getCurrentTerm() {
        return currentTerm;
    }

    public synchronized String getVotedFor() {
        return votedFor;
    }

    private void appendEntryToFile(RaftLogEntry entry) throws IOException {
        byte[] bytes = serialize(entry);
        try (DataOutputStream dos = new DataOutputStream(
                new FileOutputStream(logFile, true))) {
            dos.writeInt(bytes.length);
            dos.write(bytes);
            dos.flush();
        }
    }

    private void rewriteLog() throws IOException {
        File tmp = new File(dataDir, "raft-log.tmp");
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(tmp)))) {
            for (int i = 1; i < entries.size(); i++) {
                byte[] bytes = serialize(entries.get(i));
                dos.writeInt(bytes.length);
                dos.write(bytes);
            }
            dos.flush();
        }
        atomicRename(tmp, logFile);
    }

    private static byte[] serialize(RaftLogEntry entry) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(entry);
        }
        return baos.toByteArray();
    }

    private static void atomicRename(File src, File dst) throws IOException {
        if (!src.renameTo(dst)) {
            dst.delete();
            if (!src.renameTo(dst)) {
                throw new IOException("Failed to rename " + src + " to " + dst);
            }
        }
    }
}
