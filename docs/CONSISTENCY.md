# Consistency, availability and failure behaviour

## Position on CAP
Under a network partition a distributed system must choose between **Consistency** and **Availability**.
This cache chooses **availability + eventual consistency** (appropriate for cache workloads: a stale or missing entry is
re-fetched from the database; an outage is worse). It does **not** provide linearizability or strong consistency.

## Eventual consistency and asynchronous replication
A write is acknowledged after the primary stores it; replicas receive it shortly after (default). Between those moments:
- a read served by a replica can return the **previous value** (stale read);
- if the primary dies in that window the write is **lost**.
`replication lag` (Replication page, Prometheus) measures that window. `ClusterConfig.syncReplication = true` waits (up to 2 s) for replica acks, narrowing but not eliminating the window (it is still not a quorum protocol).

## Where data can be lost or served stale (be explicit in interviews)
1. Write acknowledged by a primary that crashes before the replica write completes (async replication).
2. Writes made through a replica during the detection window (primary silent but not yet failed) and then overwritten by version order after the primary returns: last-writer-wins by `(version, updatedAt)`.
3. Concurrent writes to the same key via different nodes during failover: one wins, the other is discarded (no merge).
4. A tombstone older than its 10-minute retention no longer protects against a very stale replica resurrecting a deleted key.
5. Eviction is local: an evicted key can be re-created by the next reconcile from another copy, and an entry evicted on all copies is simply a cache miss.
6. A CRASHed node loses everything it held only in memory; redundancy is restored from the surviving replicas (needs `replicationFactor >= 1` and the replicas to be alive).
7. With `replicationFactor = 0` a node failure loses its keys.

## Failure detection and its limits
Heartbeats cannot distinguish "dead" from "slow/partitioned". The detector therefore uses: suspect timeout -> failure timeout,
then **two independent confirmations** before acting:
1. **Quorum rule** - the manager must reach a strict majority of all members. If it sees only a minority it concludes *it* is the isolated one and **refuses to fail anyone over** (event `FAILOVER_BLOCKED`). This is tested: isolating 3 of 6 nodes causes no failover.
2. **Peer probes** - up to 3 healthy nodes are asked to reach the suspect; a majority of responders must also fail.
Limitations: a false positive is still possible under asymmetric partitions; timeouts must be tuned (Advisor warns when `failure.timeout < 3x heartbeat`).

## Split-brain, duplicate promotion, concurrent failover
- Only the manager mutates the ring, so two competing primaries cannot be elected by different nodes (no split-brain *between nodes*).
- Duplicate promotion is prevented by an atomic `SUSPECTED -> FAILED` compare-and-set, a per-node single-flight flag, a single-thread failover executor and a global topology lock (tested through sequential/concurrent failures).
- **Trade-off:** the manager is a **single point of failure** and failover stops when more than a minority of members are lost. Production-grade alternatives: a Raft-replicated coordinator (etcd/ZooKeeper style) or a gossip-based failure detector with quorum decisions (SWIM + epoch fencing). Listed as future work.

## Stale replica promotion
Replicas can lag. After promotion, reconcile scans **all surviving copies** and pushes the **highest version** of every key to its owners,
so the freshest surviving copy wins. It cannot recover a write that no surviving node ever received.

## Network partitions
- Manager partitioned from a minority of nodes: those nodes fail over (majority still reachable); when the partition heals they re-enter through RECOVERING (sync from the newest versions).
- Manager partitioned from a majority: no failover; requests to unreachable nodes fall back to reachable replicas; reads may be stale.
- A partitioned node that still receives traffic from other clients would diverge: in this design clients reach the cache only through the manager, which bounds the divergence.

## Hot keys and rebalancing
A hot key overloads its primary and replicas regardless of node count (Advisor rule `hot-key`). Mitigations: client-side cache, key splitting, more read replicas.
Rebalancing moves only the key ranges adjacent to the changed node (consistent hashing); the cluster stays online, copying happens against a candidate ring and routing switches atomically.

## Security notes
- Control plane: JWT (HS256) with roles; secrets via environment variables; login throttling; audit log of every state-changing operation; masked values by role and key pattern.
- Node protocol: authenticated by a shared secret on every command, **not encrypted**. For production put the nodes on a private network and add TLS (mutual TLS) - future work. Node ports are not published by `docker-compose.yml`.
- Failure simulation is a demo feature: disabled with `SIMULATION_ENABLED=false`, ADMIN-only, requires explicit confirmation, and never exposes process or Docker control.
