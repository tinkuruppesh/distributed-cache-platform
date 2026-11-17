# REST API reference (`/api/v1`, JSON, `Authorization: Bearer <JWT>`)

Roles: **A** = ADMIN, **O** = OPERATOR, **V** = VIEWER. Errors: `{timestamp,status,error,message,path}` (400 validation, 401, 403, 404, 409 conflict, 503 cluster/node unavailable).

## Authentication
| Method & path | Roles | Description |
|---|---|---|
| `POST /auth/login` `{username,password}` | public | -> `{token,tokenType,expiresAt,username,role}`; 5 failed attempts / 5 min are throttled (429) |
| `POST /auth/logout` | any | audit entry; client discards token (stateless JWT) |
| `GET /auth/me` | any | current user and roles |

## Cache (gateway)
| Method & path | Roles | Description |
|---|---|---|
| `POST /cache` `{key,value,ttlSeconds?}` | A,O | SET -> 201 `{key,version,expiresAt,writtenTo,replicas,fallbackAttempts,message}` |
| `GET /cache/{key}` | any | GET -> 200 entry (value masked for VIEWER) or 404 |
| `DELETE /cache/{key}` | A,O | DELETE (tombstone replicated) -> 200 / 404 |
| `GET /cache/{key}/exists` | any | `{key,exists}` |
| `GET /cache/{key}/ttl` | any | `{hasExpiry,ttlMillis,ttlSeconds}` or 404 |
| `POST /cache/{key}/expire` `{ttlSeconds}` | A,O | set TTL (0 = remove expiry) |
| `GET /cache/{key}/locate` | any | primary, replicas, node states, ring position |
| `GET /cache/keys?prefix=&limit=&reveal=` | any | Cache Explorer search; values masked unless ADMIN reveals (sensitive key names stay masked for non-admins) |

Key rule: 1-200 chars `[A-Za-z0-9:_-.]`; TTL 0-30 days; value <= 256K chars.

## Cluster
| Method & path | Roles | Description |
|---|---|---|
| `GET /cluster` | any | overview + settings + flags |
| `GET /cluster/nodes` / `GET /cluster/nodes/{id}` | any | node views / node detail + recent events |
| `POST /cluster/nodes` `{id,host?,port?,startEmbedded?}` | A | join (identity verified, data synchronized before serving) -> 201 |
| `DELETE /cluster/nodes/{id}` | A | graceful removal (data moved first) |
| `POST /cluster/nodes/{id}/maintenance` / `/resume` | A,O | drain / re-integrate |
| `GET /cluster/hash-ring?sample=` | any | virtual nodes, ownership %, sampled key placement |
| `GET /cluster/health` | any | `{status,activeNodes,totalNodes,...}` |
| `GET /cluster/events?limit=` | any | cluster event journal |
| `GET /cluster/gossip` | any | each node's membership view + convergence flag |
| `GET /cluster/failovers` | any | failover history |

## Monitoring
`GET /monitoring/overview`, `/traffic?seconds=`, `/cache-performance`, `/memory`, `/replication`, `/events` (all roles). Data is measured: gateway counters, sliding latency window (p50/p95/p99), per-second buckets, node heartbeats, replication acks.

`GET /monitoring/replication` reports configured factor/mode, process-local replication counters and recent RPC latency outcomes, plus a bounded (5,000 unique live keys) scan. Its per-node fields distinguish expected ring ownership from observed copies; `scanStatus`, `keysDiscovered`, and `errorReason` report each node's latest scan. Missing/stale replica counts are based only on successful owner scans. `scanComplete`, `nodesAttempted`, `nodesScanned`, `scanErrors`, `keysInspected`, and `truncated` describe coverage. Raw cache values are not returned. Expected/observed counts cover the inspected sample, not necessarily the full keyspace.

## Simulation (ADMIN)
| Method & path | Description |
|---|---|
| `POST /simulations` `{nodeId,mode:ISOLATE\|CRASH,generateTraffic,trafficRps,autoRestartAfterSec,confirm:true}` | start (202); `confirm` is mandatory |
| `GET /simulations?q=` | live + persisted runs, searchable |
| `GET /simulations/{id}` | summary, events, report |
| `POST /simulations/{id}/restart-node` | restart the failed node (when `AWAITING_RESTART`) |
| `GET /simulations/{id}/report` | measured report (409 until ready) |
(GET endpoints are readable by every role.)

## Advisor
`GET /advisor/recommendations?status=&category=`, `GET /advisor/recommendations/{id}` (all roles);
`POST /advisor/recommendations/{id}/acknowledge|resolve|dismiss`, `POST /advisor/run` (A,O).

## Audit / settings
`GET /audit-logs?q=&page=&size=` (A). `GET /settings` (A,O), `PUT /settings` (A): validated first, applied second, persisted;
changing replication factor or virtual nodes triggers a rebalance.

## Operations endpoints
`/actuator/health`, `/actuator/info`, `/actuator/prometheus` are public: expose them only inside a trusted network (Compose does).
