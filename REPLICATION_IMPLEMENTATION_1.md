# Replication Module — Implementation Task 1

**Date:** 2026-11-13  
**Scope:** Scan accuracy, expected-versus-observed ownership, acknowledgement latency, mode messaging, and Replication dashboard states. Existing services and the `/monitoring/replication` API were extended; no second monitoring system or new endpoint was added.

## Final fix and verification

### Root cause of the reported screen

The API serving `localhost:8081` initially returned the older replication response shape: `keysSampled`, `primaryKeys`, and `replicaKeys` only. It did not return `keysInspected`, `sampleLimit`, `nodesScanned`, `scanErrors`, `latencySamples`, or the measured RPC latency fields expected by the updated React page. That response-contract mismatch explained the dashes and unavailable latency in the screenshot. The old listener also owned local node ports 7001-7006. After preserving a backup and receiving approval, that process was stopped and replaced by the current dev-profile build; the restart and restoration results are recorded at the end of this report.

For genuine unavailable nodes, `NodeClient.scan()` uses the configured cluster secret and socket timeout. A node that does not answer returns a timeout (`NodeUnavailableException`); an authentication mismatch returns the node's `ERR unauthorized` protocol response; malformed SCAN headers/entries are reported as protocol/parse errors. The updated backend now retains one outcome per registered node instead of silently collapsing these failures into an empty search result. A deliberate isolated-node check produced `timeout after 5000ms` for that node while the other five scans succeeded.

The latency was unavailable on the old API because its response had only the old lag sample schema and lacked `latencySamples`/`avgLatencyMs`/`p95LatencyMs`. On the current source, an actual SET produced two APPLIED replica acknowledgements and two measured monotonic RPC durations. No latency values are filled in when no real samples exist.

### Files changed for the final fix

- `cache-core/src/main/java/com/distributedcache/monitoring/MonitoringService.java`
- `frontend/src/pages/Replication.jsx`
- `cache-core/src/test/java/com/distributedcache/cluster/ReplicationMonitoringTest.java`
- `management-api/src/test/java/com/distributedcache/api/ApiSmokeTest.java`
- `docs/API.md`
- `REPLICATION_IMPLEMENTATION_1.md`

`npm.cmd run build` also refreshed generated output: `frontend/dist/index.html`, `frontend/dist/assets/index-DE-czShR.css`, and `frontend/dist/assets/index-DbsxrEww.js`.

### End-to-end run on the current source

For the initial end-to-end check, the packaged current source was started with the local `dev` profile on API port `8181` and embedded node ports `7101-7106` so the pre-existing `8081`/`7001-7006` process and its in-memory data were not disturbed. After approval, the current source was then started on the standard ports and the backed-up entry was restored.

- Six of six embedded nodes were ACTIVE at the beginning.
- Inserted `product:101` with `iPhone 17 Pro` through the cache API. The subsequent API read returned HIT and the same value.
- The entry's current primary was `node-5`; replicas were `node-2` and `node-4`. Authenticated internal SCANs on all three nodes found the exact expected value.
- The monitoring scan was complete at 6/6 nodes and inspected 1 key (sample limit 5,000). Expected/observed counts were primary 1/1 and replicas 2/2. The manager recorded 2 acknowledgements and 2 actual latency samples; observed average latency was 2.2854 ms and P95 was 2.2997 ms.
- Deleting the entry returned `deleted: true`. Authenticated scans found delete tombstones on the primary and both replicas; the monitoring endpoint then reported a complete scan, `cacheEmpty: true`, and 0 live keys inspected.
- An ADMIN partition simulation isolated `node-6`. The real monitoring endpoint returned a partial 5/6 scan, marked `node-6` FAILED with `Node node-6@127.0.0.1:7106 unavailable: timeout after 5000ms`, and did not claim the cache was empty. After UNISOLATE, node-6 returned ACTIVE with a successful recovery record and scan coverage returned to 6/6.

The existing simulation detail/list endpoints also returned HTTP 500 during this check because some controller `@PathVariable`/`@RequestParam` arguments omit explicit names and the application is not compiled with Java parameter metadata. This did not affect scan failure reporting or node recovery; the node was restored through its authenticated internal protocol. It remains an unrelated API issue.

### Verification results

