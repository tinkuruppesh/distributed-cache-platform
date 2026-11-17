import { fmtTime } from '../../utils/format';

const COLOR = { ACTIVE: '#22a35a', SUSPECTED: '#e8890c', FAILED: '#dc3b3b', RECOVERING: '#e8b00c', JOINING: '#e8b00c', MAINTENANCE: '#7a8bb0', REMOVED: '#b8c0d3' };

/** One coloured strip per node showing its state over time (sampled every 2s by the backend). */
export default function NodeHealthTimeline({ history, nodeIds }) {
  if (!history || history.length === 0) return <div className="state">Collecting samples…</div>;
  return (
    <div className="stack" style={{ gap: 8 }}>
      {nodeIds.map((id) => (
        <div key={id} className="row" style={{ flexWrap: 'nowrap' }}>
          <div style={{ width: 70, fontWeight: 600, fontSize: 12.5 }}>{id}</div>
          <div style={{ display: 'flex', flex: 1, height: 18, borderRadius: 5, overflow: 'hidden', background: '#eceff5' }}>
            {history.map((p) => (
              <div key={p.time} title={`${id}: ${p.states[id] || '–'} @ ${fmtTime(p.time)}`} style={{ flex: 1, background: COLOR[p.states[id]] || '#eceff5' }} />
            ))}
          </div>
        </div>
      ))}
      <div className="row small muted">
        {Object.entries(COLOR).slice(0, 5).map(([k, c]) => <span key={k} className="row" style={{ gap: 5 }}><span className="node-dot" style={{ background: c }} />{k}</span>)}
      </div>
    </div>
  );
}
