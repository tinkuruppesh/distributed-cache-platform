import { useEffect, useState } from 'react';
import { Play } from 'lucide-react';
import ConfirmDialog from '../common/ConfirmDialog';

export default function FailureSimulationForm({ nodes, initialNode, busy, onStart }) {
  const active = nodes.filter((n) => n.state === 'ACTIVE');
  const [nodeId, setNodeId] = useState(initialNode || '');
  const selectedNode = nodes.find((n) => n.id === nodeId);
  const [mode, setMode] = useState('ISOLATE');
  const [generateTraffic, setGenerateTraffic] = useState(true);
  const [rps, setRps] = useState(60);
  const [autoRestart, setAutoRestart] = useState(0);
  const [confirm, setConfirm] = useState(false);

  useEffect(() => { if (!active.some((n) => n.id === nodeId) && active.length) setNodeId(initialNode && active.some((n) => n.id === initialNode) ? initialNode : active[0].id); }, [active, nodeId, initialNode]);
  const invalidRate = generateTraffic && (!Number.isInteger(Number(rps)) || Number(rps) < 1 || Number(rps) > 500);
  const invalidRestart = !Number.isInteger(Number(autoRestart)) || Number(autoRestart) < 0 || Number(autoRestart) > 1800;
  const invalidCluster = active.length < 3;

  const submit = () => {
    setConfirm(false);
    onStart({ nodeId, mode, generateTraffic, trafficRps: generateTraffic ? Number(rps) : 0, autoRestartAfterSec: Number(autoRestart), confirm: true });
  };

  return (
    <div className="stack">
      <div className="form-grid">
        <label className="field">Node to fail
          <select value={nodeId} onChange={(e) => setNodeId(e.target.value)}>
            {active.map((n) => <option key={n.id} value={n.id}>{n.id} · primary for {n.primaryOwnershipPct}% of keys</option>)}
          </select>
          {selectedNode && <span className="muted small">Status: {selectedNode.state} · role: {selectedNode.role || 'Unavailable'} · primary ownership: {Number.isFinite(selectedNode.primaryOwnershipPct) ? `${selectedNode.primaryOwnershipPct}%` : 'Unavailable'}</span>}
        </label>
        <label className="field">Simulation mode
          <select value={mode} onChange={(e) => setMode(e.target.value)}>
            <option value="ISOLATE">Network partition — node keeps its data</option>
            <option value="CRASH">Application-level crash - clears this node's in-memory data</option>
          </select>
        </label>
        <label className="field">Request rate during test (requests/s)
          <input type="number" min="1" max="500" value={rps} disabled={!generateTraffic} onChange={(e) => setRps(e.target.value)} />
        </label>
        <label className="field">Auto-restart after (seconds, 0 = manual)
          <input type="number" min="0" max="1800" value={autoRestart} onChange={(e) => setAutoRestart(e.target.value)} />
        </label>
      </div>
      <label className="check"><input type="checkbox" checked={generateTraffic} onChange={(e) => setGenerateTraffic(e.target.checked)} />Generate continuous client traffic through the gateway so request success/failure is measured</label>
      {invalidCluster && <div className="alert warn">Failover requires at least 3 ACTIVE nodes so a majority can remain available. Currently {active.length} are active.</div>}
      {(invalidRate || invalidRestart) && <div className="alert error">{invalidRate ? 'Traffic rate must be a whole number from 1 to 500 requests/s. ' : ''}{invalidRestart ? 'Auto-restart must be a whole number from 0 to 1800 seconds.' : ''}</div>}
      <div><button className="btn danger" disabled={!nodeId || busy || invalidCluster || invalidRate || invalidRestart} onClick={() => setConfirm(true)}><Play size={14} />Start simulation</button></div>
      <ConfirmDialog open={confirm} danger title={`Take ${nodeId} out of service?`} confirmLabel="Yes, simulate failure" onCancel={() => setConfirm(false)} onConfirm={submit}
        message={<span>This makes <b>{nodeId}</b> stop answering ({mode === 'CRASH' ? 'and clears its local in-memory data' : 'while preserving its local data'}). The real failure detector will react. This does not stop a process or container.</span>} />
    </div>
  );
}
