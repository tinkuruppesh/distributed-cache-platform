# Replication Module Audit

**Date:** 2026-11-13  
**Scope:** Static audit of the Replication page, related settings UI, cache-core replication/routing/recovery, monitoring API, Micrometer/Prometheus configuration, authorization, and relevant tests. No application source files were changed. The report is the only file added for this audit.

## Executive summary

The cache engine performs real replica writes over its authenticated node protocol. Replica nodes return `APPLIED` or `STALE`, the manager records those responses and failures, and the engine reconciles copies during failover, membership changes, and recovery. Focused integration tests run for this audit verified normal replication, failover, returning-node recovery, maintenance resume, and replication-factor rebalancing.

The Replication dashboard is much narrower than the engine. Its per-node chart counts **expected owners for a bounded sample of live keys**, not actual copies stored on each node. `Keys inspected: 0` means the scan returned no unique live keys, but a swallowed scan failure can produce the same result. The page does not expose replica-value verification, durable history, search/filter/pagination, export, or configuration controls. Replication factor is managed on Settings; synchronous mode is only set through application configuration and has no runtime management UI/API. The page's explanatory banner always says asynchronous even when the API says synchronous.

## Classification by requirement

| # | Requirement | Classification | Findings |
|---|---|---|---|
| 1 | Replication overview and configuration | **PARTIALLY IMPLEMENTED** | `/monitoring/replication` reports configured factor, sync mode, counters, recent lag/failures and sampled counts. The page has summary cards, but its banner always describes asynchronous mode, even when `synchronous` is true. Configuration is not managed from this page. |
| 2 | Primary and replica topology visualization | **PARTIALLY IMPLEMENTED** | A stacked bar chart presents per-node primary/replica series. It does not show ownership links or topology edges; the ring and virtual-node view is in the separate Hash Ring page. |
| 3 | Actual primary and replica key counts per node | **PARTIALLY IMPLEMENTED** | Current counts are calculated from keys returned by a scan and the ring preference list. They are expected ownership counts for the scanned key sample, not counts of copies actually stored on each node. The sample is capped at 5,000 keys. |
| 4 | Replication activity and history | **PARTIALLY IMPLEMENTED** | The page shows recent failures and successful-write lag samples. Counters and bounded samples live only in process memory; there is no durable replication history or unified activity table. |
| 5 | Replication lag monitoring | **PARTIALLY IMPLEMENTED** | Lag samples use actual acknowledgements, but the value is calculated as current time minus the entry's `updatedAt`, not elapsed time from the replication send to response. It includes executor queue delay and is sensitive to clock skew between nodes. The chart shows at most 120 samples; aggregate stats use a bounded 600-sample window. |
| 6 | Actual replica data verification | **PARTIALLY IMPLEMENTED** | `ConsistencyVerifier` scans real node entries and checks whether expected owners contain the newest version. It is used by simulation reporting, but there is no dedicated replication API/page action to run verification or display per-key primary/replica value/version differences. Its comparison is version-order based, not a returned-value comparison UI. |
| 7 | Replication failure handling | **COMPLETE AND VERIFIED** | Node replica write failures are counted and retained in a bounded in-memory list. Failover removes the failed node from the ring, promotes surviving ownership and re-replicates. The focused real-node failover tests passed. Async writes can still be lost if a primary fails before its replica write completes; this is documented behavior. |
| 8 | Replica synchronization and recovery | **COMPLETE AND VERIFIED** | `RebalanceService` reconciles newest entries; `RecoveryManager` uses a candidate-ring bulk pass followed by a delta pass before marking a returning node active. Focused recovery/resume tests passed. |
| 9 | Replication configuration management | **PARTIALLY IMPLEMENTED** | ADMIN can change replication factor in Settings; the backend validates and rebalances it, and an integration test passed. The page does not link to this configuration. Synchronous/asynchronous mode is present in config and the monitoring response, but has no Settings field or runtime API to change it. |
| 10 | Search, filtering, sorting and pagination | **NOT IMPLEMENTED** | The Replication page has no search/filter/sort/page controls. The failures table is a fixed recent list. |
| 11 | Export functionality | **NOT IMPLEMENTED** | No export control or export API is present on the Replication page. |
| 12 | Prometheus and Micrometer metrics | **PARTIALLY IMPLEMENTED** | Micrometer exposes replication sent, failed and P95 lag metrics. It does not expose acked/applied/stale counts, average lag, recent lag samples, verification results, or per-node ownership/copy counts. The audit's API smoke test failed on its `/actuator/prometheus` assertion: MockMvc returned 500 because no resource handler was found for that path. The endpoint therefore was not verified by this run. |
| 13 | JWT authentication and role permissions | **IMPLEMENTED BUT NOT VERIFIED** | `SecurityConfig` restricts API reads to authenticated ADMIN/OPERATOR/VIEWER roles; the frontend uses the existing authenticated Axios client. Settings writes are ADMIN-only. The Viewer/settings restriction test passed, but the `/monitoring/replication` endpoint was not directly exercised for each role in this audit. |
| 14 | Functional controls and dialogs | **PARTIALLY IMPLEMENTED** | Replication.jsx contains no page-level buttons, forms, dropdowns or dialogs; it relies on automatic polling and chart/table rendering. There are no dead page buttons, but required operational/filter/export controls do not exist. Settings' Apply and confirmation actions are wired to `PUT /settings`; the replication-factor form has no client-side range validation and relies on backend validation. |
| 15 | Backend/frontend integration | **IMPLEMENTED BUT NOT VERIFIED** | `monitoringService.replication()` calls the matching authenticated `/monitoring/replication` endpoint, and the page consumes its response shape. The Vite production build passed, but no browser/manual runtime check or dedicated API test for this endpoint was run. The page does not display a stale-data warning after a failed poll when prior data exists. |

