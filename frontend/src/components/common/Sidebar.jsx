import { NavLink } from 'react-router-dom';
import { Activity, BarChart3, FlaskConical, Gauge, GitBranch, Lightbulb, Orbit, ScrollText, Search, Server, Settings } from 'lucide-react';

const ITEMS = [
  { to: '/', label: 'Overview', icon: Gauge, end: true },
  { to: '/nodes', label: 'Cluster Nodes', icon: Server },
  { to: '/explorer', label: 'Cache Explorer', icon: Search },
  { to: '/hash-ring', label: 'Hash Ring', icon: Orbit },
  { to: '/replication', label: 'Replication', icon: GitBranch },
  { to: '/monitoring', label: 'Live Monitoring', icon: Activity },
  { to: '/simulation', label: 'Failure Simulation', icon: FlaskConical },
  { to: '/analytics', label: 'Recovery Analytics', icon: BarChart3 },
  { to: '/advisor', label: 'Optimization Advisor', icon: Lightbulb },
  { to: '/audit', label: 'Audit Logs', icon: ScrollText },
  { to: '/settings', label: 'Settings', icon: Settings },
];

export default function Sidebar({ open, onNavigate }) {
  return (
    <aside className={`sidebar ${open ? 'open' : ''}`}>
      <div className="brand">
        <div className="logo"><Orbit size={18} /></div>
        <div>Cache Platform<small>Self-healing cluster</small></div>
      </div>
      <nav className="nav">
        {ITEMS.map(({ to, label, icon: Icon, end }) => (
          <NavLink key={to} to={to} end={end} onClick={onNavigate} className={({ isActive }) => (isActive ? 'active' : '')}>
            <Icon size={17} />{label}
          </NavLink>
        ))}
      </nav>
      <div className="sidebar-foot">Custom distributed cache engine<br />Java 21 · Spring Boot · React</div>
    </aside>
  );
}
