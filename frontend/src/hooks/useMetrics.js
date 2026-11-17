import usePolling from './usePolling';
import { monitoringService } from '../services/monitoringService';
import { useCluster } from '../context/ClusterContext';

export default function useMetrics(seconds = 60) {
  const { refreshMs } = useCluster();
  const traffic = usePolling(() => monitoringService.traffic(seconds), refreshMs, [seconds]);
  const performance = usePolling(() => monitoringService.cachePerformance(seconds), refreshMs, [seconds]);
  return { traffic, performance };
}
