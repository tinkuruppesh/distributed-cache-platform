const CATEGORIES = ['PERFORMANCE', 'MEMORY', 'SCALABILITY', 'REPLICATION', 'HOT_KEYS', 'CONFIGURATION'];
const STATUSES = ['OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'DISMISSED'];

export default function RecommendationFilters({ category, status, onCategory, onStatus }) {
  return (
    <div className="stack" style={{ gap: 10 }}>
      <div className="chips">
        <button className={`chip ${!category ? 'active' : ''}`} onClick={() => onCategory('')}>All categories</button>
        {CATEGORIES.map((c) => <button key={c} className={`chip ${category === c ? 'active' : ''}`} onClick={() => onCategory(c)}>{c.replace('_', ' ')}</button>)}
      </div>
      <div className="chips">
        <button className={`chip ${!status ? 'active' : ''}`} onClick={() => onStatus('')}>Any status</button>
        {STATUSES.map((s) => <button key={s} className={`chip ${status === s ? 'active' : ''}`} onClick={() => onStatus(s)}>{s}</button>)}
      </div>
    </div>
  );
}
