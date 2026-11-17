# Learning guide: every concept in this project

Each concept follows the same nine questions so you can study (and defend) it in an interview. Read the listed classes next to this guide, then run the listed tests.

**Suggested study order:** 1 -> 2 -> 3 -> 4 -> 5 -> 6 -> 7 -> 8 -> 9 -> 10 -> 11 -> 12 -> 13 -> 14. Run `mvn -pl cache-core test` after each to see it work.


## 1. In-memory cache and TTL

- **What it is:** A fast key-value store that keeps data in RAM; TTL (time to live) makes an entry expire automatically.
- **Why we need it:** Databases are slow and expensive to hit repeatedly; expiring data bounds staleness and memory.
- **How it works:** Each entry stores `expiresAt`. A read checks it (lazy expiration); a background task removes expired entries every second (active cleanup).
- **Where it fits in this project:** Every node's local store; the Settings page defines the default TTL.
- **Classes involved:** `CacheStore`, `CacheEntry`, `NodeServer` (cleanup scheduler)
- **How to implement it:** `ConcurrentHashMap.compute()` per operation; `ttlMillis()` returns -2 missing / -1 no expiry / remaining ms.
- **How to test it:** `CacheStoreTest.entryExpiresAfterTtl`, `scheduledCleanupRemovesExpiredEntries`, integration `ttlExpiresOnPrimaryAndReplicas` (uses a fake clock in unit tests).
- **What can go wrong:** Clock skew between nodes, expired entry still visible on a replica until its own check, thundering herd when many keys expire together.
- **How to explain it in an interview:** "I combine lazy and scheduled expiration, like Redis: reads never return expired data and memory is reclaimed in the background."

## 2. Thread safety with ConcurrentHashMap.compute

- **What it is:** Doing a read-modify-write atomically for one key while other keys proceed in parallel.
- **Why we need it:** Version numbers and byte accounting would corrupt under races if we used get-then-put.
- **How it works:** `compute(key, fn)` locks only that key's bin and runs `fn` atomically.
- **Where it fits in this project:** Every write path inside `CacheStore`.
- **Classes involved:** `CacheStore.put/delete/expire/applyReplica`
- **How to implement it:** Compute the new entry from the previous one inside the lambda; update the byte counter in the same lambda.
- **How to test it:** `concurrentWritersNeverCorruptCountersOrLoseKeys` (8 threads, checks exact byte accounting).
- **What can go wrong:** Doing slow work or blocking I/O inside `compute`, or forgetting that the lambda may be retried by other map implementations.
- **How to explain it in an interview:** "Atomic per-key updates without a global lock, so throughput scales with cores."

## 3. Consistent hashing

