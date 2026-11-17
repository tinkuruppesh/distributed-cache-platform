import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { clusterService } from '../services/clusterService';
import { useAuth } from './AuthContext';

const ClusterContext = createContext({ refreshMs: 3000 });

/** Cluster-wide facts the whole UI needs: settings, whether simulation is enabled, dashboard refresh interval. */
export function ClusterProvider({ children }) {
  const { user } = useAuth();
  const [info, setInfo] = useState(null);

  const reload = useCallback(async () => {
    try { setInfo(await clusterService.cluster()); } catch { /* shown by pages */ }
  }, []);

  useEffect(() => { if (user) reload(); else setInfo(null); }, [user, reload]);

  const value = useMemo(() => ({
    info,
    settings: info?.settings,
    simulationEnabled: !!info?.simulationEnabled,
    embeddedNodes: !!info?.embeddedNodes,
    refreshMs: Math.max(1000, info?.settings?.monitoringRefreshMs || 3000),
    reload,
  }), [info, reload]);

  return <ClusterContext.Provider value={value}>{children}</ClusterContext.Provider>;
}

export const useCluster = () => useContext(ClusterContext);