- `mvn.cmd -pl cache-core "-Dtest=ReplicationMonitoringTest" test`: **6 passed, 0 failed**.
- Focused `ApiSmokeTest#replicationMonitoringReportsScanCoverageToAuthenticatedViewers`: **1 passed, 0 failed**. Confirms the additive per-node scan fields and authenticated Viewer access; unauthenticated access remains denied.
- `npm.cmd run build` after the final UI adjustment: **passed**. Vite emitted its existing large bundle warning (773.43 kB minified).
- The above HTTP and direct node-protocol sequence was run against six real embedded `NodeServer` instances from the current packaged source. At that stage the pre-existing process at port 8081 was not restarted or modified.

### Remaining limitations

- The dashboard at port 5173 still proxies to the pre-existing backend on port 8081. It must be restarted from the updated source before the new metadata and latency fields can appear there. Restart was not performed because it would interrupt the existing live cache process and its in-memory data.
- The direct runtime test used an alternate port set; it verified the current backend and UI response contract, not browser rendering in the user's existing Vite session.
- Replication counters and latency samples remain process-local and reset on API restart.
- The scan still reads each node's full snapshot before selecting the 5,000-key analysis sample.
- The simulation controller's unnamed path/query argument issue noted above remains outside this fix.

## Files changed

- `cache-core/src/main/java/com/distributedcache/monitoring/MonitoringService.java`
- `cache-core/src/main/java/com/distributedcache/replication/ReplicationManager.java`
- `management-api/src/main/java/com/distributedcache/observability/ClusterMetricsBinder.java`
- `frontend/src/pages/Replication.jsx`
- `frontend/src/components/charts/ReplicationLagChart.jsx`
- `cache-core/src/test/java/com/distributedcache/cluster/ReplicationMonitoringTest.java`
- `management-api/src/test/java/com/distributedcache/api/ApiSmokeTest.java`
- `docs/API.md`
- `REPLICATION_IMPLEMENTATION_1.md`

## APIs modified

No endpoint was added. `GET /api/v1/monitoring/replication` now includes:

- Scan coverage: `keysInspected`, `sampleLimit`, `truncated`, `cacheEmpty`, `scanComplete`, `nodesAttempted`, `nodesScanned`, `nodesUnreachable`, and `scanErrors`.
- Per-node expected and observed counts: `expectedPrimaryCount`, `expectedReplicaCount`, `actualPrimaryCount`, `actualReplicaCount`, `missingReplicaCount`, `staleReplicaCount`, and `scanSucceeded`.
- Recent `latencySamples`, whose result is `APPLIED`, `STALE`, or `FAILED` and whose `latencyMs` is measured around the replica RPC using `System.nanoTime()`.
- Average/P95/last latency and recent sample count in replication statistics. Existing `primaryKeys`, `replicaKeys`, `keysSampled`, and lag-stat fields remain as compatibility fields; the per-node aliases mean expected sampled ownership.

The existing authenticated node `SCAN` operation was reused. The response contains counts and scan errors, not cache values.

## Features completed

- The service concurrently scans registered, non-removed nodes with Java virtual threads instead of using `CacheRouter.searchKeys()`, whose scan errors were swallowed. Each node scan has a bounded wait and failed or timed-out scans are reported. Empty results are marked as an empty cache only when all node scans completed. Partial and total scan failures carry coverage and node error details.
- Analysis is bounded to the first 5,000 sorted unique live keys. The API reports the limit and whether additional keys were found. Counts are explicitly sample-scoped.
- Expected ring slots are separate from copies actually found on their expected owners. A missing replica is counted only when that replica node's scan succeeded. Stale replicas are found by comparing observed copies to the newest scanned copy. Observed counts include found stale copies; the stale count identifies that subset.
- Send-to-response durations now use a monotonic clock. APPLIED and STALE are acknowledged responses; FAILED is a failed call. Average and nearest-rank P95 use the bounded recent outcome window, including failures. The chart displays the actual RPC latency and response result rather than entry age.
- Micrometer now exposes acknowledged, applied and stale counters plus average/P95 RPC latency gauges. Existing metric names remain available, with the P95 lag metric's description clarified to say RPC latency.
- The page distinguishes empty, partial, failed and stale scan/poll results; shows the scan limit and coverage; compares expected and observed bars; explains the count semantics; displays last successful refresh time; and handles zero latency samples as unavailable.
- The mode banner reads the backend's configured mode. For synchronous mode it explicitly states that the current engine waits up to two seconds for each replica task, but still does not fail the primary write on replica timeout/failure. It does not claim quorum durability.
- Existing Viewer read access and JWT protection remain in place. No cache values are returned by the new scan fields.

## Tests run

