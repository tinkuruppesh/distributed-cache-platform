import StatusBadge from '../common/StatusBadge';
import { fmtBytes, fmtDateTime, fmtDuration, fmtNum, fmtPct } from '../../utils/format';

export default function NodeDetails({ detail }) {
  if (!detail) return null;
  const n = detail.node;
  const stateEvent = detail.recentEvents.find((event) => event.time >= n.stateSince && event.type.startsWith('NODE_'));
  return (
    <div className="stack">
      <dl className="kv">
        <dt>Node ID</dt><dd><b>{n.id}</b></dd>
        <dt>Endpoint</dt><dd className="mono">{n.host}:{n.port}</dd>
        <dt>Status</dt><dd><StatusBadge status={n.state} /> <span className="muted small">since {fmtDateTime(n.stateSince)}</span>{stateEvent && <div className="muted small">Reason: {stateEvent.message}</div>}</dd>
        <dt>Primary ownership</dt><dd>{n.primaryOwnershipPct}% of the hash space</dd>
        <dt>Primary / replica keys</dt><dd>{detail.keysSampled == null ? 'Role counts unavailable' : `${fmtNum(detail.primaryKeys)} primary and ${fmtNum(detail.replicaKeys)} replica keys in the current sample (${fmtNum(detail.keysSampled)} keys examined)`}</dd>
        <dt>Uptime</dt><dd>{fmtDuration(n.uptimeMs)}</dd>
        <dt>Last heartbeat</dt><dd>{fmtDateTime(n.lastHeartbeatAt)} ({fmtDuration(n.lastHeartbeatAgoMs)} ago, RTT {n.rttMs} ms, missed beats {n.missedBeats})<div className="muted small">Heartbeat interval {fmtNum(detail.heartbeatIntervalMs)} ms · failure timeout {fmtNum(detail.failureTimeoutMs)} ms</div></dd>
        <dt>Heartbeat status</dt><dd>{detail.heartbeatIntervalMs == null || detail.failureTimeoutMs == null ? 'Threshold unavailable' : n.lastHeartbeatAgoMs > detail.failureTimeoutMs ? 'Past failure timeout' : n.lastHeartbeatAgoMs > 2 * detail.heartbeatIntervalMs ? 'Late' : 'Within heartbeat interval'}<div className="muted small">Failure state is determined by backend heartbeat/failure detection, not this display.</div></dd>
        <dt>Memory</dt><dd>{fmtBytes(n.usedBytes)} used of {fmtBytes(n.maxBytes)} ({n.maxBytes > 0 ? fmtPct(n.memoryPct) : 'Unavailable'}); {fmtBytes(Math.max(0, n.maxBytes - n.usedBytes))} available · policy {n.evictionPolicy}</dd>
        <dt>Entries</dt><dd>{fmtNum(n.entries)} (expired {fmtNum(n.expired)}, evicted {fmtNum(n.evictions)})</dd>
        <dt>Local hits / misses</dt><dd>{fmtNum(n.hits)} / {fmtNum(n.misses)}</dd>
        <dt>Requests</dt><dd>{fmtNum(n.nodeRequests)} handled by the node · {fmtNum(n.gatewayRequests)} routed by the gateway</dd>
        <dt>Cluster replication health</dt><dd>{detail.replication ? `${fmtNum(detail.replication.acked)} acknowledged · ${fmtNum(detail.replication.failed)} failed · P95 lag ${fmtDuration(detail.replication.p95LagMs)} (cluster-wide)` : 'Unavailable'}</dd>
        <dt>Incarnation</dt><dd>{n.incarnation} <span className="muted small">(increases every time the node restarts)</span></dd>
      </dl>
      {detail.latestRecovery && <div className={`alert ${detail.latestRecovery.success ? 'ok' : 'warn'}`}><b>Latest recovery ({detail.latestRecovery.kind})</b>: {detail.latestRecovery.message}. {fmtNum(detail.latestRecovery.pushed)} entries synchronized; {detail.latestRecovery.failedPushes} failed pushes.</div>}
      {!detail.latestRecovery && <div className="muted small">No recovery record is available in this process for this node.</div>}
      <div>
        <h3 style={{ marginBottom: 8 }}>Recent events and state transitions</h3>
        <div className="muted small">Individual heartbeat packets are not retained as history; this timeline shows recorded cluster events for this node.</div>
        {detail.recentEvents.length === 0 ? <div className="muted small">No events for this node.</div> : (
          <ul className="timeline">
            {detail.recentEvents.map((e) => (
              <li key={e.id}><span className="t">{new Date(e.time).toLocaleTimeString([], { hour12: false })}</span><span className={`bullet ${e.severity === 'CRITICAL' ? 'red' : e.severity === 'WARNING' ? 'orange' : ''}`} /><span>{e.message}</span></li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
