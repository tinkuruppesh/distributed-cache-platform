# Self-Healing Distributed Cache — Project Audit

**Audit type:** Static, read-only source and configuration review  
**Date:** 2026-11-13  
**Scope:** Existing React dashboard, Spring Boot management API, custom cache engine, persistence, auth, Micrometer/Prometheus, Docker and project documentation. No application source was changed. No tests or services were run as part of this audit, so runtime observations below are identified as source/configuration findings rather than execution results.

## Executive summary

The project is an implemented, multi-module application rather than a UI-only prototype. The React app has all eleven routes and API service calls. The backend provides the corresponding cache, cluster, monitoring, simulation, advisor, audit, settings and auth controllers. The custom engine includes cache routing, replication, heartbeats, failure detection, failover, recovery and a controlled failure simulator. H2 and PostgreSQL profiles share Flyway schema migrations; operational records and settings have persistence paths.

The highest-priority issue is a Docker port mismatch: `application.yml` sets `server.port: 8081`, while the Compose port mapping, frontend Nginx proxy and Prometheus target all use container port 8080. The Dockerfile exposes 8080 but does not itself change Spring's configured port. Unless an external runtime setting overrides it, the API will listen on 8081 and the container integrations will fail to reach it.

The dashboard has useful loading and initial error states on several pages, but many secondary polling requests render a perpetual loading placeholder on failure. Monitoring history and counters are JVM-local and bounded, while database persistence is selective; restarting the API loses those in-memory measurements and event history. Recovery Analytics reports simulation runs only and lacks an aggregate recovery/failover-history API.

## Project structure and existing functionality

- `cache-core`: plain Java cache engine, consistent hash ring, node TCP protocol, membership/gossip, routing, replication, failover, recovery, metrics recording, advisor rules and simulation.
- `management-api`: Spring Boot REST gateway, configuration, JWT/RBAC, JPA repositories, Flyway migration and Micrometer binder.
- `frontend`: React 18/Vite single-page management dashboard with all eleven routes, API client, role-aware views, charts and polling hooks.
- `docker`: Compose deployment, separate cache-node containers, API/frontend containers, Prometheus and Grafana provisioning.
- `docs`: architecture, API, consistency, run and learning guides. Existing API documentation describes the current controller surface.
- Existing tests include cache-core unit/integration tests and an API smoke test. They were inspected but not run.

Cache entries themselves remain in node memory; the SQL schema explicitly stores platform metadata rather than cache contents. Dev/test profiles use H2; Docker profile configures PostgreSQL. Settings, audit records, finished simulation snapshots, failover records and recommendations have persistence components, with caveats noted below.

## Navigation section audit

### 1. Overview

**Existing:** `Overview.jsx` composes cluster overview and node polling, measured traffic charts, node status, recent cluster events, open advisor recommendations and role-aware traffic/demo controls. Data is fetched from `/monitoring/overview`, `/cluster/nodes`, `/monitoring/traffic`, `/cluster/events` and `/advisor/recommendations`.

**Gaps/problems:** The page handles the main overview's initial loading and error states. Secondary requests (nodes, traffic, events, alerts) do not show an error state; failed requests can leave their cards on loading or empty states. The hit ratio is displayed as a dash when it is exactly zero, which conflates a measured zero with unavailable data. The traffic control can insert clearly synthetic sample keys and generated requests; those are real cache operations and therefore affect the real metrics, but should be treated as demo workload when interpreting measurements.

### 2. Cluster Nodes

**Existing:** `ClusterNodes.jsx`, `NodeTable.jsx`, and `NodeDetails.jsx` connect to node list/detail, add, remove, maintenance and resume endpoints. UI actions are role-gated, confirmed where destructive, and use actual engine operations. Embedded-node creation is surfaced only when the API reports it is available.

**Gaps/problems:** List polling has initial loading/error handling, but a later refresh error is hidden while stale data remains. Node-detail and action errors appear in a shared message, but detail requests have no explicit busy state. There is no clear indication that a stale node list is being shown after a polling failure.

### 3. Cache Explorer

**Existing:** `CacheExplorer.jsx` uses `/cache/keys`, `/cache/{key}`, `/locate`, `/expire`, set and delete operations. It supports prefix search, TTL, ownership, masked values and administrator reveal. Backend authorization masks values for non-admins and protects sensitive key names in listings.

