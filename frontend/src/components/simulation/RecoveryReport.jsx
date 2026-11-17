import StatusBadge from '../common/StatusBadge';
import MetricCard from '../common/MetricCard';
import { fmtDateTime, fmtDuration, fmtNum } from '../../utils/format';

const duration = (value) => value == null ? 'N/A' : fmtDuration(value);

/** Everything on this card is copied from the measured report returned by the backend. */
export default function RecoveryReport({ report }) {
  if (!report) return <div className="state">The report is generated when the simulation finishes.</div>;
  const r = report;
  const consistent = r.consistency?.consistent;
  const phases = [r.detectionMs, r.failoverMs, r.recoveryMs];
  const measuredDurationTotal = phases.reduce((sum, value) => sum + (typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : 0), 0);
  const total = Math.max(1, measuredDurationTotal);
  const requestSuccess = typeof r.requestsTotal === 'number' && r.requestsTotal > 0
    && typeof r.requestsSucceeded === 'number' && r.requestsSucceeded >= 0 && r.requestsSucceeded <= r.requestsTotal
    ? (100 * r.requestsSucceeded) / r.requestsTotal
    : null;
  const seg = (ms, color) => typeof ms !== 'number' || !Number.isFinite(ms) || ms < 0 ? null
    : <div style={{ width: `${(100 * ms) / total}%`, background: color, minWidth: ms > 0 ? 3 : 0 }} title={fmtDuration(ms)} />;
  return (
    <div className="stack">
      <div className="row">
        <h2>Simulation {r.simulationId}</h2>
        <StatusBadge status={r.outcome === 'COMPLETED' ? 'COMPLETED' : 'FAILED'} label={r.outcome.replaceAll('_', ' ')} />
        <span className="muted small">node <b>{r.failedNode}</b> · {r.mode === 'CRASH' ? 'process crash' : 'network partition'}</span>
      </div>
      <div className="grid metrics">
        <MetricCard label="Failure detection" value={duration(r.detectionMs)} tone="orange" />
        <MetricCard label="Failover" value={duration(r.failoverMs)} tone="blue" />
        <MetricCard label="Recovery" value={duration(r.recoveryMs)} hint={`Sync ${duration(r.syncMs)}`} tone="green" />
        <MetricCard label="Request success" value={requestSuccess == null ? 'N/A' : `${requestSuccess.toFixed(2)}%`} hint={`${fmtNum(r.requestsSucceeded)} of ${fmtNum(r.requestsTotal)} measured requests`} tone={r.requestsFailed == null ? undefined : r.requestsFailed > 0 ? 'orange' : 'green'} />
        <MetricCard label="Failed requests" value={fmtNum(r.requestsFailed)} hint={`error rate ${r.errorRatePct == null ? 'N/A' : `${r.errorRatePct}%`}`} tone={r.requestsFailed ? 'red' : 'green'} />
        <MetricCard label="Served via fallback" value={fmtNum(r.requestsServedByFallback)} hint="primary timed out, replica answered" />
        <MetricCard label="Measured request rate" value={`${Number(r.requestsPerSecond || 0).toFixed(2)} /s`} hint="gateway counter delta / elapsed run time" />
        <MetricCard label="Average request latency" value={r.latencySamples ? `${Number(r.averageLatencyMs).toFixed(2)} ms` : 'Unavailable'} hint={`${fmtNum(r.latencySamples || 0)} retained gateway latency samples`} />
        <MetricCard label="P95 request latency" value={r.latencySamples ? `${Number(r.p95LatencyMs).toFixed(2)} ms` : 'Unavailable'} />
        <MetricCard label="P99 request latency" value={r.latencySamples ? `${Number(r.p99LatencyMs).toFixed(2)} ms` : 'Unavailable'} />
      </div>
      <div className="card">
        <h3 style={{ marginBottom: 8 }}>Failover and recovery verification</h3>
        <dl className="kv">
          <dt>Failover completion</dt><dd>{r.failoverCompletedAt > 0 ? `Completed at ${fmtDateTime(r.failoverCompletedAt)}` : 'Not confirmed'}</dd>
          <dt>Affected keys recorded</dt><dd>{r.affectedKeys ? fmtNum(r.affectedKeys.length) : 'Unavailable for this saved run'}</dd>
          <dt>Recovery status</dt><dd>{r.recoverySucceeded && r.consistency?.consistent ? 'Verified ACTIVE' : r.finalNodeStatus ? `Not verified · final node state ${r.finalNodeStatus}` : 'Unavailable for this saved run'}</dd>
        </dl>
        {r.affectedKeys?.length > 0 && <div style={{ overflowX: 'auto', marginTop: 12 }}>
          <table className="data-table"><thead><tr><th>Key</th><th>Original primary</th><th>Original replicas</th><th>New primary</th><th>Current replicas</th><th>Copies verified</th></tr></thead>
            <tbody>{r.affectedKeys.map((k) => <tr key={k.key}><td>{k.key}</td><td>{k.originalPrimary}</td><td>{k.originalReplicas?.join(', ') || 'None reported'}</td><td>{k.newPrimary || 'Unavailable'}</td><td>{k.currentReplicas?.join(', ') || 'Unavailable'}</td><td>{k.copiesVerified ? `${k.verifiedCopies}/${k.expectedCopies} verified` : `${k.verifiedCopies}/${k.expectedCopies} verified · incomplete`}</td></tr>)}</tbody>
          </table>
        </div>}
      </div>
      <div>
        <div className="small muted" style={{ marginBottom: 6 }}>Time split</div>
        <div style={{ display: 'flex', height: 16, borderRadius: 6, overflow: 'hidden', background: '#eceff5' }}>
          {seg(r.detectionMs, '#e8890c')}{seg(r.failoverMs, '#2563eb')}{seg(r.recoveryMs, '#22a35a')}
        </div>
        <div className="row small muted" style={{ marginTop: 6 }}>
          <span><span className="node-dot" style={{ background: '#e8890c' }} /> detection</span>
          <span><span className="node-dot" style={{ background: '#2563eb' }} /> failover</span>
          <span><span className="node-dot" style={{ background: '#22a35a' }} /> recovery</span>
        </div>
      </div>
      <div className="grid cols-2">
        <div className="card">
          <h3 style={{ marginBottom: 10 }}>Timeline</h3>
          <dl className="kv">
            <dt>Failure started</dt><dd>{fmtDateTime(r.failureStartedAt)}</dd>
            <dt>Failure detected</dt><dd>{fmtDateTime(r.failureDetectedAt)}</dd>
            <dt>Failover started</dt><dd>{fmtDateTime(r.failoverStartedAt)}</dd>
            <dt>Failover completed</dt><dd>{fmtDateTime(r.failoverCompletedAt)}</dd>
            <dt>Node restarted</dt><dd>{fmtDateTime(r.restartedAt)}</dd>
            <dt>Recovery completed</dt><dd>{fmtDateTime(r.recoveryCompletedAt)}</dd>
          </dl>
        </div>
        <div className="card">
          <h3 style={{ marginBottom: 10 }}>Replication &amp; consistency</h3>
          <dl className="kv">
            <dt>Keys promoted</dt><dd>{fmtNum(r.keysPromoted)}</dd>
            <dt>Entries re-replicated</dt><dd>{fmtNum(r.reReplicated)}</dd>
            <dt>Replication writes</dt><dd>{fmtNum(r.replicationSent)} sent · {fmtNum(r.replicationAcked)} acked · {fmtNum(r.replicationFailed)} failed</dd>
            <dt>Consistency check</dt><dd><StatusBadge status={consistent ? 'COMPLETED' : 'FAILED'} label={consistent ? 'CONSISTENT' : 'INCONSISTENT'} /></dd>
            <dt>Keys verified</dt><dd>{fmtNum(r.consistency?.keysChecked)} ({fmtNum(r.consistency?.consistentKeys)} fully consistent)</dd>
            <dt>Missing / stale copies</dt><dd>{fmtNum(r.consistency?.missingCopies)} / {fmtNum(r.consistency?.staleCopies)}</dd>
          </dl>
        </div>
      </div>
      {r.notes?.length > 0 && (
        <div className="card">
          <h3 style={{ marginBottom: 8 }}>Notes</h3>
          <ul style={{ margin: 0, paddingLeft: 18 }}>{r.notes.map((n, i) => <li key={i} className="small" style={{ marginBottom: 4 }}>{n}</li>)}</ul>
        </div>
      )}
    </div>
  );
}