- **What it is:** Placing nodes and keys on a circle of hash values; a key belongs to the next node clockwise.
- **Why we need it:** `hash(key) % N` reshuffles almost every key when N changes; consistent hashing moves only about 1/N of them.
- **How it works:** MD5 -> 64-bit position; a `TreeMap` of positions; `ceilingEntry` finds the owner; wrap around at the end.
- **Where it fits in this project:** Routing of every request and ownership of data.
- **Classes involved:** `ConsistentHashRing`, `Md5HashFunction`, `CacheRouter`
- **How to implement it:** Add node = add its points; remove node = delete them; `preferenceList(key, n)` walks clockwise collecting distinct nodes.
- **How to test it:** `ConsistentHashRingTest` (stable mapping, only ~1/7 keys move on join, only the leaving node's keys move on leave).
- **What can go wrong:** Hash collisions (handled with a salt), unbalanced load with few points (fixed by virtual nodes), changing the hash function (moves everything).
- **How to explain it in an interview:** "Adding a node moved 14% of the keys in my test, not 85% like modulo hashing."

## 4. Virtual nodes

- **What it is:** Each physical node appears at many points (default 100) on the ring.
- **Why we need it:** With one point per node the arcs are very uneven; many points average out the load.
- **How it works:** Point i of node X sits at `hash("X#i")`.
- **Where it fits in this project:** Ring construction; the Hash Ring page draws them.
- **Classes involved:** `VirtualNode`, `ConsistentHashRing.virtualNodes()/ownership()`
- **How to implement it:** Configurable via Settings; `ownership()` sums arc lengths per node.
- **How to test it:** `virtualNodesSpreadKeysEvenly` (all six nodes between 10% and 25% of 20,000 keys).
- **What can go wrong:** Too few -> skew; too many -> larger ring memory and slower rebuilds.
- **How to explain it in an interview:** "Virtual nodes trade a little memory for much better balance, and let stronger nodes take more points."

## 5. Replication

- **What it is:** Keeping copies of each key on several nodes.
- **Why we need it:** A single copy is lost with its node; replicas give availability and a promotion target.
- **How it works:** Primary stores the write, then the gateway sends `REPL` to the next distinct nodes on the ring (factor 2 = 3 copies).
- **Where it fits in this project:** Every SET/DELETE/EXPIRE.
- **Classes involved:** `ReplicationManager`, `CacheRouter.set`, `CacheStore.applyReplica`
- **How to implement it:** Asynchronous by default (virtual thread per replica write); acks and lag are recorded.
- **How to test it:** `dataIsDistributedAndEveryKeyHasThreeCopies`, `replicaWriteIsAppliedOnlyIfNewer`.
- **What can go wrong:** Lag window, lost acknowledged writes on primary crash, replicas that missed writes while down.
- **How to explain it in an interview:** "Async replication favours latency and availability; I measure the lag and document the loss window."

## 6. Versioning, stale-write prevention and tombstones

- **What it is:** A number per entry that decides which copy is newer; deletes are stored as markers.
- **Why we need it:** Replicas and recovered nodes must never overwrite newer data with old data, nor resurrect deleted keys.
- **How it works:** `version = max(prev+1, now)`; a copy wins if (version, updatedAt, valueHash) is greater; delete writes a tombstone kept 10 min.
- **Where it fits in this project:** `REPL`, reconcile, recovery.
- **Classes involved:** `CacheEntry.isNewerThan`, `CacheStore.delete/applyReplica`
- **How to implement it:** Reject an incoming entry unless it is newer than the stored one.
- **How to test it:** `deleteWritesTombstoneThatBlocksStaleResurrection`, `staleNodeDoesNotOverwriteNewerDataAfterPartitionHeals`, `crashedNodeIsRebuiltFromReplicasAndNewerDataIsNeverOverwritten`.
- **What can go wrong:** Clock skew, tombstone expiry before a very stale node returns, concurrent writers (last writer wins).
- **How to explain it in an interview:** "One total order of versions makes every node converge to the same winner."

## 7. Heartbeat failure detection

- **What it is:** Periodically pinging nodes and reacting when they stop answering.
- **Why we need it:** Failures must be noticed and handled automatically, but one slow reply must not trigger failover.
- **How it works:** Ping every interval; silent >= suspect timeout -> SUSPECTED; silent >= failure timeout -> failover candidate.
- **Where it fits in this project:** Cluster health; the router tries suspected nodes last.
- **Classes involved:** `HeartbeatService`, `NodeInfo`, `NodeState`
- **How to implement it:** Parallel pings with hard timeouts; atomic state transitions.
- **How to test it:** `singleMissedHeartbeatNeverCausesFailover`, `primaryFailureTriggersFailoverAndNoDataIsLost` (asserts detection took >= the timeout).
- **What can go wrong:** False positives from GC pauses or network jitter, thresholds too tight, a dead manager sees nothing.
- **How to explain it in an interview:** "Detection is a trade-off between speed and false positives; I use two states and independent confirmation."

## 8. Gossip protocol

- **What it is:** Nodes periodically tell random peers what they know about the cluster.
- **Why we need it:** Membership spreads without a central list and without every node contacting every other node.
- **How it works:** Each node increments its own heartbeat counter and pushes its table to 2 random peers; higher incarnation/heartbeat wins on merge.
- **Where it fits in this project:** Membership convergence shown by `/cluster/gossip`.
- **Classes involved:** `GossipAgent`, `NodeServer` (`GOSSIP`, `MEMBERS`)
- **How to implement it:** Status (ALIVE/SUSPECT/DEAD) is derived locally from how long a counter stopped increasing.
- **How to test it:** `gossipSpreadsMembershipToAllNodes` (nodes that only know one seed learn everyone).
- **What can go wrong:** Slow convergence in big clusters, no indirect probes (SWIM), simplified suspicion handling.
- **How to explain it in an interview:** "A simplified heartbeat-counter gossip: eventual membership convergence in O(log N) rounds."

## 9. Quorum and split-brain protection

- **What it is:** Acting only when a majority agrees.
- **Why we need it:** Without it, a partitioned manager could 'fail over' healthy nodes and cause chaos.
- **How it works:** Failover requires reaching a strict majority of members and a majority of peers also failing to reach the suspect.
- **Where it fits in this project:** Before any failover.
- **Classes involved:** `FailoverManager.execute`
- **How to implement it:** Count reachable members; probe up to 3 peers (`PROBE`).
- **How to test it:** `managerRefusesFailoverWithoutQuorum` (3 of 6 isolated -> nobody fails over, `FAILOVER_BLOCKED` event).
- **What can go wrong:** Cannot fail over beyond a minority of losses; the manager itself is a single point of failure.
- **How to explain it in an interview:** "I chose consistency of the topology over availability of failover when the manager cannot see a majority."

## 10. Automatic failover and replica promotion

- **What it is:** Moving ownership of a dead node's keys to its replicas.
- **Why we need it:** Requests must keep working without human action.
- **How it works:** Remove the node from the ring: its first replica becomes the primary; then reconcile re-creates missing copies.
- **Where it fits in this project:** Failure Simulation page; Recovery Analytics.
- **Classes involved:** `FailoverManager`, `RebalanceService`, `FailoverRecord`
- **How to implement it:** CAS `SUSPECTED->FAILED` under the topology lock; record detection/failover/re-replication times.
- **How to test it:** `primaryFailureTriggersFailoverAndNoDataIsLost`, `multipleSequentialFailuresAreHandled`, `ringTest.firstReplicaBecomesPrimaryWhenPrimaryIsRemoved`.
- **What can go wrong:** Writes lost in the async window, double promotion (prevented), stale replica (healed by highest-version reconcile).
- **How to explain it in an interview:** "Promotion is just the ring successor taking over, so there is no separate election to get wrong."

## 11. Recovery and anti-entropy (reconcile)

- **What it is:** Bringing a returning or new node up to date and repairing differences.
- **Why we need it:** A node that was away holds old data; it must not serve it or overwrite newer data.
- **How it works:** Bulk sync against a candidate ring, add to ring, delta sync, ACTIVE; always copy the newest version.
- **Where it fits in this project:** Join, recovery, maintenance resume, settings changes.
- **Classes involved:** `RecoveryManager`, `RebalanceService`, `ConsistencyVerifier`
- **How to implement it:** Scan reachable nodes, pick the newest copy per key, push to owners, drop surplus copies.
- **How to test it:** `crashedNodeIsRebuiltFromReplicasAndNewerDataIsNeverOverwritten`, `nodeJoinsAndLeavesGracefully`.
- **What can go wrong:** O(keys) scan cost, writes during the sync window (handled by the second pass), unreachable nodes.
- **How to explain it in an interview:** "Reconcile is idempotent and version-guarded, so running it twice or concurrently with writes is safe."

## 12. LRU / LFU eviction

- **What it is:** Removing entries when memory is full: least recently used / least frequently used.
- **Why we need it:** A cache must stay inside its memory limit.
- **How it works:** Track last access time and an access counter per entry; sort candidates and evict down to 90% of the limit.
- **Where it fits in this project:** Per-node memory limit and strategy in Settings.
- **Classes involved:** `CacheStore.enforceLimit`, `EvictionPolicy`
- **How to implement it:** Byte estimate per entry; `SETCONF` pushes policy and limit to nodes.
- **How to test it:** `lruEvictsLeastRecentlyUsedFirst`, `lfuEvictsLeastFrequentlyUsedFirst`.
- **What can go wrong:** O(n log n) per eviction burst (approximate sampling is the production fix), LFU can evict brand-new entries, eviction is local to a node.
- **How to explain it in an interview:** "Exact LRU/LFU for clarity; Redis uses sampling to avoid scanning."

## 13. Observability

- **What it is:** Measuring the system so you can explain its behaviour.
- **Why we need it:** You cannot trust or tune what you cannot see.
- **How it works:** One `MetricsRecorder` feeds both the REST API (React) and Micrometer (Prometheus/Grafana).
- **Where it fits in this project:** Dashboard, Grafana dashboard, advisor.
- **Classes involved:** `MetricsRecorder`, `MonitoringService`, `ClusterMetricsBinder`
- **How to implement it:** Counters, a 20,000-sample latency window for percentiles, per-second buckets.
- **How to test it:** `concurrentClientsAreAllServedAndMeasured`.
- **What can go wrong:** Percentiles over a sliding window are approximate; high-cardinality labels.
- **How to explain it in an interview:** "A single source of truth for metrics avoids dashboards that disagree."

## 14. JWT authentication and role-based access

- **What it is:** Signed tokens that carry the user's role; endpoints check the role.
- **Why we need it:** Cluster management must not be public.
- **How it works:** Login verifies a BCrypt hash and issues an HS256 JWT; Spring's resource server validates it; rules map HTTP method+path to roles.
- **Where it fits in this project:** Every `/api/v1` call.
- **Classes involved:** `SecurityConfig`, `JwtService`, `AuthController`, `AuditFilter`
- **How to implement it:** Roles ADMIN / OPERATOR / VIEWER; login throttling; masked cache values.
- **How to test it:** `ApiSmokeTest` (written, run it with `mvn test`).
- **What can go wrong:** Weak/default secrets, tokens cannot be revoked (stateless), CORS mistakes.
- **How to explain it in an interview:** "Stateless auth scales horizontally; I keep the signing key in an environment variable."

## Ten questions to practise

1. Why not `hash(key) % N`?
1. What happens to my data when a node dies, step by step?
1. Where can this system lose data, and why do you accept it?
1. Why can't a heartbeat alone prove a node is dead?
1. How do you stop two nodes becoming primary for the same key?
1. Why is the version a timestamp-based number?
1. What does the quorum rule protect against, and what does it cost?
1. How would you make replication synchronous or quorum-based?
1. What would you change to make the manager highly available?
1. How do you know your failover numbers are real?
