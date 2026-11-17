export default function LoadingState({ label = 'Loading…' }) {
  return (
    <div className="state">
      <div className="spinner" />
      <span>{label}</span>
    </div>
  );
}
