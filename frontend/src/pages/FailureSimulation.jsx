import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { RotateCcw } from 'lucide-react';
import PageHeader from '../components/common/PageHeader';
import LoadingState from '../components/common/LoadingState';
import MetricCard from '../components/common/MetricCard';
import ErrorState from '../components/common/ErrorState';
import FailureSimulationForm from '../components/simulation/FailureSimulationForm';
import SimulationTimeline from '../components/simulation/SimulationTimeline';
import RecoveryReport from '../components/simulation/RecoveryReport';
import usePolling from '../hooks/usePolling';
import { simulationService } from '../services/simulationService';
import { clusterService } from '../services/clusterService';
import { monitoringService } from '../services/monitoringService';
import { errorMessage } from '../services/api';
import { useAuth } from '../context/AuthContext';
import { useCluster } from '../context/ClusterContext';
import { fmtNum } from '../utils/format';

const STEPS = [['INJECTING', 'Failure injected'], ['DETECTING', 'Heartbeat timeout'], ['FAILOVER', 'Replica promotion'], ['AWAITING_RESTART', 'Traffic on healthy nodes'], ['RECOVERING', 'Recovery & sync'], ['COMPLETED', 'Node active']];
const KEY = 'dcp.activeSimulation';

export default function FailureSimulation() {
  const { hasRole } = useAuth();
  const { simulationEnabled } = useCluster();
  const [params] = useSearchParams();
  const [simId, setSimId] = useState(() => sessionStorage.getItem(KEY));
  const [msg, setMsg] = useState(null);
  const [busy, setBusy] = useState(false);
  const [actionBusy, setActionBusy] = useState(false);
  const baseline = useRef(null);

  const nodes = usePolling(() => clusterService.nodes(), 2000);
  const failovers = usePolling(() => clusterService.failovers(), simId ? 2000 : 0, [simId]);
  const sim = usePolling(() => (simId ? simulationService.get(simId) : Promise.resolve(null)), simId ? 1000 : 0, [simId]);
  const overview = usePolling(() => monitoringService.overview(), simId ? 1000 : 0, [simId]);
  const d = sim.data;
  const phase = d?.summary?.phase;
  const finished = phase === 'COMPLETED' || phase === 'FAILED' || phase === 'CANCELLED';

  // remember request counters at the moment we started watching, to show requests since start
  useEffect(() => { if (simId && overview.data && !baseline.current) baseline.current = overview.data; }, [simId, overview.data]);

  const start = async (body) => {
    setBusy(true); setMsg(null); baseline.current = overview.data || null;
    try { const v = await simulationService.start(body); sessionStorage.setItem(KEY, v.id); setSimId(v.id); }
    catch (e) { setMsg({ ok: false, text: errorMessage(e) }); }
    setBusy(false);
  };
  const restart = async () => {
    try { await simulationService.restartNode(simId); setMsg({ ok: true, text: 'Restart requested — the node is coming back.' }); }
    catch (e) { setMsg({ ok: false, text: errorMessage(e) }); }
  };
  const reset = () => { sessionStorage.removeItem(KEY); setSimId(null); baseline.current = null; setMsg(null); };

  const stop = async () => {
    setActionBusy(true);
    try { await simulationService.stop(simId); setMsg({ ok: true, text: 'Stop requested. Traffic will stop and the node will be restored safely.' }); }
    catch (e) { setMsg({ ok: false, text: errorMessage(e) }); }
    finally { setActionBusy(false); }
  };
  const refresh = () => { nodes.reload(); if (simId) { sim.reload(); overview.reload(); } };
  const exportReport = () => {
    if (!d?.report) return;
    const url = URL.createObjectURL(new Blob([JSON.stringify(d.report, null, 2)], { type: 'application/json' }));
    const a = document.createElement('a'); a.href = url; a.download = `simulation-${simId}-report.json`; a.click(); URL.revokeObjectURL(url);
  };

  if (!hasRole('ADMIN')) return (<><PageHeader title="Failure Simulation" /><div className="alert warn">Only administrators can run failure simulations. You can review past results under Recovery Analytics.</div></>);
  if (!simulationEnabled) return (<><PageHeader title="Failure Simulation" /><div className="alert warn">Failure simulation is disabled on this server (cluster.simulation-enabled=false). Enable it only in an isolated demo or test environment.</div></>);

  const stepIndex = STEPS.findIndex(([p]) => p === phase);
  const o = overview.data, b = baseline.current;
  const reqSince = o && b ? o.totalRequests - b.totalRequests : 0;
  const failSince = o && b ? o.failedRequests - b.failedRequests : 0;
  const successSince = o && b ? o.successfulRequests - b.successfulRequests : 0;
  const fallbackSince = o && b ? o.fallbackRequests - b.fallbackRequests : 0;
  const failover = failovers.data?.find((item) => item.nodeId === d?.summary?.nodeId && item.detectedAt >= (d?.summary?.startedAt || 0));

  return (
    <>
      <PageHeader title="Failure Simulation" subtitle="Inject a controlled failure and watch the cluster detect it, promote replicas, keep serving, and recover."
        actions={<><button className="btn" onClick={refresh}><RotateCcw size={14} />Refresh</button>{simId && finished && <button className="btn" onClick={reset}>New simulation</button>}</>} />
      <div className="stack">
        {sim.error && d && <div className="alert warn">Showing last successful simulation data; refresh failed: {sim.error}</div>}
        {nodes.error && !nodes.data && !simId && <ErrorState message={nodes.error} onRetry={nodes.reload} />}
        <div className="alert warn">Demo-only feature. It never kills processes or touches Docker: the selected node is told to stop answering, and the real failure-handling code does the rest.</div>
        {msg && <div className={`alert ${msg.ok ? 'ok' : 'error'}`}>{msg.text}</div>}
        {!simId && (
          <div className="card">
            <h2 style={{ marginBottom: 12 }}>Configure simulation</h2>
            {nodes.error && !nodes.data ? <ErrorState message={nodes.error} onRetry={nodes.reload} /> : nodes.data ? <><FailureSimulationForm nodes={nodes.data} initialNode={params.get('node')} busy={busy} onStart={start} />{nodes.error && <div className="alert warn">Node list is stale: {nodes.error}</div>}</> : <LoadingState />}
          </div>
        )}
        {simId && !d && (sim.error ? <ErrorState message={sim.error} onRetry={sim.reload} /> : <LoadingState label="Loading simulation" />)}
        {d && (
          <>
            <div className="card">
              <div className="spread" style={{ marginBottom: 12 }}><h2>Simulation {d.summary.id} · {d.summary.nodeId} ({d.summary.mode === 'CRASH' ? 'crash' : 'partition'})</h2>
                </div>
              <div className="row" style={{ marginBottom: 12 }}>{phase === 'AWAITING_RESTART' && <button className="btn primary" disabled={actionBusy} onClick={restart}>Restart {d.summary.nodeId}</button>}{!finished && <button className="btn danger" disabled={actionBusy} onClick={stop}>Stop safely</button>}</div>
              {d.autoRestartAt > 0 && phase === 'AWAITING_RESTART' && <p className="muted small">Automatic recovery in {Math.max(0, Math.ceil((d.autoRestartAt - Date.now()) / 1000))} seconds.</p>}
              {phase === 'CANCELLED' && <div className="alert warn">Simulation stopped after safe node recovery.</div>}
              <div className="stepper">
                {STEPS.map(([p, label], i) => (
                  <div key={p} className={`step ${phase === 'FAILED' ? '' : i < stepIndex || phase === 'COMPLETED' ? 'done' : i === stepIndex ? 'current' : ''}`}>{label}</div>
                ))}
              </div>
              {phase === 'FAILED' && <div className="alert error" style={{ marginTop: 12 }}>The simulation did not complete normally{d.report ? `: ${d.report.outcome.replaceAll('_', ' ')}` : ''}. See the timeline and notes.</div>}
            </div>
            {!finished && (
              <div className="grid metrics">
                <MetricCard label="Requests since page opened" value={fmtNum(reqSince)} hint="real gateway counter delta" tone="blue" />
                <MetricCard label="Successful requests" value={fmtNum(successSince)} tone="green" />
                <MetricCard label="Failed requests" value={fmtNum(failSince)} tone={failSince ? 'red' : 'green'} />
                <MetricCard label="Fallback requests" value={fmtNum(fallbackSince)} hint="gateway fallback counter delta" tone="orange" />
                <MetricCard label="Cluster health" value={o?.health || '–'} tone={o?.health === 'HEALTHY' ? 'green' : 'orange'} hint={o ? `${o.activeNodes}/${o.totalNodes} active` : ''} />
                <MetricCard label="Failover record" value={failover ? 'Confirmed' : failovers.error ? 'Unavailable' : 'Pending'} hint={failover ? `${failover.keysPromoted} keys promoted · ${failover.reReplicationFailures} re-replication failures` : failovers.error || 'Waiting for backend failover record'} tone={failover ? 'green' : 'orange'} />
              </div>
            )}
            <div className="grid cols-2" style={{ gridTemplateColumns: finished ? '1fr' : 'minmax(0,1fr) minmax(0,1fr)' }}>
              <div className="card"><div className="card-title"><h2>Live event timeline</h2></div><SimulationTimeline events={d.events} /></div>
              {!finished && <div className="card"><h2 style={{ marginBottom: 8 }}>What is happening</h2><p className="muted">{HELP[phase] || 'Starting…'}</p></div>}
            </div>
            {finished && <div className="card"><RecoveryReport report={d.report} /><div className="row" style={{ marginTop: 14 }}><Link to="/analytics" className="btn">Open Recovery Analytics</Link>{d.report && <button className="btn" onClick={exportReport}>Export report JSON</button>}</div></div>}
          </>
        )}
      </div>
    </>
  );
}

const HELP = {
  INJECTING: 'The failure is being injected into the selected node.',
  DETECTING: 'The node stopped answering. After a few missed heartbeats it becomes SUSPECTED; then the manager checks it still sees a majority of the cluster and asks other nodes whether they can reach it.',
  FAILOVER: 'The node was declared FAILED. It leaves the hash ring so its replicas become primaries, then missing copies are re-created on the remaining nodes.',
  AWAITING_RESTART: 'Traffic is being served by the healthy nodes. Press “Restart” when you are ready (or wait for auto-restart).',
  RECOVERING: 'The node answers again. The cluster marks it RECOVERING, copies the newest version of every key it should own, and only then lets it serve requests.',
};
