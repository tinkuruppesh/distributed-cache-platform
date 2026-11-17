import usePolling from './usePolling';
import { clusterService } from '../services/clusterService';
import { monitoringService } from '../services/monitoringService';
import { useCluster } from '../context/ClusterContext';

/** Overview + node list, refreshed at the interval configured in Settings. */
export default function useClusterStatus() {
  const { refreshMs } = useCluster();
  const overview = usePolling(() => monitoringService.overview(), refreshMs);
  const nodes = usePolling(() => clusterService.nodes(), refreshMs);
  return { overview, nodes };
}