**Gaps/problems:** Search errors are surfaced as a message, but existing rows remain visible and the page has no distinct initial loading/error component. Detail selection can remain selected after a failed fetch, so users need the message to understand that details are stale/unavailable. Key search is bounded by a `limit` (up to 500) and is not paginated; large keyspaces cannot be browsed exhaustively. The empty state suggests loading sample data, which invokes the demo helper and writes 100 synthetic sample entries.

### 4. Hash Ring

**Existing:** `HashRing.jsx` polls `/cluster/hash-ring` and renders actual virtual nodes, ownership distribution and placement for sampled existing cache keys. API validates sample size.

**Gaps/problems:** Sample key placement only covers keys currently found in cache; an empty cache provides no representative key placements. This is a live ring view, not a historical ring-change view. Initial load errors are handled; refresh errors with prior data are not clearly indicated.

### 5. Replication

**Existing:** `Replication.jsx` polls `/monitoring/replication` for configured factor/mode, counters, lag samples, failures and per-node primary/replica counts. Counts are explicitly labeled as sampled.

**Gaps/problems:** The page always renders an “Asynchronous replication” explanatory alert even when the header says synchronous mode. This is an incorrect frontend/backend presentation when synchronous replication is configured. Per-node key counts inspect at most 5,000 cache entries, as the endpoint reports. No replication controls are on this page; factor and sync settings are managed through Settings.

### 6. Live Monitoring

**Existing:** `LiveMonitoring.jsx` combines measured overview, traffic/cache-performance time buckets, node memory/state history, events and failover chart. `/monitoring/overview`, `/traffic`, `/memory`, `/events`, `/replication` and `/cluster/failovers` are implemented. `MetricsRecorder` tracks request totals, success/failure, hits/misses, fallback count, latency samples, per-second buckets and bounded hot-key data.

**Gaps/problems:** Main overview errors are visible, but errors in traffic, memory, events and failovers are not. Their cards can remain in loading/empty states indefinitely. Monitoring samples and counters are in process memory: latency keeps the last 20,000 observations, traffic buckets span 900 seconds, memory history spans 300 samples at two-second intervals (about ten minutes), and key-access tracking caps at 5,000 keys. These are bounded operational views, not durable historical analytics. “P99” and similar measurements are calculated from the in-memory sliding sample window, not Prometheus query results.

### 7. Failure Simulation

**Existing:** `FailureSimulation.jsx` and simulation components use `/simulations`, `/simulations/{id}`, `/restart-node`, `/report`, plus cluster nodes and measured overview. The core simulator injects an authenticated node isolation/crash command and observes the real failure detection, failover and recovery path. It requires admin role, explicit confirmation, enabled simulation config and at least two active nodes. UI remembers the selected active run in `sessionStorage`.

**Gaps/problems:** Node list and monitoring request failures do not have consistently visible error handling. If session state references a simulation no longer available in that API process, the page can repeatedly fail loading the run without a user-facing recovery action. Restarting an API process also discards live run state; persistence listeners save run snapshots, but the simulator's active-control operation remains in-memory. Simulation is enabled by default in base config and should be disabled explicitly outside isolated demo/test environments.

### 8. Recovery Analytics

**Existing:** `RecoveryAnalytics.jsx` lists persisted/live simulation summaries through `/simulations`, derives averages from completed reports, and loads the report and event timeline from `/simulations/{id}`. The report contains measured detection, failover, recovery, request and consistency information.

**Gaps/problems:** Despite the broad “Recovery Analytics” name, the view only aggregates failure simulations; it does not analyze normal cluster failovers or the persisted `failover_history` table. There is no dedicated aggregate recovery analytics endpoint, and its averages are calculated client-side over the currently returned simulation list (no pagination). Selecting another row does not clear the prior `detail` before the request completes, so an older report may remain visible during a failed or delayed selection.

### 9. Optimization Advisor

**Existing:** `OptimizationAdvisor.jsx` calls list/filter, run and status action APIs. The core advisor evaluates measured cache/cluster metrics against built-in rules; recommendations are deduplicated and status changes are persisted. Role controls are present in advisor components and API authorization restricts mutations to admin/operator.

**Gaps/problems:** This is a built-in rules-based advisor, not an external optimizer. Its effectiveness depends on representative live cache traffic and cluster state. The page handles initial list failure, but a polling failure after data has loaded does not clearly show a stale-data warning. No export or history of rule evaluations is exposed.

### 10. Audit Logs

