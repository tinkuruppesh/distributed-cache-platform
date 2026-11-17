import { Navigate, Route, Routes } from 'react-router-dom';
import ProtectedRoute from './ProtectedRoute';
import AppLayout from '../components/common/AppLayout';
import Login from '../pages/Login';
import Overview from '../pages/Overview';
import ClusterNodes from '../pages/ClusterNodes';
import CacheExplorer from '../pages/CacheExplorer';
import HashRing from '../pages/HashRing';
import Replication from '../pages/Replication';
import LiveMonitoring from '../pages/LiveMonitoring';
import FailureSimulation from '../pages/FailureSimulation';
import RecoveryAnalytics from '../pages/RecoveryAnalytics';
import OptimizationAdvisor from '../pages/OptimizationAdvisor';
import AuditLogs from '../pages/AuditLogs';
import Settings from '../pages/Settings';

export default function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route element={<ProtectedRoute><AppLayout /></ProtectedRoute>}>
        <Route path="/" element={<Overview />} />
        <Route path="/nodes" element={<ClusterNodes />} />
        <Route path="/explorer" element={<CacheExplorer />} />
        <Route path="/hash-ring" element={<HashRing />} />
        <Route path="/replication" element={<Replication />} />
        <Route path="/monitoring" element={<LiveMonitoring />} />
        <Route path="/simulation" element={<FailureSimulation />} />
        <Route path="/analytics" element={<RecoveryAnalytics />} />
        <Route path="/advisor" element={<OptimizationAdvisor />} />
        <Route path="/audit" element={<AuditLogs />} />
        <Route path="/settings" element={<Settings />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
