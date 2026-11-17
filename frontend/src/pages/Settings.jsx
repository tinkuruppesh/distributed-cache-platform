import { useEffect, useState } from 'react';
import PageHeader from '../components/common/PageHeader';
import LoadingState from '../components/common/LoadingState';
import ConfirmDialog from '../components/common/ConfirmDialog';
import { clusterService } from '../services/clusterService';
import { errorMessage } from '../services/api';
import { useAuth } from '../context/AuthContext';
import { useCluster } from '../context/ClusterContext';

export default function Settings() {
  const { hasRole } = useAuth();
  const { reload } = useCluster();
  const canEdit = hasRole('ADMIN');
  const [s, setS] = useState(null);
  const [msg, setMsg] = useState(null);
  const [busy, setBusy] = useState(false);
  const [confirm, setConfirm] = useState(false);
  const [orig, setOrig] = useState(null);

  useEffect(() => { clusterService.settings().then((x) => { setS(toForm(x)); setOrig(x); }).catch((e) => setMsg({ ok: false, text: errorMessage(e) })); }, []);

  if (!hasRole('ADMIN', 'OPERATOR')) return (<><PageHeader title="Settings" /><div className="alert warn">Settings are available to administrators and operators.</div></>);
  if (!s) return msg ? <div className="alert error">{msg.text}</div> : <LoadingState />;

  const set = (k) => (e) => setS({ ...s, [k]: e.target.value });
  const rebalances = orig && (Number(s.replicationFactor) !== orig.replicationFactor || Number(s.virtualNodes) !== orig.virtualNodes);

  const errors = [];
  if (Number(s.heartbeatIntervalMs) < 200 || Number(s.heartbeatIntervalMs) > 10000) errors.push('Heartbeat interval must be 200–10000 ms.');
  if (Number(s.failureTimeoutMs) < 3 * Number(s.heartbeatIntervalMs)) errors.push('Failure timeout must be at least 3× the heartbeat interval.');
  if (Number(s.virtualNodes) < 10 || Number(s.virtualNodes) > 1000) errors.push('Virtual nodes must be 10–1000.');
  if (Number(s.maxMemoryMb) < 1) errors.push('Memory limit must be at least 1 MB.');

  const save = async () => {
    setBusy(true); setMsg(null); setConfirm(false);
    try {
      const r = await clusterService.saveSettings(fromForm(s));
      setOrig(r); setS(toForm(r)); reload();
      setMsg({ ok: true, text: 'Settings validated and applied.' });
    } catch (e) { setMsg({ ok: false, text: errorMessage(e) }); }
    setBusy(false);
  };

  return (
    <>
      <PageHeader title="Settings" subtitle="Cluster behaviour. Values are validated before anything changes." />
      {msg && <div className={`alert ${msg.ok ? 'ok' : 'error'}`} style={{ marginBottom: 14 }}>{msg.text}</div>}
      {!canEdit && <div className="alert info" style={{ marginBottom: 14 }}>Read-only: only administrators can change settings.</div>}
      <div className="stack">
        <div className="card"><h2 style={{ marginBottom: 12 }}>Failure detection</h2>
          <div className="form-grid">
            <Field label="Heartbeat interval (ms)" v={s.heartbeatIntervalMs} on={set('heartbeatIntervalMs')} dis={!canEdit} type="number" />
            <Field label="Failure timeout (ms)" v={s.failureTimeoutMs} on={set('failureTimeoutMs')} dis={!canEdit} type="number" hint="≥ 3× heartbeat interval" />
          </div></div>
        <div className="card"><h2 style={{ marginBottom: 12 }}>Data distribution</h2>
          <div className="form-grid">
            <Field label="Replication factor (replicas per key)" v={s.replicationFactor} on={set('replicationFactor')} dis={!canEdit} type="number" hint="changing this re-balances data" />
            <Field label="Virtual nodes per node" v={s.virtualNodes} on={set('virtualNodes')} dis={!canEdit} type="number" hint="changing this re-balances data" />
          </div></div>
        <div className="card"><h2 style={{ marginBottom: 12 }}>Memory &amp; expiration</h2>
          <div className="form-grid">
            <Field label="Memory limit per node (MB)" v={s.maxMemoryMb} on={set('maxMemoryMb')} dis={!canEdit} type="number" />
            <label className="field">Eviction strategy<select value={s.evictionPolicy} onChange={set('evictionPolicy')} disabled={!canEdit}><option value="LRU">LRU — least recently used</option><option value="LFU">LFU — least frequently used</option></select></label>
            <Field label="Default TTL (seconds, 0 = none)" v={s.defaultTtlSeconds} on={set('defaultTtlSeconds')} dis={!canEdit} type="number" />
          </div></div>
        <div className="card"><h2 style={{ marginBottom: 12 }}>Dashboard</h2>
          <div className="form-grid"><Field label="Monitoring refresh interval (ms)" v={s.monitoringRefreshMs} on={set('monitoringRefreshMs')} dis={!canEdit} type="number" hint="1000–60000" /></div></div>
        {errors.length > 0 && <div className="alert warn">{errors.map((x) => <div key={x}>{x}</div>)}</div>}
        {canEdit && <div><button className="btn primary" disabled={busy || errors.length > 0} onClick={() => (rebalances ? setConfirm(true) : save())}>{busy ? 'Applying…' : 'Validate & apply'}</button></div>}
      </div>
      <ConfirmDialog open={confirm} title="Re-balance the cluster?" confirmLabel="Apply & re-balance" onCancel={() => setConfirm(false)} onConfirm={save}
        message="Changing the replication factor or virtual node count moves data between nodes. The cache stays online, but this can take a moment on large data sets." />
    </>
  );
}

function Field({ label, v, on, dis, type = 'text', hint }) {
  return <label className="field">{label}<input type={type} value={v} onChange={on} disabled={dis} />{hint && <span className="small" style={{ fontWeight: 400 }}>{hint}</span>}</label>;
}
const toForm = (x) => ({ ...x, maxMemoryMb: Math.round(x.maxMemoryBytes / 1048576) });
const fromForm = (s) => ({
  heartbeatIntervalMs: Number(s.heartbeatIntervalMs), failureTimeoutMs: Number(s.failureTimeoutMs), replicationFactor: Number(s.replicationFactor),
  virtualNodes: Number(s.virtualNodes), defaultTtlSeconds: Number(s.defaultTtlSeconds), maxMemoryBytes: Math.round(Number(s.maxMemoryMb) * 1048576),
  evictionPolicy: s.evictionPolicy, monitoringRefreshMs: Number(s.monitoringRefreshMs),
});
