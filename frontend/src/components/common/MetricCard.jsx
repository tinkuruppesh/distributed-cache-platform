export default function MetricCard({ label, value, hint, icon: Icon, tone }) {
  return (
    <div className={`card metric ${tone ? `tone-${tone}` : ''}`}>
      <div className="label">{Icon && <Icon size={15} />}{label}</div>
      <div className="value">{value}</div>
      {hint && <div className="hint">{hint}</div>}
    </div>
  );
}