**Existing:** `AuditLogs.jsx` uses the admin-only paginated `/audit-logs` endpoint with search, newest-first results and visible loading/error states. Login attempts are recorded explicitly and an audit filter records state-changing control-plane calls.

**Gaps/problems:** Cache reads/writes are explicitly excluded from audit logging to avoid a database operation per cache request; the page subtitle discloses this. Read-only GET activity is not audited by the filter. Audit write failures are logged and intentionally do not fail the original request, meaning an audit entry is not guaranteed when persistence fails. Later polling failures are hidden if old data exists.

### 11. Settings

**Existing:** `Settings.jsx` reads and updates cluster settings; the backend validates/applies settings and persists them. Settings cover failure detection intervals, replication factor, virtual nodes, memory limit, eviction policy, default TTL and dashboard refresh interval. Changes to ring parameters require confirmation.

**Gaps/problems:** The settings fetch runs in an effect before the role-based early return. A viewer therefore still makes a forbidden request, although the page presents the role warning. The UI has client-side validation, with authoritative backend validation expected on save. A failed reload after save is not separately represented; successful save refreshes shared cluster context.

## Frontend/backend connection review

- The route list and sidebar both contain the eleven requested sections. The pages call matching service/controller routes for the implemented data/actions; no general `/api/v1` versus controller-prefix mismatch was found in source.
- The Axios client uses `/api/v1`, adds the session token, and clears it after 401. Vite development proxy points at `localhost:8081`, consistent with the base Spring config.
- **Docker connection mismatch:** `management-api/src/main/resources/application.yml` configures port 8081. `docker-compose.yml` publishes `8080:8080`; `frontend/nginx.conf` proxies to `management-api:8080`; Prometheus targets `management-api:8080`. `docker/management-api.Dockerfile` has `EXPOSE 8080`, but that metadata does not override Spring's `server.port`. No `SERVER_PORT=8080` override was found in the Compose API environment. As configured, those container-to-API connections are likely broken unless overridden externally.
- Most pages do not indicate secondary polling failures. `usePolling` retains prior data on failure and stores an error, but pages commonly inspect the error only when no data exists. This makes a stale dashboard look live after an outage.
- Cache Explorer uses a direct component fetch rather than the common polling hook; it does not visibly distinguish initial loading from an empty result.
- No live push transport (SSE/WebSocket) exists; polling is intentionally documented in `usePolling.js`.

## REST API coverage and gaps

**Implemented API groups:**

- Auth: login, logout, current user.
- Cache: set/get/delete/exists/TTL/expire/locate/key search.
- Cluster: overview, node list/detail/add/remove/maintenance/resume, ring, health, events, gossip and failovers.
- Monitoring: overview, traffic, cache performance, memory, replication and events.
- Simulations: start/list/detail/restart/report.
- Advisor: list/get/status changes/run.
- Audit logs: search and pagination.
- Settings: get/update.
- Actuator: health, info, metrics and Prometheus exposure (per config).

No missing basic CRUD endpoint was identified for current dashboard controls. The following API capabilities are absent relative to broader dashboard/analytics expectations:

1. **Recovery aggregation endpoint:** no API combines durable failover history and simulations into time-range summary/trends; Recovery Analytics derives limited simulation-only averages in the browser.
2. **Historical monitoring query endpoint:** no durable time-range query for metrics. Current custom monitoring endpoints read bounded JVM-local samples. Prometheus is separately scrapeable, but the React app does not query Prometheus.
3. **Persistent failover history read path:** the failover repository/table is written to, but `/cluster/failovers` reads `rt.failover.history()`; no startup load from `FailoverHistoryRepository` is evident. Historical rows may therefore not be reflected after API restart.
4. **Exhaustive cache key browsing:** `/cache/keys` is a capped prefix search with a limit, without cursor/page pagination.
5. **Streaming updates:** no SSE/WebSocket endpoint; all dashboard updates depend on polling.

These are feature gaps, not claims that the existing matching routes are missing.

## Monitoring and metrics status

