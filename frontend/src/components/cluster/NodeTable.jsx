import { Eye, Trash2, Wrench, RotateCcw, FlaskConical } from 'lucide-react';
import DataTable from '../common/DataTable';
import StatusBadge from '../common/StatusBadge';
import { fmtBytes, fmtDateTime, fmtDuration, fmtNum, nodeColor } from '../../utils/format';

function MemoryBar({ pct, usedBytes, maxBytes }) {
  if (maxBytes <= 0) return <span className="muted small">Unavailable</span>;
  const tone = pct >= 90 ? 'red' : pct >= 80 ? 'orange' : 'green';
  return (
    <div style={{ minWidth: 110 }}>
      <div className={`bar ${tone}`}><span style={{ width: `${Math.min(100, pct)}%` }} /></div>
      <div className="small muted" style={{ marginTop: 3 }}>{fmtBytes(usedBytes)} / {fmtBytes(maxBytes)} ({pct}%)</div>
    </div>
  );
}

export default function NodeTable({ nodes, ringOrder, canOperate, isAdmin, simulationEnabled, busyId, onDetails, onMaintenance, onResume, onRemove, onSimulate, empty }) {
  const columns = [
    { key: 'id', header: 'Node ID / name', render: (n) => <span className="row" style={{ gap: 8 }}><span className="node-dot" style={{ background: nodeColor(n.id, ringOrder) }} /><b>{n.id}</b></span> },
    { key: 'host', header: 'Host', render: (n) => <span className="mono">{n.host}</span> },
    { key: 'port', header: 'Port', render: (n) => <span className="mono">{n.port}</span> },
    { key: 'state', header: 'Status', render: (n) => <StatusBadge status={n.state} /> },
    { key: 'role', header: 'Role / ownership', render: (n) => <div><div style={{ fontWeight: 550 }}>{n.state === 'MAINTENANCE' || n.role === 'NONE' ? 'Not serving' : n.primaryOwnershipPct > 0 ? (n.replicaKeys > 0 ? 'Primary + replica' : 'Primary') : n.replicaKeys > 0 ? 'Replica' : n.replicaKeys === null ? 'Role counts unavailable' : 'No sampled role'}</div><div className="small muted">{fmtNum(n.primaryKeys)} primary · {fmtNum(n.replicaKeys)} replica keys (sampled)</div><div className="small muted">{n.primaryOwnershipPct}% primary hash share</div></div> },
    { key: 'mem', header: 'Memory', render: (n) => <MemoryBar pct={n.memoryPct} usedBytes={n.usedBytes} maxBytes={n.maxBytes} /> },
    { key: 'entries', header: 'Entries', render: (n) => fmtNum(n.entries) },
    { key: 'req', header: 'Requests', render: (n) => <div>{fmtNum(n.gatewayRequests)}<div className="small muted">gateway · node {fmtNum(n.nodeRequests)}</div><div className="small muted">hits {fmtNum(n.hits)} · misses {fmtNum(n.misses)}</div></div> },
    { key: 'uptime', header: 'Uptime', render: (n) => fmtDuration(n.uptimeMs) },
    { key: 'hb', header: 'Heartbeat', render: (n) => <div><span>{fmtDateTime(n.lastHeartbeatAt)}</span><div className="small muted">{fmtDuration(n.lastHeartbeatAgoMs)} ago · {n.failureTimeoutMs == null || n.heartbeatIntervalMs == null ? 'Threshold unavailable' : n.lastHeartbeatAgoMs > n.failureTimeoutMs ? 'Past failure timeout' : n.lastHeartbeatAgoMs > n.heartbeatIntervalMs * 2 ? 'Late' : 'Healthy'} · {fmtNum(n.missedBeats)} missed</div>{n.stateReason && <div className="small muted" title={n.stateReason}>{n.stateReason}</div>}</div> },
    {
      key: 'actions', header: 'Actions', render: (n) => (
        <div className="row" style={{ gap: 6, flexWrap: 'nowrap' }}>
          <button className="btn small" title="Details" onClick={() => onDetails(n)}><Eye size={14} /></button>
          {canOperate && n.state === 'ACTIVE' && <button className="btn small" title="Graceful stop: drain into maintenance" disabled={busyId === n.id} onClick={() => onMaintenance(n)}><Wrench size={14} /></button>}
          {canOperate && n.state === 'MAINTENANCE' && <button className="btn small" title="Resume" disabled={busyId === n.id} onClick={() => onResume(n)}><RotateCcw size={14} /></button>}
          {isAdmin && simulationEnabled && n.state === 'ACTIVE' && <button className="btn small" title="Simulate failure (demo mode)" onClick={() => onSimulate(n)}><FlaskConical size={14} /></button>}
          {isAdmin && <button className="btn small" title="Graceful stop: drain and remove node" disabled={busyId === n.id} onClick={() => onRemove(n)}><Trash2 size={14} /></button>}
        </div>
      ),
    },
  ];
  return <DataTable columns={columns} rows={nodes} rowKey={(n) => n.id} empty={empty || 'No nodes registered.'} />;
}
