import { Check, CheckCheck, X } from 'lucide-react';
import StatusBadge from '../common/StatusBadge';
import { fmtDateTime } from '../../utils/format';

export default function RecommendationCard({ rec, canAct, busy, onAcknowledge, onResolve, onDismiss }) {
  const closed = rec.status === 'RESOLVED' || rec.status === 'DISMISSED';
  return (
    <div className="card" style={{ borderLeft: `4px solid ${rec.severity === 'CRITICAL' ? 'var(--red)' : rec.severity === 'WARNING' ? '#e8890c' : 'var(--primary)'}` }}>
      <div className="spread" style={{ alignItems: 'flex-start' }}>
        <div className="row"><StatusBadge status={rec.severity} /><span className="badge gray">{rec.category.replace('_', ' ')}</span><StatusBadge status={rec.status} /></div>
        <span className="small muted">{fmtDateTime(rec.createdAt)}</span>
      </div>
      <h2 style={{ margin: '10px 0 4px' }}>{rec.title}</h2>
      <p>{rec.description}</p>
      <div className="stack" style={{ gap: 8, marginTop: 12 }}>
        <div><div className="small muted">Supporting metric</div><code>{rec.supportingMetric}</code></div>
        <div><div className="small muted">Why it matters</div><div>{rec.reason}</div></div>
        <div><div className="small muted">Recommended action</div><div style={{ fontWeight: 550 }}>{rec.recommendedAction}</div></div>
      </div>
      {canAct && !closed && (
        <div className="row" style={{ marginTop: 14 }}>
          {rec.status === 'OPEN' && <button className="btn small" disabled={busy} onClick={() => onAcknowledge(rec)}><Check size={14} />Acknowledge</button>}
          <button className="btn small primary" disabled={busy} onClick={() => onResolve(rec)}><CheckCheck size={14} />Resolve</button>
          <button className="btn small ghost" disabled={busy} onClick={() => onDismiss(rec)}><X size={14} />Dismiss</button>
        </div>
      )}
    </div>
  );
}
