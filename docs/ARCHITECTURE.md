# Architecture (Phase 0 deliverable, kept in sync with the code)

## 1. Overview
A **custom distributed in-memory cache**: data is sharded across independent nodes with **consistent hashing**, copied to
replicas, monitored by **heartbeats + gossip**, repaired by **automatic failover** and **recovery**, and operated through a
REST API and a React dashboard that includes **failure simulation** and a **rule-based optimization advisor**.
It is a learning/demonstration platform, not a Redis replacement.

## 2. System architecture

```
 React dashboard (5173 / nginx 3000)        k6 / client applications
            |  REST + JWT                              |
            +------------------+-----------------------+
                               v
              MANAGEMENT API  (Spring Boot, :8080)
   +-------------------------------------------------------------+
   | Gateway: CacheRouter  -> ConsistentHashRing (vnodes)         |
   | ClusterCoordinator: node registry, states, event log         |
   | HeartbeatService -> FailoverManager -> RecoveryManager       |
   | RebalanceService (reconcile) - ReplicationManager (async)    |
   | MonitoringService - MetricsRecorder - Recommendation engine  |
   | SimulationService - Security (JWT/RBAC) - Audit - Micrometer |
   +--------+--------------+--------------+----------------------+
            | TCP (secret) | TCP          | TCP
            v              v              v
        CACHE NODE 1   CACHE NODE 2 ... CACHE NODE 6      (each: CacheStore + gossip + TCP server)
            ^-------------- gossip (node <-> node) --------^
   PostgreSQL: users, audit, simulations, failovers, recommendations, settings (metadata only)
   Prometheus <- /actuator/prometheus      Grafana <- Prometheus
```

Two modules: **cache-core** (pure Java, no framework: the whole distributed engine, tested) and **management-api**
(Spring Boot: HTTP, security, persistence, metrics - a thin layer over cache-core).

## 3. Backend architecture (packages)
| Package | Responsibility |
|---|---|
| `cache` | `CacheStore` (ConcurrentHashMap, TTL, LRU/LFU, tombstones), `CacheEntry` |
| `hashing` | `ConsistentHashRing`, `VirtualNode`, `Md5HashFunction` |
| `networking` | `NodeClient` (pooled TCP client), `Wire` (protocol encoding), `NodeAddress` |
| `node` | `NodeServer` (TCP command server), `GossipAgent`, `NodeConfig`, `CacheNodeMain` |
| `cluster` | `ClusterCoordinator`, `NodeInfo`/`NodeState`, `MembershipManager`, `ClusterRuntime`, settings, `EventLog` |
| `failure` | `HeartbeatService` |
| `failover` | `FailoverManager`, `FailoverRecord` |
| `recovery` | `RecoveryManager`, `RebalanceService` (reconcile), `ConsistencyVerifier` |
| `replication` | `ReplicationManager` |
| `routing` | `CacheRouter` (gateway: routing, fallback, measurement) |
| `monitoring` | `MetricsRecorder`, `MonitoringService` |
| `simulation` | `SimulationService`, `TrafficGenerator` |
| `advisor` | rules, `RecommendationEngine`, `RecommendationStore` |
| `exception` | domain exceptions (+ `GlobalExceptionHandler` in management-api) |
| management-api: `api.*`, `security`, `persistence`, `audit`, `config`, `observability` | REST controllers, JWT/RBAC, JPA, audit filter, Spring wiring, Micrometer |

## 4. Node communication design
One request = one text line over TCP: `<cluster-secret> <COMMAND> <args>`; values are Base64. Responses: `OK ...`, `NIL`, `ERR ...`.

| Command | Purpose |
|---|---|
| `PING` | heartbeat; returns health counters (uptime, entries, memory, hits, misses, evictions, requests...) |
| `PUT / GET / DEL / EXPIRE / TTL` | client operations on the primary (return the full versioned entry) |
| `REPL` | replica write, applied only if newer (version guard) |
| `SCAN` | stream all entries (used by reconciliation) |
| `DROP` | remove a surplus copy after rebalancing |
| `PROBE host port` | "can YOU reach this node?" (quorum confirmation of a failure) |
| `GOSSIP` / `MEMBERS` | membership exchange / local membership view |
| `SETCONF` | eviction policy + memory limit |
| `ISOLATE / CRASH / UNISOLATE` | failure simulation (black-hole / black-hole + wipe memory / restore) |