## Findings and answers to the specific questions

### Why does “Keys inspected” show 0?

`MonitoringService.replicationView()` calls `CacheRouter.searchKeys("", 5000)`. That scan collects entries from reachable ACTIVE ring members, filters tombstones, deduplicates keys and returns at most 5,000 keys. Therefore 0 can mean the live cache has no entries. However, `CacheRouter.searchKeys()` catches and ignores scan exceptions and continues, while the API does not return scan errors or an “incomplete” flag. Consequently, 0 can also mean that no scan succeeded. The UI currently cannot distinguish those cases.

### Why can the “Primary vs replica keys per node” chart look empty?

The backend initializes a row per ring node, then increments the counts only for each inspected key. If `keysSampled` is zero, every bar is zero-height. This produces a visually blank chart rather than an explanatory no-data state. If keys were scanned, counts describe the ring's **expected primary/replica assignment for those keys**, not observed physical copies.

### Are acknowledgements genuine?

Yes, the ACK counter is based on a real node response. `NodeClient.repl()` parses the response returned by `NodeServer` after `CacheStore.applyReplica()` executes. `APPLIED` increments applied and acked; `STALE` increments stale and acked. A network/protocol runtime failure increments failed. A stale response means the node responded and already held an equal/newer version; it is not a failed write. The name “acknowledged” should be explained accordingly.

### Are lag measurements actual?

They are based on actual replica responses, but the reported `lagMs` is not a measured send-to-ack duration. It is `System.currentTimeMillis() - entry.updatedAt()` when the response is processed. It approximates entry age at acknowledgement and includes queueing delay; if primary and manager clocks differ, it can be distorted. The chart comment's “time between primary write and replica acknowledgement” overstates what the code directly measures.

### Can primary and replica values be retrieved and compared?

There is no management endpoint or Replication page action for retrieving each selected owner's copy and comparing values. The internal `ConsistencyVerifier` scans node snapshots and checks expected owners against the newest `CacheEntry` ordering. Tests can inspect node stores directly. The verifier is used in simulation reporting, not exposed as an operator-facing replication verification operation.

### Is history durable?

No. `ReplicationManager` keeps counters in `LongAdder`s, up to 600 lag samples, and up to 100 failures in concurrent deques. These are lost when the API process restarts. The current schema/persistence bridge does not persist replication samples or failures.

### Does failure/recovery synchronize replicas?

Source and tests show that failure handling removes the failed node from the live ring, then reconciles surviving entries against new owners. Returning nodes move through RECOVERING and are synchronized in bulk and delta passes before ACTIVE. The focused failover/recovery tests passed in this audit. This verifies the exercised test scenarios, not all network partitions, crash timing windows or production failure modes.

## Interactive control audit

| Surface | Controls inspected | Result |
|---|---|---|
| Replication page | No buttons, forms, tabs, filters or dialogs; `usePolling` refreshes data automatically. | Nothing is visibly wired to a fake mutation. Missing refresh, filtering and export controls are feature gaps, not dead visible buttons. |
| Replication lag chart | Recharts line, axes and tooltip. | Displays supplied samples; it has no click action or range selector. No explicit empty/error/stale state exists for a polling error after initial data has loaded. |
| Primary/replica chart | Recharts stacked bars, legend and tooltip. | Renders `r.perNode`; there is no zero-data explanation or distinction between expected ownership and actual copies. |
| Failure table | `DataTable` with time/key/node/error columns. | Displays the API's recent failure array. It has no search, sort, page or export controls. |
| Settings page | Numeric fields, eviction select, Apply and confirmation dialog. | State handlers and save/cancel/confirm handlers exist. Replication factor is persisted and triggers rebalance. No field changes sync replication mode. Client-side checks omit replication-factor bounds; backend validation remains authoritative. |
| Authentication | Axios token attachment and Spring Security role matchers. | Replication API GET is protected for authenticated roles. Mutating cluster settings is ADMIN-only. No replication-specific action is present on the page. |

