import { useMemo, useState } from 'react';
import { RefreshCw, Search, X } from 'lucide-react';
import { Link } from 'react-router-dom';
import PageHeader from '../components/common/PageHeader';
import LoadingState from '../components/common/LoadingState';
import ErrorState from '../components/common/ErrorState';
import HashRingVisualization from '../components/cluster/HashRingVisualization';
import usePolling from '../hooks/usePolling';
import { clusterService } from '../services/clusterService';
import { cacheService } from '../services/cacheService';
import { errorMessage } from '../services/api';
import { useCluster } from '../context/ClusterContext';
import { nodeColor, fmtDateTime, fmtBytes } from '../utils/format';

export default function HashRing() {
  const { refreshMs } = useCluster();
  const interval = Math.max(refreshMs, 5000);
  const ring = usePolling(() => clusterService.hashRing(80), interval);
  const nodes = usePolling(() => clusterService.nodes(), interval);
  const [selectedNode, setSelectedNode] = useState(null);
  const [selectedKey, setSelectedKey] = useState(null);
  const [keyInput, setKeyInput] = useState('');
  const [keyMapping, setKeyMapping] = useState(null);
  const [mappingError, setMappingError] = useState(null);
  const [mappingLoading, setMappingLoading] = useState(false);

  const nodeById = useMemo(() => new Map((nodes.data || []).map((node) => [node.id, node])), [nodes.data]);

  const findOwner = async (event) => {
    event.preventDefault();
    const key = keyInput.trim();
    if (!key) {
      setMappingError('Enter a cache key to find its owner.');
      return;
    }
    setMappingLoading(true);
    setMappingError(null);
    try {
      const placement = await cacheService.locate(key);
      setKeyMapping({ ...placement, key, position: placement.ringPosition });
      setSelectedKey(null);
      setSelectedNode(null);
    } catch (error) {
      setKeyMapping(null);
      setMappingError(errorMessage(error));
    } finally {
      setMappingLoading(false);
    }
  };

  const clearMapping = () => {
    setKeyInput('');
    setKeyMapping(null);
    setMappingError(null);
  };

  if (ring.loading && !ring.data) return <LoadingState label="Loading the live hash ring…" />;
  if (ring.error && !ring.data) return <ErrorState message={ring.error} onRetry={ring.reload} />;

  const data = ring.data;
  const nodeIds = Object.keys(data.ownership || {});
  const selectedSample = data.keys.find((entry) => entry.key === selectedKey);
  const selected = keyMapping || selectedSample;
  const selectedNodeInfo = selectedNode ? nodeById.get(selectedNode) : null;
  const primarySampleCount = selectedNode ? data.keys.filter((entry) => entry.primary === selectedNode).length : 0;
  const replicaSampleCount = selectedNode ? data.keys.filter((entry) => entry.replicas.includes(selectedNode)).length : 0;

  return (
    <>
      <PageHeader
        title="Hash Ring"
        subtitle="See how the live consistent-hash ring assigns key ranges to nodes and where a key will be stored."
        actions={<button className="btn" type="button" onClick={() => { ring.reload(); nodes.reload(); }} disabled={ring.loading}><RefreshCw size={14} />Refresh ring</button>}
      />
      {ring.error && <div className="alert warn" style={{ marginBottom: 14 }}>Ring refresh failed. Showing the last successful view from {fmtDateTime(ring.lastSuccessAt)}. <button className="btn small" onClick={ring.reload}>Retry</button></div>}

      <div className="grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 510px), 1fr))', alignItems: 'start' }}>
        <section className="card" aria-label="Hash ring visualization">
          <div className="card-title">
            <div><h2>Ownership ring</h2><p className="muted small" style={{ marginTop: 4 }}>A key moves clockwise to its primary virtual node.</p></div>
            <span className="badge blue">{nodeIds.length} nodes</span>
          </div>
          <HashRingVisualization
            ring={data}
            selectedNode={selectedNode}
            onSelectNode={(id) => { setSelectedNode(id); setSelectedKey(null); setKeyMapping(null); }}
            selectedKey={selectedKey}
            onSelectKey={(key) => { setSelectedKey(key); setKeyMapping(null); setSelectedNode(null); }}
            mappedKey={keyMapping}
          />
          <div className="grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(125px, 1fr))', gap: 10, marginTop: 16 }}>
            <div className="card" style={{ padding: 12, boxShadow: 'none' }}><div className="muted small">Physical nodes</div><b>{nodeIds.length}</b></div>
            <div className="card" style={{ padding: 12, boxShadow: 'none' }}><div className="muted small">Virtual positions</div><b>{data.virtualNodes.length}</b></div>
            <div className="card" style={{ padding: 12, boxShadow: 'none' }}><div className="muted small">Virtual nodes / host</div><b>{data.virtualNodesPerNode}</b></div>
            <div className="card" style={{ padding: 12, boxShadow: 'none' }}><div className="muted small">Replica copies</div><b>{data.replicationFactor}</b></div>
          </div>
          <p className="muted small" style={{ marginTop: 12 }}>Colored arcs show primary ownership ranges. Small ring marks are backend virtual-node positions. Interior dots are real cache keys sampled for display ({data.keys.length}); they are not a complete key count.</p>
          <p className="muted small" style={{ marginTop: 6 }}>Last ring refresh: {fmtDateTime(ring.lastSuccessAt)}{ring.loading ? ' · Refreshing…' : ''}</p>
        </section>

        <div className="stack">
          <section className="card">
            <div className="card-title"><div><h2>Find a key’s owner</h2><p className="muted small" style={{ marginTop: 4 }}>The backend calculates placement using the active ring.</p></div></div>
            <form className="row" onSubmit={findOwner}>
              <label className="field" style={{ flex: '1 1 210px' }}>Cache key<input value={keyInput} onChange={(event) => setKeyInput(event.target.value)} maxLength={200} placeholder="Enter a key" aria-label="Cache key to locate" /></label>
              <button className="btn primary" type="submit" disabled={mappingLoading}><Search size={14} />{mappingLoading ? 'Finding…' : 'Find owner'}</button>
              {(keyInput || keyMapping) && <button className="btn" type="button" onClick={clearMapping} disabled={mappingLoading}><X size={14} />Clear</button>}
            </form>
            {mappingError && <div className="alert error" style={{ marginTop: 12 }}>{mappingError}</div>}
            {selected && <dl className="kv" style={{ marginTop: 14 }}>
              <dt>Key</dt><dd className="mono">{selected.key}</dd>
              <dt>Primary node</dt><dd><b>{selected.primary || 'Unavailable'}</b></dd>
              <dt>Replica nodes</dt><dd>{selected.replicas?.join(', ') || 'None reported'}</dd>
              <dt>Ring position</dt><dd className="mono">{Number.isFinite(selected.position) ? selected.position.toFixed(6) : 'Unavailable'}</dd>
              <dt>Node availability</dt><dd>{selected.states ? [selected.primary, ...(selected.replicas || [])].map((id) => `${id}: ${selected.states[id] || 'unknown'}`).join(' · ') : 'Unavailable from sampled key data'}</dd>
            </dl>}
            {!selected && !mappingError && <div className="state" style={{ padding: '22px 12px' }}>Enter a key to see its current primary and replica placement.</div>}
          </section>

          <section className="card">
            <div className="card-title"><div><h2>Nodes and ownership</h2><p className="muted small" style={{ marginTop: 4 }}>Select a node to inspect its live status and ring share.</p></div><button className="btn small" type="button" onClick={() => setSelectedNode(null)} disabled={!selectedNode}>Clear selection</button></div>
            {!nodeIds.length ? <div className="state">No physical nodes are present in the ring.</div> : <div className="stack" style={{ gap: 8 }}>
              {nodeIds.map((id) => {
                const node = nodeById.get(id);
                const share = Number(data.ownership[id] || 0);
                const active = selectedNode === id;
                return <button key={id} type="button" aria-pressed={active} onClick={() => { setSelectedNode(active ? null : id); setSelectedKey(null); setKeyMapping(null); }} style={{ border: `1px solid ${active ? nodeColor(id, nodeIds) : 'var(--border)'}`, borderRadius: 8, padding: 11, background: active ? 'var(--primary-weak)' : 'var(--surface)', color: 'var(--text)', textAlign: 'left', cursor: 'pointer' }}>
                  <div className="spread"><span className="row" style={{ gap: 8 }}><span className="node-dot" style={{ background: nodeColor(id, nodeIds) }} /><b>{id}</b></span><span className={`badge ${node?.state === 'ACTIVE' ? 'green' : node?.state === 'FAILED' ? 'red' : 'orange'}`}>{node?.state || 'Status unavailable'}</span></div>
                  <div className="spread muted small" style={{ marginTop: 6 }}><span>{share.toFixed(2)}% primary ring ownership</span><span>{node ? `${node.host}:${node.port}` : 'Address unavailable'}</span></div>
                  <div className="bar" style={{ height: 6, marginTop: 8 }}><span style={{ width: `${Math.max(0, Math.min(100, share))}%`, background: nodeColor(id, nodeIds) }} /></div>
                </button>;
              })}
            </div>}
            {nodes.loading && !nodes.data && <LoadingState label="Loading node health…" />}
            {nodes.error && <div className="alert warn" style={{ marginTop: 10 }}>Node health could not refresh: {nodes.error}. {nodes.data ? `Showing nodes from ${fmtDateTime(nodes.lastSuccessAt)}.` : 'Status and addresses are unavailable.'} <button className="btn small" onClick={nodes.reload}>Retry</button></div>}
            {selectedNode && <div style={{ marginTop: 14, paddingTop: 12, borderTop: '1px solid var(--border)' }}>
              <h3 style={{ marginBottom: 8 }}>{selectedNode} details</h3>
              {selectedNodeInfo ? <dl className="kv">
                <dt>Host / port</dt><dd>{selectedNodeInfo.host}:{selectedNodeInfo.port}</dd>
                <dt>Health / role</dt><dd>{selectedNodeInfo.state} · {selectedNodeInfo.role || 'Unavailable'}</dd>
                <dt>Ring ownership</dt><dd>{Number(data.ownership[selectedNode] || 0).toFixed(3)}% of hash space</dd>
                <dt>Stored entries</dt><dd>{Number(selectedNodeInfo.entries).toLocaleString()}</dd>
                <dt>Memory</dt><dd>{fmtBytes(selectedNodeInfo.usedBytes)} / {fmtBytes(selectedNodeInfo.maxBytes)} ({Number(selectedNodeInfo.memoryPct).toFixed(1)}%)</dd>
                <dt>Sampled key placements</dt><dd>{primarySampleCount} primary · {replicaSampleCount} replica of {data.keys.length} sampled keys</dd>
              </dl> : <div className="muted small">Live node details are unavailable.</div>}
            </div>}
          </section>
        </div>
      </div>
      <div className="muted small" style={{ marginTop: 12 }}>Ring configuration and node membership are managed in <Link to="/nodes">Cluster Nodes</Link> and Settings. This view is read-only.</div>
    </>
  );
}