Every connection is served on a virtual thread; the client keeps a small per-node connection pool and enforces hard timeouts.

## 5. Data model
`CacheEntry(key, value, createdAt, expiresAt, version, updatedAt, deleted)`.
`version = max(previous+1, wall-clock millis)` is strictly increasing per key and survives eviction or restart.
"Newer" = higher version, then `updatedAt`, then value hash (total order, so every node converges to the same winner).
Deletes are **tombstones** (kept 10 minutes) so stale replicas cannot resurrect deleted keys.

## 6. Key algorithms
- **Placement:** primary = first virtual node clockwise from `hash(key)`; replicas = next distinct physical nodes. Copies per key = `1 + replicationFactor`.
- **Replica promotion = ring removal:** the first replica is the ring successor, so removing a failed node makes it primary automatically; `RebalanceService` then restores full redundancy.
- **Reconcile:** scan reachable nodes, take the newest copy of every key, push it to the owners under the target ring, drop surplus copies. Used for join, failover, recovery, maintenance and settings changes.
- **Join / recovery:** bulk sync against a *candidate* ring -> add node to the real ring -> delta sync -> ACTIVE (live routing untouched during the bulk copy).
- **Failure detection:** parallel pings each interval; `silent >= suspect timeout` -> SUSPECTED (tried last by the router); `silent >= failure timeout` -> failover candidate; one missed beat never matters.
- **Failover safeguards:** majority-of-members rule, peer probes, CAS state transition, single-flight flag per node, single-thread executor, global topology lock.

## 7. Database design (PostgreSQL via Flyway `V1__init.sql`)
`users`, `audit_logs`, `simulation_runs` (report + events as JSON), `failover_history`, `recommendations`,
`application_settings`. Primary keys, constraints (role / status checks), indexes, created/updated epoch-millis columns.
Cache values are never stored in the database. (Spec tables `roles`/`user_roles`/`cluster_nodes`/`cluster_configuration`/
`simulation_events` are folded into columns/JSON or config: see PROGRESS.md.)

## 8. API architecture
Versioned REST under `/api/v1`, DTOs with Bean Validation, one `GlobalExceptionHandler` (400/401/403/404/409/503...),
stateless JWT (HS256) with roles ADMIN / OPERATOR / VIEWER. Full list in `API.md`.

## 9. Frontend architecture
React 18 + Vite + React Router + Axios + Recharts + lucide-react. Layers: `pages` -> `components` (common, charts, cluster,
simulation, advisor) -> `hooks` (`usePolling`, `useClusterStatus`, `useMetrics`) -> `services` (one per API area) -> axios
instance with token injection. `AuthContext` (session) and `ClusterContext` (settings, refresh interval). Every number on screen comes from an API call.

## 10. UI page structure
Overview - Cluster Nodes - Cache Explorer - Hash Ring - Replication - Live Monitoring - Failure Simulation -
Recovery Analytics - Optimization Advisor - Audit Logs - Settings (+ Login). Light infrastructure-SaaS design system in `styles/variables.css`.

## 11. Roadmap (phases) and status
See `PROGRESS.md`.

## 12. Key decisions and trade-offs
| Decision | Why | Cost |
|---|---|---|
| Pure-Java core + thin Spring layer | engine is testable without a framework; Spring only does HTTP/security/DB | two modules |
| Coordinator-managed membership instead of Raft | simple, explainable, deterministic tests | manager is a single point of failure; no failover beyond a minority of lost members |
| Async replication | low latency, availability first (cache workload) | writes acknowledged but not yet replicated can be lost if the primary dies |
| Replica promotion by ring removal | no separate election needed; mathematically the right owner | requires reconcile to rebuild redundancy |
| Hybrid timestamp versions | correct after eviction/restart; no coordination | depends on roughly synchronized clocks |
| Reconcile = O(keys) scan | tiny code, obviously correct | not for huge datasets (production: Merkle trees / range streaming) |
| Plain sockets + virtual threads | easy to read and test | Netty would give more tuning/TLS options |
| HTTP polling for the dashboard | robust, simple | slightly more requests than push |
| Simulation by in-protocol ISOLATE/CRASH | safe: no process/Docker control exposed | does not exercise real OS-level kill |
