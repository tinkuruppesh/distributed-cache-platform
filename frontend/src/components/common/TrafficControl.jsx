import { useEffect, useRef, useState } from 'react';
import { Play, Square, Database, Trash2 } from 'lucide-react';
import { cacheService } from '../../services/cacheService';
import { errorMessage } from '../../services/api';
import { cleanupGeneratedKeys, runGeneratedWorkload } from '../../services/workloadRunner';
import { useAuth } from '../../context/AuthContext';

const OPERATIONS = ['GET', 'SET', 'UPDATE', 'DELETE'];
const formatMs = (value) => value === null || value === undefined ? 'N/A' : `${value.toFixed(2)} ms`;

/** Issues generated workload through the real cache API, tracking only successfully created keys. */
export default function TrafficControl({ compact, showSampleData = false, generateLabel = 'Generate traffic' }) {
  const { hasRole } = useAuth();
  const allowed = hasRole('ADMIN', 'OPERATOR');
  const [running, setRunning] = useState(false);
  const [busy, setBusy] = useState(false);
  const [count, setCount] = useState(60);
  const [rate, setRate] = useState(10);
  const [duration, setDuration] = useState(10);
  const [distribution, setDistribution] = useState({ GET: 70, SET: 15, UPDATE: 10, DELETE: 5 });
  const [progress, setProgress] = useState(null);
  const [generatedKeys, setGeneratedKeys] = useState([]);
  const [msg, setMsg] = useState(null);
  const runningRef = useRef(false);
  const busyRef = useRef(false);
  const ownedKeys = useRef(new Set());

  const publishOwnedKeys = () => setGeneratedKeys([...ownedKeys.current]);

  useEffect(() => () => { runningRef.current = false; }, []);

  const loadSample = async () => {
    if (busyRef.current || runningRef.current) return;
    busyRef.current = true; setBusy(true); setMsg(null);
    try {
      const prefix = `monitoring-demo-sample-${crypto.randomUUID()}`;
      const keys = Array.from({ length: 20 }, (_, i) => `${prefix}-${i}`);
      const results = await Promise.allSettled(keys.map((key, i) => cacheService.set(key, JSON.stringify({ generated: true, sample: true, index: i }), 600)));
      const created = keys.filter((_, index) => results[index].status === 'fulfilled');
      created.forEach((key) => ownedKeys.current.add(key));
      publishOwnedKeys();
      const failures = keys.length - created.length;
      setMsg(failures
        ? { ok: false, text: `${created.length}/20 generated sample entries were created; ${failures} writes failed. Successful entries remain listed for cleanup.` }
        : { ok: true, text: 'Created 20 generated sample entries through the cache API. They are separate from existing keys.' });
    } catch (e) { setMsg({ ok: false, text: errorMessage(e) }); }
    finally { busyRef.current = false; setBusy(false); }
  };

  const start = async () => {
    if (busyRef.current || runningRef.current) return;
    const totalWeight = OPERATIONS.reduce((sum, op) => sum + Number(distribution[op] || 0), 0);
    if (!Number.isInteger(Number(count)) || count < 1 || count > 500 || !Number.isInteger(Number(rate)) || rate < 1 || rate > 50 ||
        !Number.isInteger(Number(duration)) || duration < 1 || duration > 300 || count > rate * duration || totalWeight <= 0 ||
        OPERATIONS.some((op) => Number(distribution[op]) < 0 || Number(distribution[op]) > 100)) {
      setMsg({ ok: false, text: 'Use 1–500 operations, 1–50 requests/second, 1–300 seconds, count ≤ rate × duration, and a positive operation distribution.' });
      return;
    }

    busyRef.current = true;
    runningRef.current = true;
    setMsg(null); setBusy(true); setRunning(true);
    setProgress({ attempted: 0, requested: Number(count), successful: 0, failed: 0, hits: 0, misses: 0,
      seedWritesAttempted: 0, seedWritesSucceeded: 0, seedWritesFailed: 0, averageLatencyMs: null, operationCounts: null });
    const prefix = `monitoring-demo-${crypto.randomUUID()}`;
    const runOwnedKeys = new Set();
    try {
      const result = await runGeneratedWorkload({
        cache: cacheService,
        prefix,
        count,
        rate,
        duration,
        distribution,
        shouldStop: () => !runningRef.current,
        onProgress: setProgress,
        onGeneratedKeysChange: (keys) => {
          const currentRunKeys = new Set(keys);
          for (const key of runOwnedKeys) if (!currentRunKeys.has(key)) ownedKeys.current.delete(key);
          for (const key of currentRunKeys) ownedKeys.current.add(key);
          runOwnedKeys.clear();
          currentRunKeys.forEach((key) => runOwnedKeys.add(key));
          publishOwnedKeys();
        },
      });
      const outcome = result.stopped ? 'stopped' : 'finished';
      const firstErrors = result.errors.map((item) => `${item.operation}: ${item.message}`).join(' · ');
      const text = `Workload ${outcome}: ${result.attempted}/${result.requested} requested operations attempted, ${result.successful} succeeded, ${result.failed} failed; ${result.hits} GET hits, ${result.misses} GET misses. Client-observed operation latency: ${formatMs(result.averageLatencyMs)} from ${result.latencySamples} samples. Seed writes: ${result.seedWritesSucceeded}/${result.seedWritesAttempted} successful (additional to the requested count); ${result.preflightChecks} existence checks were setup traffic.`;
      setMsg({ ok: result.failed === 0 && result.seedWritesFailed === 0 && !result.stopped, text: firstErrors ? `${text} Recent errors: ${firstErrors}` : text });
    } catch (e) {
      setMsg({ ok: false, text: `Workload setup failed: ${errorMessage(e)}. Successfully created generated keys remain available for cleanup.` });
    } finally {
      runningRef.current = false; busyRef.current = false; setRunning(false); setBusy(false);
    }
  };

  const stop = () => { runningRef.current = false; setRunning(false); };

  const cleanup = async () => {
    if (busyRef.current || runningRef.current) return;
    busyRef.current = true; setBusy(true); setMsg(null);
    try {
      const result = await cleanupGeneratedKeys(cacheService, ownedKeys.current);
      ownedKeys.current = new Set(result.remaining);
      publishOwnedKeys();
      setMsg(result.remaining.length
        ? { ok: false, text: `${result.remaining.length} generated entries could not be deleted; they remain listed for another cleanup attempt.` }
        : { ok: true, text: `Cleanup completed: ${result.removed} entries created by this page session were deleted or confirmed already absent.` });
    } catch (e) { setMsg({ ok: false, text: errorMessage(e) }); }
    finally { busyRef.current = false; setBusy(false); }
  };

  if (!allowed) return <span className="muted small">Operator or admin role required to generate cache workload.</span>;
  return (
    <div className={compact ? 'stack' : 'stack'} style={{ gap: 10, alignItems: 'stretch' }}>
      <div className="form-grid">
        <label className="field">Requests<input type="number" min="1" max="500" value={count} disabled={running} onChange={(e) => setCount(Number(e.target.value))} /></label>
        <label className="field">Rate (requests / second)<input type="number" min="1" max="50" value={rate} disabled={running} onChange={(e) => setRate(Number(e.target.value))} /></label>
        <label className="field">Duration (seconds)<input type="number" min="1" max="300" value={duration} disabled={running} onChange={(e) => setDuration(Number(e.target.value))} /></label>
      </div>
      <div className="row">
        {OPERATIONS.map((op) => <label className="field" key={op} style={{ width: 100 }}>{op} %<input type="number" min="0" max="100" value={distribution[op]} disabled={running} onChange={(e) => setDistribution((d) => ({ ...d, [op]: Number(e.target.value) }))} /></label>)}
        <span className="muted small">Weights are normalized. GET uses measured hit/miss results; generated keys use a unique prefix.</span>
      </div>
      <div className="row">
        {showSampleData && <button className="btn" onClick={loadSample} disabled={busy || running}><Database size={14} />Create generated sample entries</button>}
        <button className={`btn ${running ? 'danger' : 'primary'}`} onClick={running ? stop : start} disabled={busy && !running}>
          {running ? <><Square size={14} />Stop workload</> : <><Play size={14} />{generateLabel}</>}
        </button>
        <button className="btn" onClick={cleanup} disabled={busy || running || generatedKeys.length === 0}><Trash2 size={14} />Clean generated entries ({generatedKeys.length})</button>
      </div>
      {progress && <div className="small muted">
        Workload operations attempted: {progress.attempted} / {progress.requested}; succeeded {progress.successful}, failed {progress.failed}, hits {progress.hits}, misses {progress.misses}.<br />
        Seed writes are additional setup traffic: {progress.seedWritesSucceeded} succeeded / {progress.seedWritesAttempted} attempted. Average client-observed latency: {formatMs(progress.averageLatencyMs)}.
        {progress.operationCounts && <><br />GET {progress.operationCounts.GET.succeeded}/{progress.operationCounts.GET.attempted} succeeded · SET {progress.operationCounts.SET.succeeded}/{progress.operationCounts.SET.attempted} · UPDATE {progress.operationCounts.UPDATE.succeeded}/{progress.operationCounts.UPDATE.attempted} · DELETE {progress.operationCounts.DELETE.succeeded}/{progress.operationCounts.DELETE.attempted}</>}
      </div>}
      <div className="small muted">Replication acknowledgements are backend-measured cluster totals shown in Live Monitoring. This workload does not treat replica placement lists as acknowledgements.</div>
      {msg && <div className={`alert ${msg.ok ? 'ok' : 'error'}`}>{msg.text}</div>}
    </div>
  );
}
