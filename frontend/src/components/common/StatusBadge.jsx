const TONE = {
  ACTIVE: 'green', HEALTHY: 'green', COMPLETED: 'green', RESOLVED: 'green', ALIVE: 'green', SUCCESS: 'green', INFO: 'blue',
  SUSPECTED: 'orange', DEGRADED: 'orange', WARNING: 'orange', RECOVERING: 'orange', JOINING: 'orange', MAINTENANCE: 'blue',
  ACKNOWLEDGED: 'blue', OPEN: 'orange', DETECTING: 'orange', FAILOVER: 'orange', AWAITING_RESTART: 'blue', INJECTING: 'orange',
  FAILED: 'red', CRITICAL: 'red', DEAD: 'red', SUSPECT: 'orange', DISMISSED: 'gray', REMOVED: 'gray', NONE: 'gray',
};

export default function StatusBadge({ status, label }) {
  const s = String(status || 'UNKNOWN').toUpperCase();
  return (
    <span className={`badge ${TONE[s] || 'gray'}`}>
      <span className="dot" />
      {label || s.replaceAll('_', ' ')}
    </span>
  );
}
