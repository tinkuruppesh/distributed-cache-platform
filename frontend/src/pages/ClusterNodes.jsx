import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Plus, RefreshCw } from 'lucide-react';
import PageHeader from '../components/common/PageHeader';
import LoadingState from '../components/common/LoadingState';
import ErrorState from '../components/common/ErrorState';
import ConfirmDialog from '../components/common/ConfirmDialog';
import Modal from '../components/common/Modal';
import NodeTable from '../components/cluster/NodeTable';
import NodeDetails from '../components/cluster/NodeDetails';
import MetricCard from '../components/common/MetricCard';
import MemoryUsageChart from '../components/charts/MemoryUsageChart';
import NodeHealthTimeline from '../components/charts/NodeHealthTimeline';
import usePolling from '../hooks/usePolling';
import { useAuth } from '../context/AuthContext';
import { useCluster } from '../context/ClusterContext';
import { clusterService } from '../services/clusterService';
import { monitoringService } from '../services/monitoringService';
import { errorMessage } from '../services/api';
import { fmtNum } from '../utils/format';

export default function ClusterNodes() {
  const { hasRole } = useAuth();
  const { refreshMs, simulationEnabled, embeddedNodes } = useCluster();
  const navigate = useNavigate();
  const nodes = usePolling(() => clusterService.nodes(), refreshMs);
  const cluster = usePolling(() => clusterService.cluster(), refreshMs);
  const replication = usePolling(() => monitoringService.replication(), refreshMs);
  const memory = usePolling(() => monitoringService.memory(150), refreshMs);
  const events = usePolling(() => clusterService.events(100), refreshMs);
  const [detailId, setDetailId] = useState(null);
  const detail = usePolling(() => detailId ? clusterService.node(detailId) : Promise.resolve(null), detailId ? refreshMs : 0, [detailId]);
  const [search, setSearch] = useState('');
  const [stateFilter, setStateFilter] = useState('ALL');
  const [roleFilter, setRoleFilter] = useState('ALL');
  const [sort, setSort] = useState('id');
  const [confirm, setConfirm] = useState(null);
  const [busyId, setBusyId] = useState(null);
  const [msg, setMsg] = useState(null);
  const [adding, setAdding] = useState(false);
  const [form, setForm] = useState({ id: '', host: '', port: '', startEmbedded: true });
  const [addBusy, setAddBusy] = useState(false);

  const run = async (id, fn, okText) => {
    setBusyId(id); setMsg(null);
    try { const r = await fn(); setMsg({ ok: true, text: r?.message || okText }); await nodes.reload(); }
    catch (e) { setMsg({ ok: false, text: errorMessage(e) }); }
    setBusyId(null); setConfirm(null);
  };

  const addNode = async (e) => {
    e.preventDefault(); setAddBusy(true); setMsg(null);
    try {
      await clusterService.addNode({ id: form.id.trim(), host: form.startEmbedded ? undefined : form.host.trim(), port: form.port ? Number(form.port) : undefined, startEmbedded: form.startEmbedded });
      setMsg({ ok: true, text: `Node ${form.id} joined: data was synchronized before it started serving.` });
      setAdding(false); setForm({ id: '', host: '', port: '', startEmbedded: true });
      await nodes.reload();
    } catch (err) { setMsg({ ok: false, text: errorMessage(err) }); }
    setAddBusy(false);
  };

  const openDetails = (n) => setDetailId(n.id);

  useEffect(() => { if (detailId && !nodes.data?.some((n) => n.id === detailId)) setDetailId(null); }, [detailId, nodes.data]);

  const sourceNodes = nodes.data || [];
  const ids = sourceNodes.map((n) => n.id);
  const replicationByNode = new Map((replication.data?.perNode || []).map((row) => [row.nodeId, row]));
  const rows = sourceNodes.map((node) => {
    const counts = replicationByNode.get(node.id);
    const transitionEvent = (events.data || []).find((event) => event.nodeId === node.id && event.time >= node.stateSince
      && ['NODE_SUSPECTED', 'NODE_FAILED', 'NODE_ALIVE', 'NODE_RECOVERY_DETECTED', 'NODE_ACTIVE', 'NODE_MAINTENANCE', 'NODE_JOINED'].includes(event.type));
    return { ...node, stateReason: transitionEvent?.message, primaryKeys: counts?.primaryKeys ?? null, replicaKeys: counts?.replicaKeys ?? null,
      keysSampled: replication.data?.keysSampled ?? null,
      heartbeatIntervalMs: cluster.data?.settings?.heartbeatIntervalMs ?? null,
      failureTimeoutMs: cluster.data?.settings?.failureTimeoutMs ?? null };
  });
  const filteredNodes = useMemo(() => {
    const q = search.trim().toLowerCase();
    return rows.filter((n) => (!q || `${n.id} ${n.host} ${n.port}`.toLowerCase().includes(q))
      && (stateFilter === 'ALL' || n.state === stateFilter)
      && (roleFilter === 'ALL' || (roleFilter === 'PRIMARY' ? n.primaryOwnershipPct > 0 : n.replicaKeys > 0)))
      .sort((a, b) => sort === 'memory' ? (b.memoryPct - a.memoryPct) : sort === 'uptime' ? (b.uptimeMs - a.uptimeMs) : sort === 'entries' ? (b.entries - a.entries) : a.id.localeCompare(b.id));
  }, [rows, search, stateFilter, roleFilter, sort]);
  const count = (state) => rows.filter((n) => n.state === state).length;
  const measuredMemory = rows.filter((n) => n.maxBytes > 0);
  const averageMemory = measuredMemory.length ? measuredMemory.reduce((sum, n) => sum + n.memoryPct, 0) / measuredMemory.length : null;
  const unhealthy = rows.filter((n) => ['SUSPECTED', 'FAILED', 'RECOVERING', 'JOINING'].includes(n.state)).length;

  if (nodes.loading && !nodes.data) return <LoadingState />;
  if (nodes.error && !nodes.data) return <ErrorState message={nodes.error} onRetry={nodes.reload} />;

  return (
    <>
      <PageHeader title="Cluster Nodes" subtitle="Every cache node, its role, health and load."
        actions={<div className="row"><Link className="btn small" to="/">Overview</Link><button className="btn small" onClick={() => { nodes.reload(); cluster.reload(); replication.reload(); memory.reload(); events.reload(); }}><RefreshCw size={14} />Refresh</button>{hasRole('ADMIN') && <button className="btn primary" onClick={() => setAdding(true)}><Plus size={14} />Add node</button>}</div>} />
      {msg && <div className={`alert ${msg.ok ? 'ok' : 'error'}`} style={{ marginBottom: 14 }}>{msg.text}</div>}
      {nodes.error && nodes.data && <div className="alert warn">Node refresh failed: {nodes.error}. Showing last successful node data (refreshed {new Date(nodes.lastSuccessAt).toLocaleString()}). <button className="btn small" onClick={nodes.reload}>Retry</button></div>}
      {cluster.error && <div className="alert warn">Cluster settings refresh failed: {cluster.error}. {cluster.data ? `Showing settings last refreshed ${new Date(cluster.lastSuccessAt).toLocaleString()}.` : 'Heartbeat thresholds are unavailable.'} <button className="btn small" onClick={cluster.reload}>Retry</button></div>}
      <div className="grid metrics">
        <MetricCard label="Total nodes" value={fmtNum(rows.length)} /><MetricCard label="Active" value={fmtNum(count('ACTIVE'))} tone="green" />
        <MetricCard label="Suspected" value={fmtNum(count('SUSPECTED'))} tone={count('SUSPECTED') ? 'orange' : undefined} />
        <MetricCard label="Failed" value={fmtNum(count('FAILED'))} tone={count('FAILED') ? 'red' : undefined} />
        <MetricCard label="Recovering" value={fmtNum(count('RECOVERING') + count('JOINING'))} tone="orange" />
        <MetricCard label="Maintenance" value={fmtNum(count('MAINTENANCE'))} />
        <MetricCard label="Average memory" value={averageMemory === null ? 'Unavailable' : `${averageMemory.toFixed(1)}%`} />
        <MetricCard label="Unhealthy nodes" value={fmtNum(unhealthy)} tone={unhealthy ? 'red' : 'green'} />
      </div>
      {nodes.lastSuccessAt && <div className="muted small" style={{ margin: '8px 0 12px' }}>Last successful node refresh: {new Date(nodes.lastSuccessAt).toLocaleString()}{nodes.loading ? ' · Refreshing…' : ''}</div>}
      <div className="card stack" style={{ marginBottom: 14 }}>
        <div className="form-grid">
          <label className="field">Search ID, IP/host or port<input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Find a node" /></label>
          <label className="field">Status<select value={stateFilter} onChange={(e) => setStateFilter(e.target.value)}><option value="ALL">All statuses</option>{['ACTIVE', 'SUSPECTED', 'FAILED', 'RECOVERING', 'JOINING', 'MAINTENANCE'].map((s) => <option key={s}>{s}</option>)}</select></label>
          <label className="field">Role<select value={roleFilter} onChange={(e) => setRoleFilter(e.target.value)}><option value="ALL">All roles</option><option value="PRIMARY">Primary owner</option><option value="REPLICA">Replica copy</option></select></label>
          <label className="field">Sort by<select value={sort} onChange={(e) => setSort(e.target.value)}><option value="id">Node ID</option><option value="memory">Memory utilization</option><option value="uptime">Uptime</option><option value="entries">Cache entries</option></select></label>
        </div>
        {replication.loading && !replication.data && <LoadingState label="Loading sampled node ownership counts…" />}
        {replication.error && <div className="alert warn">Role counts refresh failed: {replication.error}. {replication.data ? `Showing the sample last refreshed ${new Date(replication.lastSuccessAt).toLocaleString()}.` : 'Role counts are unavailable.'} <button className="btn small" onClick={replication.reload}>Retry</button></div>}
        {events.loading && !events.data && <LoadingState label="Loading recent node transition events…" />}
        {events.error && <div className="alert warn">Node transition reasons refresh failed: {events.error}. {events.data ? `Showing events last refreshed ${new Date(events.lastSuccessAt).toLocaleString()}.` : 'Transition reason details may be unavailable.'} <button className="btn small" onClick={events.reload}>Retry</button></div>}
      </div>
      <div className="card" style={{ padding: 8 }}>
        <NodeTable nodes={filteredNodes} empty={rows.length ? 'No nodes match the current search and filters.' : 'No nodes are registered in the cluster.'} ringOrder={ids} canOperate={hasRole('ADMIN', 'OPERATOR')} isAdmin={hasRole('ADMIN')} simulationEnabled={simulationEnabled} busyId={busyId}
          onDetails={openDetails}
          onMaintenance={(n) => setConfirm({ type: 'maintenance', n })} onResume={(n) => run(n.id, () => clusterService.resume(n.id), 'Node resumed')}
          onRemove={(n) => setConfirm({ type: 'remove', n })} onSimulate={(n) => navigate(`/simulation?node=${n.id}`)} />
      </div>
      <p className="muted small" style={{ marginTop: 12 }}>Role key counts are sampled from up to {fmtNum(replication.data?.keysSampled ?? 0)} current cache entries. Primary hash share comes directly from current ring ownership.</p>

      <div className="grid cols-2" style={{ marginTop: 16 }}>
        <section className="card"><div className="card-title"><h2>Memory utilization history</h2></div>{memory.loading && !memory.data ? <LoadingState /> : memory.error && !memory.data ? <ErrorState message={memory.error} onRetry={memory.reload} /> : memory.data?.history?.length ? <MemoryUsageChart history={memory.data.history} nodeIds={ids} /> : <div className="state">Historical memory samples are not available yet.</div>}{memory.data && <div className="muted small">Sampled by the backend every 2 seconds · {memory.data.history.length} samples</div>}</section>
        <section className="card"><div className="card-title"><h2>Node health history</h2></div>{memory.loading && !memory.data ? <LoadingState /> : memory.error && !memory.data ? <ErrorState message={memory.error} onRetry={memory.reload} /> : memory.data?.history?.length ? <NodeHealthTimeline history={memory.data.history} nodeIds={ids} /> : <div className="state">Historical node health samples are not available yet.</div>}</section>
      </div>
      {memory.error && memory.data && <div className="alert warn">History refresh failed: {memory.error}. Showing the last successful samples from {new Date(memory.lastSuccessAt).toLocaleString()}.</div>}
      {memory.data && <div className="muted small" style={{ marginTop: 8 }}>Last successful history refresh: {new Date(memory.lastSuccessAt).toLocaleString()}{memory.loading ? ' · Refreshing…' : ''}. Per-node request totals are available, but historical request-series data is not exposed by the backend.</div>}

      <Modal open={!!detailId} title={`Node ${detail.data?.node?.id || detailId || ''}`} wide onClose={() => setDetailId(null)}>{detail.loading && !detail.data ? <LoadingState label="Loading node details…" /> : detail.error && !detail.data ? <ErrorState message={detail.error} onRetry={detail.reload} /> : detail.data && <NodeDetails detail={{ ...detail.data, primaryKeys: replicationByNode.get(detailId)?.primaryKeys, replicaKeys: replicationByNode.get(detailId)?.replicaKeys, keysSampled: replication.data?.keysSampled, replication: replication.data?.stats }} />}{detail.error && detail.data && <div className="alert warn">Could not refresh these details: {detail.error}. Showing the last successful detail response.</div>}</Modal>

      <ConfirmDialog open={confirm?.type === 'maintenance'} title={`Drain ${confirm?.n?.id} into maintenance?`} confirmLabel="Drain into maintenance" busy={busyId === confirm?.n?.id}
        message="Its data is copied to the other owners first, then it leaves the hash ring while its process remains available for a later resume." onCancel={() => setConfirm(null)}
        onConfirm={() => run(confirm.n.id, () => clusterService.maintenance(confirm.n.id), 'Node drained')} />
      <ConfirmDialog open={confirm?.type === 'remove'} danger title={`Gracefully stop and remove ${confirm?.n?.id}?`} confirmLabel="Drain and remove" busy={busyId === confirm?.n?.id}
        message="The node leaves the cluster permanently. Its data is moved to the new owners first." onCancel={() => setConfirm(null)}
        onConfirm={() => run(confirm.n.id, () => clusterService.removeNode(confirm.n.id), 'Node removed')} />

      <Modal open={adding} title="Add a node" onClose={() => setAdding(false)}>
        <form className="stack" onSubmit={addNode}>
          <label className="field">Node ID<input required value={form.id} onChange={(e) => setForm({ ...form, id: e.target.value })} placeholder="node-7" /></label>
          {embeddedNodes && <label className="check"><input type="checkbox" checked={form.startEmbedded} onChange={(e) => setForm({ ...form, startEmbedded: e.target.checked })} />Start a new embedded node for me (dev mode)</label>}
          {!form.startEmbedded && (
            <div className="form-grid">
              <label className="field">Host<input required value={form.host} onChange={(e) => setForm({ ...form, host: e.target.value })} placeholder="cache-node-7" /></label>
              <label className="field">Port<input required type="number" value={form.port} onChange={(e) => setForm({ ...form, port: e.target.value })} placeholder="7001" /></label>
            </div>
          )}
          <p className="muted small">The node must already be running and accept the cluster secret. Its identity is verified, data is synchronized, and only then does it enter the hash ring.</p>
          <div className="row" style={{ justifyContent: 'flex-end' }}><button type="button" className="btn" onClick={() => setAdding(false)}>Cancel</button><button className="btn primary" disabled={addBusy}>{addBusy ? 'Joining…' : 'Add node'}</button></div>
        </form>
      </Modal>
    </>
  );
}
