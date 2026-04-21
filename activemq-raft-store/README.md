# ActiveMQ RAFT Store

A persistence adapter for Apache ActiveMQ that uses the [RAFT consensus algorithm](https://raft.github.io/) (Ongaro & Ousterhout, 2014) to provide distributed, fault-tolerant message storage across a cluster of broker nodes.

## Overview

Traditional ActiveMQ persistence adapters (KahaDB, JDBC) are single-node stores. The RAFT adapter replicates every write operation to a quorum of nodes before acknowledging it, so the cluster continues to operate correctly as long as a majority of nodes remain available.

```
┌────────────────────────────────────────────────┐
│              ActiveMQ Broker                   │
│                                                │
│  PersistenceAdapter                            │
│  └── RaftPersistenceAdapter                   │
│       ├── RaftNode  (consensus engine)         │
│       │    ├── RaftLog  (durable log)          │
│       │    └── RaftStateMachine  (state)       │
│       ├── RaftMessageStore  (queues)           │
│       ├── RaftTopicMessageStore  (topics)      │
│       └── RaftTransactionStore  (XA)           │
└────────────────────────────────────────────────┘
         │ AppendEntries / RequestVote RPCs
    ┌────┴────┐           ┌─────────┐
    │ node 2  │           │ node 3  │
    └─────────┘           └─────────┘
```

### HA model

| Node role    | Behaviour |
|--------------|-----------|
| **Leader**   | Accepts all writes; replicates to followers; the active broker |
| **Follower** | Serves reads from local committed state; forwards writes to leader |
| **Candidate**| Transitional state during leader election |

`RaftPersistenceAdapter.isLeader()` returns `true` on the current leader node, allowing the broker (or an external load balancer) to route client connections to the active master.

## Getting started

### Maven dependency

```xml
<dependency>
  <groupId>org.apache.activemq</groupId>
  <artifactId>activemq-raft-store</artifactId>
  <version>${activemq.version}</version>
</dependency>
```

### Broker configuration

Configure the adapter in your `activemq.xml` (three-node example):

**Node 1 (`broker1`)**
```xml
<broker brokerName="broker1" ...>
  <persistenceAdapter>
    <bean xmlns="http://www.springframework.org/schema/beans"
          class="org.apache.activemq.store.raft.RaftPersistenceAdapter">
      <property name="nodeId"    value="broker1"/>
      <property name="port"      value="7150"/>
      <property name="peers"     value="broker2:192.168.1.2:7151,broker3:192.168.1.3:7152"/>
      <property name="directory" value="${activemq.data}/raft"/>
    </bean>
  </persistenceAdapter>
</broker>
```

**Node 2 (`broker2`)**
```xml
<bean class="org.apache.activemq.store.raft.RaftPersistenceAdapter">
  <property name="nodeId"    value="broker2"/>
  <property name="port"      value="7151"/>
  <property name="peers"     value="broker1:192.168.1.1:7150,broker3:192.168.1.3:7152"/>
  <property name="directory" value="${activemq.data}/raft"/>
</bean>
```

**Node 3 (`broker3`)**
```xml
<bean class="org.apache.activemq.store.raft.RaftPersistenceAdapter">
  <property name="nodeId"    value="broker3"/>
  <property name="port"      value="7152"/>
  <property name="peers"     value="broker1:192.168.1.1:7150,broker2:192.168.1.2:7151"/>
  <property name="directory" value="${activemq.data}/raft"/>
</bean>
```

### Configuration properties

| Property    | Default      | Description |
|-------------|--------------|-------------|
| `nodeId`    | hostname     | Unique identifier for this node in the cluster |
| `port`      | `7150`       | TCP port for RAFT inter-node communication |
| `peers`     | *(empty)*    | Comma-separated peer list: `nodeId:host:port,...` |
| `directory` | `activemq-raft-data` | Directory for log and metadata files |

#### Peer address format

Each entry in `peers` may take one of two forms:

```
nodeId:host:port     # all three components explicit
host:port            # nodeId defaults to host
```

Example: `"broker2:10.0.0.2:7151,broker3:10.0.0.3:7152"`

## How it works

### Leader election

Each node starts as a **follower** with a randomised election timeout (150–300 ms). If a follower does not hear from a leader within its timeout, it becomes a **candidate**, increments its term, and sends `RequestVote` RPCs to all peers. The first candidate to collect votes from a majority becomes the **leader** for that term.

### Log replication

Every write (message add/remove, subscription change, transaction operation) is appended to the leader's local `RaftLog` as a `RaftLogEntry`. The leader then sends `AppendEntries` RPCs to all followers. Once a quorum acknowledges the entry, the leader advances `commitIndex` and applies the entry to the `RaftStateMachine`. Followers apply entries when their own `commitIndex` is updated via subsequent `AppendEntries`.

### Follower writes

If a client write reaches a follower node, the follower forwards it to the known leader via a `ForwardProposal` RPC and blocks until the leader confirms the entry has been committed.

### Persistence

Each node stores two files in its `directory`:

| File | Contents |
|------|----------|
| `raft-meta.properties` | Durable state: `currentTerm` and `votedFor` |
| `raft-log.bin` | Binary log: length-prefixed serialized `RaftLogEntry` objects |

On restart, a node reloads its persisted log and state, then rejoins the cluster as a follower. Any entries committed during the outage are delivered by the current leader via `AppendEntries`.

### XA transactions

Prepared XA transactions are recorded in the RAFT log via `PREPARE_TX` entries. On commit, buffered operations are flushed through the log atomically. On broker restart, `RaftTransactionStore.recover()` reports prepared-but-uncommitted transactions to the broker's recovery path.

## Module structure

```
activemq-raft-store/
└── src/main/java/org/apache/activemq/store/raft/
    ├── RaftLogEntry.java          Serializable log entry (operation + payload)
    ├── RaftLog.java               File-backed persistent replicated log
    ├── RaftProtocol.java          RPC message types (AppendEntries, RequestVote, …)
    ├── RaftPeer.java              Outbound RPC client for a single remote node
    ├── RaftNode.java              Core RAFT engine (election, replication, commit)
    ├── RaftStateMachine.java      In-memory committed state; OpenWire serialization
    ├── RaftPersistenceAdapter.java  PersistenceAdapter entry point
    ├── RaftMessageStore.java      MessageStore implementation for queues
    ├── RaftTopicMessageStore.java TopicMessageStore for durable topics
    └── RaftTransactionStore.java  TransactionStore for XA recovery
```

## Cluster sizing

RAFT requires a strict majority of nodes to be available:

| Cluster size | Tolerated failures |
|-------------:|-----------------:|
| 1            | 0 (no HA)        |
| 3            | 1                |
| 5            | 2                |

A **three-node cluster** is the recommended minimum for production use.

## Known limitations

- **No log compaction / snapshots**: The RAFT log grows unbounded. A future release will add snapshot support to bound disk usage and accelerate new-node catch-up.
- **In-memory state machine**: All committed messages are held in memory. For large message volumes, heap sizing must be planned accordingly.
- **No `JobSchedulerStore`**: The RAFT adapter does not provide a distributed scheduler store; configure a separate `JobSchedulerStore` if the broker's scheduler is required.
- **Short-lived RPC connections**: Each RAFT RPC opens a new TCP connection. Connection pooling is a future optimisation.
