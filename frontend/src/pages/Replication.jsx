import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { Link } from 'react-router-dom';
import { RefreshCw } from 'lucide-react';
import PageHeader from '../components/common/PageHeader';
import MetricCard from '../components/common/MetricCard';
import DataTable from '../components/common/DataTable';
import LoadingState from '../components/common/LoadingState';
import ErrorState from '../components/common/ErrorState';
import ReplicationLagChart from '../components/charts/ReplicationLagChart';
import usePolling from '../hooks/usePolling';
import { monitoringService } from '../services/monitoringService';
import { useCluster } from '../context/ClusterContext';
import { fmtDateTime, fmtMs, fmtNum } from '../utils/format';

export default function Replication() {
  const { refreshMs } = useCluster();
  const rep = usePolling(() => monitoringService.replication(), refreshMs);
  if (rep.loading && !rep.data) return <LoadingState label="Loading replication measurements…" />;
  if (rep.error && !rep.data) return <ErrorState message={rep.error} onRetry={rep.reload} />;

  const r = rep.data;
  const s = r.stats;
  const total = (field) => r.perNode.length > 0 && r.perNode.every((node) => Number.isFinite(node[field]))
    ? r.perNode.reduce((sum, node) => sum + node[field], 0)
    : null;
  const expectedPrimaries = total('expectedPrimaryCount');
  const actualPrimaries = total('actualPrimaryCount');
  const expectedReplicas = total('expectedReplicaCount');
  const actualReplicas = total('actualReplicaCount');
  const missingReplicas = total('missingReplicaCount');
  const staleReplicas = total('staleReplicaCount');
  const latencyHasSamples = r.latencySamples?.length > 0;
  const scanMetadataAvailable = Number.isFinite(r.keysInspected) && Number.isFinite(r.sampleLimit)
    && Number.isFinite(r.nodesScanned) && Number.isFinite(r.nodesAttempted)
    && Array.isArray(r.perNode) && r.perNode.every((node) => typeof node.scanStatus === 'string');

  return (
    <>
      <PageHeader
        title="Replication"
        subtitle={`Replication factor ${r.replicationFactor} (${r.replicationFactor + 1} configured copies per key) · ${r.synchronous ? 'synchronous' : 'asynchronous'} mode`}
        actions={<button type="button" className="btn" onClick={rep.reload} disabled={rep.loading}><RefreshCw size={14} />Refresh</button>}
      />
      {rep.error && <div className="alert warn" style={{ marginBottom: 14 }}>Could not refresh replication data: {rep.error}. Showing the last successful response from {fmtDateTime(rep.lastSuccessAt)}. <button className="btn small" onClick={rep.reload}>Retry</button></div>}
      {!scanMetadataAvailable && <div className="alert error" style={{ marginBottom: 14 }}>
        The backend response is missing node scan metadata. Restart the management API from the current source with the `dev` profile, then refresh this page. No scan counts are available from this response.
      </div>}

      <div className={`alert ${r.synchronous ? 'warn' : 'info'}`}>
        {r.synchronous
          ? <>Synchronous mode is configured. The current engine waits up to 2 seconds for each replica task in turn, but does not fail the primary write when a replica task times out or fails. This is not a quorum or durability guarantee. <Link to="/settings">View cluster settings</Link>.</>
          : <>Asynchronous mode is configured. The primary write returns before replica tasks finish; a primary failure during that interval can lose an unreplicated write. <Link to="/settings">View cluster settings</Link>.</>}
      </div>

      <div className="stack" style={{ marginTop: 16 }}>
        <div className="grid metrics">
          <MetricCard label="Replica writes sent" value={fmtNum(s.sent)} hint="Cumulative since this API process started" />
          <MetricCard label="Acknowledged" value={fmtNum(s.acked)} tone="green" hint={`${fmtNum(s.applied)} applied · ${fmtNum(s.stale)} already current`} />
          <MetricCard label="Failed writes" value={fmtNum(s.failed)} tone={s.failed ? 'red' : undefined} hint="Replica calls that did not return a valid response" />
          <MetricCard label="Average ack latency" value={latencyHasSamples ? fmtMs(s.avgLatencyMs, 2) : 'Unavailable'} hint="Recent send-to-response time; includes applied, stale and failed calls" />
          <MetricCard label="P95 ack latency" value={latencyHasSamples ? fmtMs(s.p95LatencyMs, 2) : 'Unavailable'} hint="Recent send-to-response time" />
          <MetricCard label="Keys inspected" value={scanMetadataAvailable ? fmtNum(r.keysInspected) : 'Unavailable'} hint={scanMetadataAvailable ? `Sample limit ${fmtNum(r.sampleLimit)}` : 'Backend scan metadata is unavailable'} />
          <MetricCard label="Node scans" value={scanMetadataAvailable ? `${fmtNum(r.nodesScanned)} / ${fmtNum(r.nodesAttempted)}` : 'Unavailable'} hint="Successful scans / nodes attempted" />
          <MetricCard label="Expected primary copies" value={fmtNum(expectedPrimaries)} hint="Expected ring owners for inspected keys" />
          <MetricCard label="Observed primary copies" value={fmtNum(actualPrimaries)} hint="Live copies found at expected primary nodes" />
          <MetricCard label="Expected replica copies" value={fmtNum(expectedReplicas)} hint="Expected replica slots for inspected keys" />
          <MetricCard label="Observed replica copies" value={fmtNum(actualReplicas)} hint="Live copies found at expected replica nodes" />
          <MetricCard label="Missing replica copies" value={fmtNum(missingReplicas)} tone={missingReplicas ? 'red' : undefined} hint="Counted only for replica nodes whose scans succeeded" />
          <MetricCard label="Stale replica copies" value={fmtNum(staleReplicas)} tone={staleReplicas ? 'orange' : undefined} hint="Observed replica entries older than the newest scanned copy" />
        </div>
        <div className="muted small">Last successful replication refresh: {fmtDateTime(rep.lastSuccessAt)}{rep.loading ? ' · Refreshing…' : ''}. Counters and recent latency samples are process-local and reset when the API restarts.</div>

        {scanMetadataAvailable && !r.scanComplete && r.nodesScanned > 0 && <div className="alert warn">
          Partial scan: inspected {fmtNum(r.keysInspected)} keys from {fmtNum(r.nodesScanned)} of {fmtNum(r.nodesAttempted)} nodes. Actual counts are incomplete; missing replicas are counted only where the expected replica node responded.
        </div>}
        {scanMetadataAvailable && !r.scanComplete && r.nodesScanned === 0 && <div className="alert error">No node scans succeeded. Replication key counts are unavailable; zero must not be interpreted as an empty cache.</div>}
        {r.scanErrors?.length > 0 && <details className="card" style={{ padding: 12 }}>
          <summary style={{ cursor: 'pointer', fontWeight: 600 }}>Scan errors ({r.scanErrors.length})</summary>
          <div className="table-wrap" style={{ marginTop: 8 }}><table className="data"><thead><tr><th>Node</th><th>Node state</th><th>Error</th></tr></thead><tbody>{r.scanErrors.map((error) => <tr key={error.nodeId}><td>{error.nodeId}</td><td>{error.nodeState}</td><td>{error.message}</td></tr>)}</tbody></table></div>
        </details>}
        {r.truncated && <div className="alert warn">The scan found more keys than the displayed analysis limit. Counts below cover the first {fmtNum(r.sampleLimit)} sorted keys, not the full keyspace.</div>}

        <section className="card">
          <div className="card-title"><div><h2>Node scan results</h2><p className="muted small" style={{ marginTop: 4 }}>Each row reports the latest authenticated SCAN response from one configured node.</p></div><span className="muted small">{scanMetadataAvailable ? `${fmtNum(r.nodesScanned)} successful / ${fmtNum(r.nodesAttempted)} attempted` : 'Unavailable'}</span></div>
          {scanMetadataAvailable ? <div className="table-wrap"><table className="data"><thead><tr><th>Node ID</th><th>Scan status</th><th>Keys discovered</th><th>Error reason</th></tr></thead><tbody>
            {r.perNode.map((node) => <tr key={node.nodeId}><td><span className="mono">{node.nodeId}</span></td><td><span className={`badge ${node.scanStatus === 'SUCCESS' ? 'green' : 'red'}`}>{node.scanStatus}</span></td><td>{node.scanSucceeded ? fmtNum(node.keysDiscovered) : 'Unavailable'}</td><td>{node.errorReason || '—'}</td></tr>)}
          </tbody></table></div> : <div className="state">Per-node scan status is not present in the backend response. Restart the management API from the current source.</div>}
        </section>

        <div className="grid cols-2">
          <section className="card">
            <div className="card-title"><div><h2>Replica acknowledgement latency</h2><p className="muted small" style={{ marginTop: 4 }}>Elapsed time measured around each node replication request.</p></div><span className="muted small">{fmtNum(r.latencySamples?.length || 0)} recent outcomes</span></div>
            {latencyHasSamples ? <ReplicationLagChart samples={r.latencySamples} /> : <div className="state">No replica responses have been measured yet.</div>}
          </section>
          <section className="card">
            <div className="card-title"><div><h2>Expected ownership vs observed copies</h2><p className="muted small" style={{ marginTop: 4 }}>Counts apply to the inspected key sample.</p></div></div>
            {scanMetadataAvailable && r.keysInspected > 0 ? <ResponsiveContainer width="100%" height={270}>
              <BarChart data={r.perNode} margin={{ top: 6, right: 8, left: -14, bottom: 0 }} barGap={2}>
                <CartesianGrid stroke="#e8ecf4" vertical={false} />
                <XAxis dataKey="nodeId" tick={{ fontSize: 11 }} />
                <YAxis allowDecimals={false} tick={{ fontSize: 11 }} />
                <Tooltip formatter={(value, name) => [value, ({ expectedPrimaryCount: 'Expected primary slots', actualPrimaryCount: 'Observed primary copies', expectedReplicaCount: 'Expected replica slots', actualReplicaCount: 'Observed replica copies' })[name] || name]} />
                <Legend wrapperStyle={{ fontSize: 11 }} />
                <Bar dataKey="expectedPrimaryCount" name="Expected primary" fill="#93b4f4" isAnimationActive={false} />
                <Bar dataKey="actualPrimaryCount" name="Observed primary" fill="#2563eb" isAnimationActive={false} />
                <Bar dataKey="expectedReplicaCount" name="Expected replica" fill="#b9c9e8" isAnimationActive={false} />
                <Bar dataKey="actualReplicaCount" name="Observed replica" fill="#526fba" isAnimationActive={false} />
              </BarChart>
            </ResponsiveContainer> : !scanMetadataAvailable
              ? <div className="state">Scan results are unavailable from this backend response.</div>
              : r.cacheEmpty
              ? <div className="state">No cache entries available for replication analysis.</div>
              : r.scanComplete
                ? <div className="state">No live keys were found in the completed node scans.</div>
                : <div className="state">No keys were observed in the successful scans; the cache cannot be confirmed empty because some node scans failed.</div>}
            <p className="muted small" style={{ marginTop: 8 }} title="Expected values come from the consistent hash ring. Observed values count live entries found on scanned nodes at the owners the ring currently expects.">
              Expected = ring placement. Observed = actual live entries returned by node scans. Missing/stale values are meaningful only within scan coverage.
            </p>
          </section>
        </div>

        <section className="card">
          <div className="card-title"><div><h2>Failed replication events</h2><p className="muted small" style={{ marginTop: 4 }}>Most recent first · retained in this process only</p></div></div>
          <DataTable rows={r.failures} rowKey={(failure) => `${failure.time}-${failure.key}-${failure.node}`} empty="No failed replication calls recorded."
            columns={[
              { key: 't', header: 'Time', render: (failure) => fmtDateTime(failure.time) },
              { key: 'k', header: 'Key', render: (failure) => <span className="mono">{failure.key}</span> },
              { key: 'n', header: 'Replica', render: (failure) => failure.node },
              { key: 'd', header: 'Elapsed', render: (failure) => fmtMs(failure.latencyMs, 2) },
              { key: 'e', header: 'Error', render: (failure) => <span className="muted small">{failure.error}</span> },
            ]} />
        </section>
      </div>
    </>
  );
}