- `MetricsRecorder.java` records real cache gateway operations and sampled latency/timeline/hot-key information. Metrics are not fabricated for display; demo controls issue real cache requests and those requests enter the same recorder.
- `MonitoringService.java` derives dashboard views from node stats/heartbeats, engine state, replication stats and the recorder. Memory/state history is sampled every two seconds and held only in a bounded deque.
- `ClusterMetricsBinder.java` registers Micrometer function counters/gauges/timers, per-node MultiGauges refreshed every two seconds, and failover/detection/recovery duration timers. `/actuator/prometheus` is exposed and Prometheus has a scrape job.
- The dashboard consumes custom REST API payloads; there is no React-to-Prometheus query integration. Gauge/counter samples and history have different retention and semantics; dashboard latency percentiles come from `MetricsRecorder`'s sliding window.
- Prometheus config uses a five-second scrape interval, but the target port is affected by the Docker mismatch above. Actuator Prometheus is `permitAll` in Spring Security. It should only be reachable on a trusted network; Compose currently publishes the management API port to the host.
- Restarting the API resets `MetricsRecorder`, node history sampler and in-memory cluster event journal. Prometheus may retain its own scraped series in its volume, but the frontend does not read that store.

## Database, authentication and authorization

- Flyway migration creates user accounts, audit logs, simulation runs, failover history, recommendations and settings. Cache entries remain in memory by design.
- H2 is configured for dev/test and PostgreSQL for Docker. `PersistenceBridge` writes settings, failovers, simulation snapshots and recommendations. It reloads settings and recommendations; it does not load failover history or monitoring samples. Audit logs persist through `AuditService` and JPA.
- JWTs are stateless HS256 bearer tokens; frontend stores them in `sessionStorage`. Passwords are BCrypt hashed; roles are ADMIN, OPERATOR and VIEWER. Spring Security constrains cache writes, settings, node operations, simulation, advisor and audit endpoints by role.
- Login throttling is in-memory per username/IP. Logout is audit-only and client-side token removal; tokens are not revoked server-side until expiration.
- Base config contains development fallback secrets/passwords and simulation-enabled defaults. Seeder warns about the default admin password. Compose requires secrets via environment variables; avoid exposing development defaults in shared deployments.
- Public actuator endpoints include Prometheus. Verify network exposure in the intended deployment, especially because the Compose API port is host-published.

## Recommended implementation order

1. **Repair and verify Docker API reachability:** choose one internal port and align Spring, Compose, Nginx and Prometheus. Then verify API health through the frontend proxy and Prometheus scrape target.
2. **Fix dashboard trustworthiness:** surface polling errors/stale-data timestamps consistently on each page; add proper initial loading/error states to Cache Explorer; correct Replication's unconditional async explanation and zero-versus-unavailable metric display.
3. **Make persistence semantics explicit:** load persisted failover history at startup or make the API contract explicitly process-local; decide what monitoring retention is required and provide durable storage/querying if recovery analytics must survive restarts.
4. **Complete Recovery Analytics:** define whether the section covers simulations, production failovers, or both; add server-side aggregate/time-range endpoint and pagination if durable datasets are expected; clear stale detail during selection.
5. **Harden settings and auth UX:** avoid unauthorized settings fetches for viewers, show save/reload errors clearly, plan an operational password-change/rotation path and clarify stateless logout behavior.
6. **Expand operational observability:** decide whether React should query a backend metrics API or Prometheus directly through a secured proxy; add retention and time-range controls only after selecting the source of truth.
7. **Review demo workload behavior:** keep synthetic sample/traffic operations visibly labeled as generated workload and separate those periods when interpreting performance results.
8. **Run verification after approval:** execute existing Maven and frontend build/test coverage, add focused tests for the confirmed Docker port configuration and dashboard state failures, and exercise role-appropriate APIs. No tests were run during this read-only audit.

## Important source references

- Frontend route/sidebar: `frontend/src/routes/AppRoutes.jsx`, `frontend/src/components/common/Sidebar.jsx`
- Dashboard pages: `frontend/src/pages/`
- API client/services: `frontend/src/services/`, `frontend/src/hooks/usePolling.js`
- REST endpoints: `management-api/src/main/java/com/distributedcache/api/`
- Security: `management-api/src/main/java/com/distributedcache/security/SecurityConfig.java`
- Persistence bridge/migration: `management-api/src/main/java/com/distributedcache/persistence/PersistenceBridge.java`, `management-api/src/main/resources/db/migration/V1__init.sql`
- Metrics: `cache-core/src/main/java/com/distributedcache/monitoring/MetricsRecorder.java`, `cache-core/src/main/java/com/distributedcache/monitoring/MonitoringService.java`, `management-api/src/main/java/com/distributedcache/observability/ClusterMetricsBinder.java`
- Deployment: `management-api/src/main/resources/application.yml`, `docker-compose.yml`, `frontend/nginx.conf`, `docker/prometheus/prometheus.yml`, `docker/management-api.Dockerfile`