- `ReplicationMonitoringTest`: **6 passed** after the concurrent bounded scan change. Covers empty cache versus scan errors, expected and actual counts, partial and total scan failure, missing copies, stale copies, and APPLIED/STALE/FAILED latency samples.
- Focused `ApiSmokeTest#replicationMonitoringReportsScanCoverageToAuthenticatedViewers`: **1 passed** after the concurrent bounded scan change. Covers unauthenticated denial and Viewer access to the scan/count DTO.
- Focused existing `ClusterIntegrationTest` distribution, primary failover, crashed-node recovery, maintenance resume, and settings rebalancing methods: **5 passed**.
- Frontend `npm.cmd run build`: **passed**. Vite still reports its existing large JavaScript chunk warning (about 771 kB minified).

These were focused tests, not the full Maven test suite. The prior audit recorded an existing `/actuator/prometheus` MockMvc test failure (`No static resource actuator/prometheus`); that endpoint test was not rerun or repaired in this task. Prometheus runtime scraping therefore remains unverified.

## Known limitations and remaining work

- Scanning reads each non-removed node's full snapshot before applying the 5,000-key analysis limit. Large nodes may make this monitoring request expensive. Counts are a deterministic sample when truncated.
- On partial scans, observed counts are lower-bound observations. Expected counts are for keys found by successful scans; unseen keys on failed nodes cannot be included. The UI shows this limitation and does not treat a failed scan as an empty cache.
- Latency samples and counters remain process-local, bounded, and reset on API restart. This task did not add durable replication history.
- Synchronous mode remains a wait policy rather than a success-enforcing quorum policy: replica failures/timeouts are still not propagated as primary write failure. Runtime mode changes are also unsupported; the banner reports the mode from configuration.
- The page does not add history search/pagination/export, an operator-triggered consistency-verification endpoint, or controls for node membership. Existing `ConsistencyVerifier` remains internal to simulation/reporting paths.
- No browser session was run. Responsive rendering, actual local cluster scans, sync-mode banner behavior, and the UI's partial/failure states need manual verification.

## Manual verification steps

1. Start the management API and its local cache nodes, then start the Vite frontend and sign in as an authorized user.
2. Open `/replication`. With an empty cache and healthy nodes, confirm all nodes scan successfully and the empty-cache message appears.
3. Insert several entries through Cache Explorer, wait for replica writes, then refresh Replication. Compare inspected count, expected slots, observed primary/replica copies and missing/stale counts.
4. In an isolated development environment, simulate one node becoming unavailable. Confirm partial coverage and node-specific scan errors appear, and zero is not presented as an empty cache. Restore the node and check recovery through Cluster Nodes.
5. Check the latency chart's APPLIED/STALE/FAILED outcomes and compare its summary to the API response. Confirm that no raw cache values appear in the replication response.
6. Repeat with `cluster.sync-replication` set to true and restart the API. Confirm the banner describes the current limited wait behavior without claiming durable acknowledgement. Do not treat this mode as quorum durability.
7. Verify anonymous requests to `/api/v1/monitoring/replication` receive 401 and authenticated Viewer requests remain read-only.

No manual browser/runtime verification was performed during the initial implementation. See the approved restart verification below for the subsequent Vite-proxied UI contract check.

## Approved restart and cache restoration

- Preserved and validated `%TEMP%\distributed-cache-pre-restart-backup-20261113-192427.json`. It contains the full value for `product:101` with TTL and ownership metadata. All six old nodes were ACTIVE and their combined physical entry count was three. Since the old API lacked scan completeness metadata, the export remains best effort.
- Stopped only the approved old API process (PID 11180), which also owned all six embedded node listeners. Packaged the current source and started it with `--spring.profiles.active=dev`. The API became healthy; six ACTIVE nodes listened on ports 7001-7006.
- Restored the backed-up entry once through the authenticated cache API. A GET returned HIT with the same value. Authenticated internal SCANs verified the value on primary `node-5` and replicas `node-2` and `node-4`.
- The updated monitoring endpoint returned `keysInspected: 1`, `sampleLimit: 5000`, `nodesScanned: 6`, a complete scan, 2 ACKs, and 2 measured latency samples (average 2.52205 ms, P95 2.5308 ms). Expected and observed counts matched: primary 1/1 and replicas 2/2.
- The same authenticated API through the Vite proxy at port 5173 returned all scan/latency fields and per-node scan statuses. This satisfies the Replication component's `scanMetadataAvailable` condition, which hides the backend-version warning. A rendered DOM check was not completed: a temporary headless browser could not stay running because its GPU process failed. The already-open page should poll the updated response; refresh if the warning remains visible.
- Packaging with `mvn.cmd -pl management-api -am -DskipTests package` passed. No application source files changed during the restart task; only build outputs and this report were updated.
