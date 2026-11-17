# Self-Healing Intelligent Distributed Cache Platform

A distributed in-memory cache **built from scratch** (no Redis Cluster): custom consistent hashing with virtual nodes,
replication, heartbeats and gossip, quorum-guarded automatic failover, safe node recovery, TTL, LRU/LFU eviction - plus a
professional React dashboard with **failure simulation, recovery analytics** and a rule-based **optimization advisor**.

> Honest status: the distributed engine (`cache-core`) and the dashboard build are **verified**; the Spring Boot layer,
> Docker stack, Grafana and k6 are **written but not yet executed** - run them once on your machine.
> See [docs/PROGRESS.md](docs/PROGRESS.md) for exactly what was and was not verified.

## Problem statement
Applications repeatedly read the same data. A database alone becomes slow and expensive; a single cache server is a bottleneck
and a single point of failure. A distributed cache must also solve data distribution, replication, failure detection, failover,
consistency and recovery. This project implements those mechanisms and makes their behaviour observable.

## Objectives
Distribute data across nodes - consistent hashing - replicas - automatic failure detection - replica promotion - request
redirection - TTL - dynamic join/leave - safe recovery - monitoring - failure simulation - analytics - optimization
recommendations - secure administration - containerized 6-node deployment.

## Features
- **Cache engine:** SET / GET / DELETE / EXISTS / EXPIRE / TTL, versions, tombstones, thread-safe, validated.
- **Consistent hashing** with configurable virtual nodes; only affected ranges move on join/leave.
- **Replication** (configurable factor, async acks, version-guarded writes, lag + failure tracking).
- **Heartbeat** (SUSPECTED before FAILED), **gossip** membership, **quorum + peer-probe** failover safeguards.
- **Failover, recovery, join, graceful removal, maintenance** - all version-safe (stale data never wins).
- **LRU / LFU eviction**, per-node memory limits, scheduled TTL cleanup.
- **Dashboard (12 pages):** overview, nodes, cache explorer, hash ring, replication, live monitoring, failure simulation, recovery analytics, advisor, audit logs, settings.
- **Failure simulation** with a *measured* report (detection / failover / recovery time, request success, consistency check).
- **Optimization advisor:** 9 deterministic rules, severities, workflow (open / acknowledge / resolve / dismiss).
- **Security:** JWT, ADMIN / OPERATOR / VIEWER, audit log, masked values, login throttling.
- **Observability:** Actuator + Micrometer + Prometheus + provisioned Grafana dashboard.

## Technology stack
Java 21 (virtual threads), Spring Boot 3.3 (Web, Validation, Security + OAuth2 resource server, Data JPA, Actuator), Flyway,
PostgreSQL (H2 in dev), Maven, React 18 + Vite + React Router + Axios + Recharts + lucide-react, Docker Compose, Prometheus, Grafana, JUnit 5, k6.
Internal node protocol: custom versioned text protocol over plain TCP sockets (Netty deliberately not used - see PROGRESS.md).

## Architecture
```
 React dashboard --REST/JWT--> MANAGEMENT API (gateway, ring, heartbeat, failover, recovery, metrics, advisor)
                                   | TCP + shared secret
              CACHE NODE 1 ... CACHE NODE 6   (CacheStore + gossip)   <-- gossip between nodes
 PostgreSQL (metadata)   Prometheus -> Grafana
```
Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Folder structure
```
cache-core/       pure-Java engine (cache, hashing, networking, node, cluster, failure, failover, recovery,
                  replication, routing, monitoring, simulation, advisor) + unit/integration tests
management-api/   Spring Boot: api, security, persistence (Flyway), audit, config, observability + tests
frontend/         React dashboard (pages, components, hooks, services, context, routes, styles)
docker/           Dockerfiles, Prometheus config, Grafana provisioning + dashboard
k6/               load test        docs/  guides        scripts/  helper scripts        .vscode/  VS Code config
docker-compose.yml   .env.example
```

## Database architecture
PostgreSQL stores **metadata only**: `users`, `audit_logs`, `simulation_runs`, `failover_history`, `recommendations`,
`application_settings` (Flyway `V1__init.sql`). Cache entries live only in node memory.

## API documentation
[docs/API.md](docs/API.md) - every endpoint, role and payload.

## How the core mechanisms work
- **Consistent hashing:** key and nodes are hashed onto a ring; a key belongs to the next virtual node clockwise; replicas are the next distinct nodes. Adding/removing a node moves ~1/N of the keys.
- **Replication:** primary write, then asynchronous `REPL` to replicas; a replica applies an entry only if it is newer (version guard).
- **Failure detection:** parallel heartbeats; silent beyond the suspect timeout -> SUSPECTED (still in the ring, tried last); beyond the failure timeout -> candidate.
- **Failover:** requires a majority of members reachable AND a majority of peers unable to reach the node; then the node leaves the ring, its replicas become primaries, and missing copies are rebuilt.
- **Recovery:** returning node -> RECOVERING -> bulk sync (newest versions only) -> join ring -> delta sync -> ACTIVE.
Deep dive and limits: [docs/CONSISTENCY.md](docs/CONSISTENCY.md). Study material: [docs/LEARNING_GUIDE.md](docs/LEARNING_GUIDE.md).

## Quick start (VS Code, no Docker)
```powershell
# Terminal 1, from the repository root:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-backend.ps1

# Terminal 2:
cd frontend
npm install
npm run dev  # http://localhost:5173
```
On Windows, the backend launcher checks for an existing API and all six node listeners before building or starting. If the dev backend is already healthy, it leaves that instance running instead of opening the same H2 file again.
Login `admin` / `Admin@12345` (dev only). Full guide incl. installs, IntelliJ, Docker, troubleshooting: [docs/RUN_GUIDE.md](docs/RUN_GUIDE.md).

## Docker
```bash
cp .env.example .env   # edit all secrets
docker compose up --build
```
Dashboard :3000, API :8080, Prometheus :9090, Grafana :3001, PostgreSQL 127.0.0.1:5432; the six nodes are private to the Docker network.

## Testing
```bash
mvn test
```
Engine tests (`cache-core`) run real nodes on real sockets: distribution, replication, failover, quorum refusal, crash recovery,
stale-write protection, join/leave, TTL, concurrency, simulation report, settings validation. Results are recorded in `docs/PROGRESS.md`.

## Screenshots
*None are included on purpose* (they would have to be invented). Capture your own after the first run: Overview, Hash Ring,
Failure Simulation timeline, Recovery report, Advisor, Grafana, and add them under `docs/screenshots/`.

## Benchmark results
*None are published.* Run `k6/load-test.js` (100 / 500 / 1000 users; healthy, one node failed, during recovery, different replication
factors) and paste your own measured numbers here.

## Known limitations
Async replication can lose in-flight writes; manager is a single coordinator (no Raft); failover needs a majority of members;
node protocol is authenticated but not encrypted; reconcile is O(keys); eviction is exact but O(n log n); the Spring layer, Docker
stack, Grafana and k6 script have not been executed by the author of this code generation - expect to fix small compile/config issues on first run.

## Future enhancements
Raft/etcd-backed coordinator or SWIM with fencing epochs; mutual TLS; Netty transport; quorum/synchronous write mode; Merkle-tree anti-entropy;
WebSocket/SSE push; sampled LRU/LFU; Redis comparison benchmark; Kubernetes manifests.
