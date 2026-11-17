import { useState } from 'react';
import { RefreshCw } from 'lucide-react';
import PageHeader from '../components/common/PageHeader';
import LoadingState from '../components/common/LoadingState';
import ErrorState from '../components/common/ErrorState';
import RecommendationCard from '../components/advisor/RecommendationCard';
import RecommendationFilters from '../components/advisor/RecommendationFilters';
import usePolling from '../hooks/usePolling';
import { advisorService } from '../services/advisorService';
import { errorMessage } from '../services/api';
import { useAuth } from '../context/AuthContext';
import { fmtDateTime } from '../utils/format';
import { monitoringService } from '../services/monitoringService';

export default function OptimizationAdvisor() {
  const { hasRole } = useAuth();
  const [category, setCategory] = useState('');
  const [status, setStatus] = useState('');
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState(null);
  const recs = usePolling(() => advisorService.list(status, category), 5000, [status, category]);
  const coverage = usePolling(() => monitoringService.cachePerformance(5), 5000);

  const act = async (fn, rec) => {
    setBusy(true); setMsg(null);
    try { await fn(rec.id); await recs.reload(); } catch (e) { setMsg({ ok: false, text: errorMessage(e) }); }
    setBusy(false);
  };
  const run = async () => {
    setBusy(true);
    try {
      const r = await advisorService.run();
      if (typeof r.successful !== 'boolean') {
        setMsg({ ok: false, text: 'The backend returned an outdated Advisor analysis response. Update the management API to report analysis failures separately from zero new recommendations.' });
      } else if (!r.successful) {
        const failedRules = (r.ruleFailures || []).map((failure) => failure.rule).join(', ');
        const reason = r.error || (failedRules ? `Failed rules: ${failedRules}.` : 'One or more analysis steps failed.');
        setMsg({ ok: false, text: `Analysis did not complete successfully. ${r.created ? `${r.created} recommendation(s) were created before the failure. ` : ''}${reason}` });
      } else {
        setMsg({ ok: true, text: r.created ? `${r.created} new recommendation(s).` : 'Analysis completed successfully; no new recommendations matched the current measurements.' });
      }
      await recs.reload();
    }
    catch (e) { setMsg({ ok: false, text: errorMessage(e) }); }
    setBusy(false);
  };

  if (recs.loading && !recs.data) return <LoadingState />;
  if (recs.error && !recs.data) return <ErrorState message={recs.error} onRetry={recs.reload} />;

  return (
    <>
      <PageHeader title="Optimization Advisor" subtitle="Rule-based recommendations computed from real cluster measurements (no AI, every rule is explainable)."
        actions={hasRole('ADMIN', 'OPERATOR') && <button className="btn" onClick={run} disabled={busy}><RefreshCw size={14} />Run analysis now</button>} />
      <div className="stack">
        <div className="card"><RecommendationFilters category={category} status={status} onCategory={setCategory} onStatus={setStatus} /></div>
        {msg && <div className={`alert ${msg.ok ? 'ok' : 'error'}`}>{msg.text}</div>}
        {recs.error
          ? <div className="alert warn" role="status">Recommendation refresh failed: {recs.error}. Showing the last successful results from {fmtDateTime(recs.lastSuccessAt)}.</div>
          : <div className="muted small">Last successful recommendation refresh: {fmtDateTime(recs.lastSuccessAt)}</div>}
        {coverage.data?.hotKeyTracking && (() => {
          const tracking = coverage.data.hotKeyTracking;
          return <div className={`alert ${tracking.trackingLimitReached ? 'warn' : 'info'}`} role="status">
            Hot-key analysis uses measured keyed GET reads and tracks up to {tracking.trackingLimit.toLocaleString()} distinct keys.
            {tracking.trackingLimitReached
              ? ` The limit has been reached: ${tracking.trackedReadAccesses.toLocaleString()} tracked reads are incomplete, so hot-key recommendations are withheld.`
              : ` ${tracking.trackedDistinctKeys.toLocaleString()} distinct keys and ${tracking.trackedReadAccesses.toLocaleString()} keyed reads are currently tracked.`}
            {coverage.error && ` Tracking coverage refresh failed; these figures may be stale (last success ${fmtDateTime(coverage.lastSuccessAt)}).`}
          </div>;
        })()}
        {!coverage.data && coverage.error && <div className="alert warn" role="status">Hot-key tracking coverage is unavailable: {coverage.error}.</div>}
        {recs.data.length === 0 ? (
          <div className="card state">No recommendations match. Rules only fire when there is enough measured data — generate traffic from the Overview page, or run the analysis again later.</div>
        ) : (
          <div className="grid cols-2">
            {recs.data.map((r) => (
              <RecommendationCard key={r.id} rec={r} busy={busy} canAct={hasRole('ADMIN', 'OPERATOR')}
                onAcknowledge={(x) => act(advisorService.acknowledge, x)} onResolve={(x) => act(advisorService.resolve, x)} onDismiss={(x) => act(advisorService.dismiss, x)} />
            ))}
          </div>
        )}
      </div>
    </>
  );
}
