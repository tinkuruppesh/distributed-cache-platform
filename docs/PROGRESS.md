# Project progress and verification status

Rule followed: **nothing is marked "verified" unless it was actually executed.**
Build environment limits: JDK 21 + Node 22 were available, but **Maven Central was not reachable**, so Maven and the
Spring Boot dependencies could not be downloaded there.

## Verification legend
- **VERIFIED** = compiled and executed in the build environment, output observed.
- **WRITTEN** = complete code/config delivered, syntax-checked only, NOT executed (needs your first run).

## Checklist (mirrors the 20 phases of the master prompt)

| Phase | Item | Status |
|---|---|---|
| 0 | Requirements, architecture, API contract, UI structure (docs/ARCHITECTURE.md) | DONE |
| 1 | Project setup (Maven multi-module, React/Vite, folders) | DONE (frontend VERIFIED build; Maven poms WRITTEN) |
| 2 | Single-node cache: SET/GET/DELETE/EXISTS/EXPIRE/TTL, versions, tombstones, validation | **VERIFIED** (unit + TCP tests) |
| 3 | Multiple independent nodes, TCP protocol, node identity, shared-secret auth | **VERIFIED** |
| 4 | Consistent hashing, virtual nodes, join/leave, affected keys | **VERIFIED** (6 ring tests) |
| 5 | Replication: factor, async acks, version-guarded writes, lag/failure stats | **VERIFIED** |
| 6 | Heartbeat, SUSPECTED/FAILED states, gossip membership | **VERIFIED** |
| 7 | Automatic failover with quorum + peer-probe safeguards | **VERIFIED** (incl. "no quorum -> no failover") |
| 8 | Node recovery, stale-data protection, crash rebuild | **VERIFIED** |
| 9 | LRU/LFU eviction, memory limits, TTL cleanup, eviction metrics | **VERIFIED** (store tests) |
| 10 | Management REST APIs (cache, cluster, monitoring, simulation, advisor, audit, settings) | WRITTEN |
| 11-14 | React dashboard: 12 pages, charts, hash ring, simulation, advisor | `vite build` **VERIFIED**; runtime against live backend NOT run |
| 14 | Optimization advisor rules + store + workflow | **VERIFIED** (rule tests with explicit inputs) |
| 13 | Failure simulation + measured recovery report | **VERIFIED** (engine level, 60 rps traffic, report asserted) |
| 15 | JWT, RBAC, audit logging | WRITTEN (covered by ApiSmokeTest, not yet run) |
| 16 | Actuator, Micrometer, Prometheus, Grafana dashboard JSON | WRITTEN |
| 17 | Dockerfiles, docker-compose (6 nodes, PostgreSQL, Prometheus, Grafana, frontend) | WRITTEN (YAML/JSON parse-checked) |
| 18 | Tests | engine tests **VERIFIED**; Spring `ApiSmokeTest` WRITTEN |
| 19 | k6 script | WRITTEN (not executed; **no benchmark numbers exist yet**) |
| 20 | Final polish on your machine: first full `mvn verify`, Docker run, screenshots, benchmarks | **YOUR NEXT STEP** |

## Engine test results (executed in the build environment, real sockets, real timeouts)
See the "Test run" section at the bottom of this file (filled in at packaging time).

## Honest deviations from the master prompt (and why)

| Prompt asked for | What was built | Reason / plan |
|---|---|---|
| Netty for node-to-node TCP | Plain `java.net` sockets + Java 21 virtual threads, same custom versioned text protocol | Netty could not be downloaded to compile/test; the protocol is isolated in `NodeServer`/`NodeClient`, so swapping in Netty later is a contained change |
| Consensus / election protocol "evaluated" | Single coordinator (the manager) + majority-of-members rule + peer probes; Raft is documented as the future extension | Simple, explainable, testable; the trade-off (manager is a single point of failure) is documented in CONSISTENCY.md |
| WebSocket/SSE hook | HTTP polling (2-5 s, configurable) | Robust behind proxies; `useWebSocket.js` intentionally not created (no empty placeholders) |
| `roles` + `user_roles` tables | one `role` column on `users` | three fixed roles; simpler and equivalent here |
| Mutual TLS between nodes | shared-secret authentication on every node command; no encryption | TLS must be added for production (see docs/CONSISTENCY.md "Security notes") |
| Testcontainers | not used | H2 profile makes tests self-contained |
| Redis comparison | not included | optional in the prompt |
| Screenshots / benchmark results in README | none invented | take them on your machine (README explains how) |

## Test run (executed 2026-11-10 12:06 UTC in the build environment)
Clean compile of `cache-core` with JDK 21, then all five engine test classes executed with a minimal stand-in for the JUnit 5
runner (Maven Central was unreachable there). The tests are standard JUnit 5, so `mvn test` on your machine will run the same code.

```
CacheStoreTest (9) · ConsistentHashRingTest (10) · AdvisorRulesTest (8) · NodeProtocolTest (8) · ClusterIntegrationTest (17)
RESULT: 52 passed, 0 failed
```
Two real bugs were found and fixed by these tests during development (documented for honesty):
1. the quorum peer-probe waited 300 ms while the node-side probe needed 400 ms, so every probe "abstained" and failover never happened (fixed: node probe 250 ms, manager waits `pingTimeout*2+400` ms);
2. a wrong expectation in the hot-key advisor test (70% share is correctly CRITICAL) - the test was corrected, not the rule.

### One measured simulation (production-default timings: heartbeat 1000 ms, failure timeout 5000 ms, request timeout 800 ms; 6 nodes on localhost; ~100 req/s)
```
outcome=COMPLETED  detection=5795 ms  failover(routing update)=8 ms  recovery(restart->ACTIVE)=215 ms (sync 103 ms)
requests=872  succeeded=872  failed=0 (error rate 0.0%)  served via replica fallback=17
keys promoted=229  entries re-replicated=606  consistency check: CONSISTENT (1200 keys)
latency: avg 7.2 ms, p95 3.0 ms, p99 8.8 ms, max 806 ms (the 800 ms timeout before falling back to a replica)
```
These numbers come from one run on a development sandbox. They are an example of what the system measures, **not a benchmark**: 0 failed requests
here is an observed result, not a guarantee (async replication can still lose in-flight writes, see CONSISTENCY.md).
Not executed anywhere yet: the Spring Boot module, `ApiSmokeTest`, Docker stack, Grafana, k6.
