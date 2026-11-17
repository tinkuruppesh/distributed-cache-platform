import { LogOut, Menu, RefreshCw } from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { useCluster } from '../../context/ClusterContext';
import useClusterStatus from '../../hooks/useClusterStatus';
import StatusBadge from './StatusBadge';

export default function Topbar({ onMenu }) {
  const { user, logout } = useAuth();
  const { refreshMs } = useCluster();
  const { overview } = useClusterStatus();
  const o = overview.data;
  return (
    <header className="topbar">
      <div className="row">
        <button className="btn ghost menu-btn" onClick={onMenu} aria-label="Menu"><Menu size={18} /></button>
        {o ? (
          <div className="row">
            <StatusBadge status={o.health} label={`Cluster ${o.health}`} />
            <span className="muted small">{o.activeNodes}/{o.totalNodes} nodes active</span>
          </div>
        ) : <span className="muted small">Connecting…</span>}
      </div>
      <div className="row">
        <span className="muted small row" title="Dashboard refresh interval (Settings)"><RefreshCw size={13} />every {(refreshMs / 1000).toFixed(refreshMs % 1000 ? 1 : 0)}s</span>
        <div className="user-chip">
          <div className="avatar">{user?.username?.[0]?.toUpperCase()}</div>
          <div><div style={{ fontWeight: 600 }}>{user?.username}</div><div className="muted small">{user?.role}</div></div>
        </div>
        <button className="btn small" onClick={logout}><LogOut size={14} />Sign out</button>
      </div>
    </header>
  );
}