## Existing APIs and missing API surface

Existing:

- `GET /api/v1/monitoring/replication`: factor, synchronous flag, stats, up to 120 lag samples, up to 50 failures, sampled per-node expected key counts and keys-sampled count.
- `GET /api/v1/settings` and `PUT /api/v1/settings`: settings view and ADMIN-controlled validated update; changing factor triggers rebalance.
- `GET /api/v1/cluster/nodes` and `GET /api/v1/cluster/hash-ring`: actual node and ring data, outside the Replication page.
- `GET /api/v1/cluster/failovers` and node detail: failover/recovery context, outside the Replication page.

No existing replication-specific API was found for per-node actual stored-copy counts, completeness/errors of node scans, actual per-key replica comparison, durable replication history, history search/pagination/export, or runtime sync-mode changes. Avoid duplicating the existing ring, node, settings and monitoring APIs when implementing those gaps.

## Required work before implementation

### Backend

1. Make `replicationView` distinguish an empty keyspace from incomplete/unreachable scans; include scan coverage and sample/truncation metadata.
2. Separate expected placement counts from actual stored-copy counts. If actual counts are required, collect them from each node's scan and classify each returned key against the ring; expose limits and scan errors.
3. Measure send-to-ack elapsed time with a monotonic clock and document it as operation latency/lag precisely. Record send/ack timestamps and result semantics.
4. Expose an authorized consistency verification operation using the existing verifier and a result DTO that includes missing/stale/unreachable counts. Decide whether values should ever be returned; a compare result usually needs versions/checksums, not raw values.
5. Persist history only if durable audit/history is a requirement; add migration/entity/repository/retention policy rather than writing cache values into SQL.
6. Correct synchronous replication semantics: collect all outcomes, enforce a defined acknowledgement policy/time budget, and return/report failure when the configured policy is not met. Current code waits on futures but ignores timeout/errors and still returns the primary write result.
7. Add missing Micrometer counters/gauges/timers only after defining exact semantics; maintain one source of truth with `ReplicationManager`.
8. Add tests for scan failures, genuine APPLIED/STALE/FAILED outcomes, lag calculation, actual copy counts, verifier behavior, sync timeout/failure behavior, authorization and persistence restart behavior.

### Frontend

1. Correct the mode-sensitive banner and distinguish configured replication factor from successful physical copies.
2. Label current sampled counts as expected ownership, show sample size/limit and use an explicit empty state for zero keys. If scans are partial, show a warning.
3. Present actual copy counts/verification only after the backend provides them; never infer they equal expected counts.
4. Add per-request loading/error/stale states and last successful refresh time to the replication panel.
5. Add a recent operation/history view with filtering, sorting, pagination and export only when backed by the API.
6. Reuse Settings and existing cluster pages for membership/configuration; link to them rather than duplicating controls. Add a sync-mode control only after a supported, validated backend API exists.
7. Add role-aware verification actions if implemented; keep viewer access read-only.

### API contract

Prefer evolving the current replication DTO with explicitly named fields (for example `expectedOwnerCounts`, `actualStoredCopyCounts`, `keysInspected`, `scanComplete`, `scanErrors`, `historyRetention`) rather than adding overlapping monitoring endpoints. Add a verification endpoint and history endpoint only if they have distinct operator/API requirements. Protect state-changing operations with the existing role policy and do not expose raw values by default.

## Recommended implementation order

1. Fix ambiguity first: return scan coverage and make zero/no-data/error visible; correct labels and the sync-mode banner.
2. Define and implement accurate actual copy counts plus tests; preserve sampled expected ownership as a separately labeled measure.
3. Fix lag semantics and add APPLIED/STALE/failure tests.
4. Expose and test consistency verification without returning cache values unnecessarily.
5. Decide and implement sync replication acknowledgement policy; document async loss window.
6. Add a durable history store only if required, including retention and migration tests.
7. Expand Micrometer metrics from the defined semantics and verify the Prometheus scrape endpoint in the local runtime and test setup.
8. Add frontend history controls and export backed by tested API contracts; then run responsive/accessibility/manual authorization checks.

## Verification performed

- Frontend: `npm.cmd run build` — **passed**. Vite emitted the existing large-chunk warning (main JS bundle about 766 kB minified).
- Backend integration: focused `ClusterIntegrationTest` methods for distribution, failover, crash recovery, maintenance resume and settings rebalancing — **5 passed, 0 failed**.
- Backend API: focused `ApiSmokeTest` run — Viewer read-only test **passed**; `monitoringAndClusterEndpointsReturnRealData` **failed** at its existing `/actuator/prometheus` assertion (`ApiSmokeTest.java:110`), receiving HTTP 500. The test log reports `No static resource actuator/prometheus`. This means Prometheus endpoint availability was not verified by this run.
- No UI browser test was run. No source code was changed and no runtime database or cluster configuration was changed.
