import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Activity, AlertTriangle, CheckCircle2, Clock, Cpu, Gauge, Percent, Server, XCircle } from 'lucide-react';
import PageHeader from '../components/common/PageHeader';
import MetricCard from '../components/common/MetricCard';
import StatusBadge from '../components/common/StatusBadge';
import LoadingState from '../components/common/LoadingState';
import ErrorState from '../components/common/ErrorState';
import TrafficControl from '../components/common/TrafficControl';
import RequestTrafficChart from '../components/charts/RequestTrafficChart';
import CacheHitMissChart from '../components/charts/CacheHitMissChart';
import useClusterStatus from '../hooks/useClusterStatus';
import useMetrics from '../hooks/useMetrics';
import usePolling from '../hooks/usePolling';
import { useCluster } from '../context/ClusterContext';
import { advisorService } from '../services/advisorService';
import { clusterService } from '../services/clusterService';
import { fmtDateTime, fmtDuration, fmtMs, fmtNum, fmtPct, fmtTime, nodeColor } from '../utils/format';

export default function Overview() {
  const { refreshMs } = useCluster();
  const { overview, nodes } = useClusterStatus();
  const [range, setRange] = useState(60);
  const [eventSeverity, setEventSeverity] = useState('ALL');
  const { traffic, performance } = useMetrics(range);
  const events = usePolling(() => clusterService.events(8), refreshMs);
  const alerts = usePolling(() => advisorService.list('OPEN'), Math.max(refreshMs, 5000));
  const o = overview.data;
  const nodeList = nodes.data;
  const counters = performance.data?.counters;
  const hits = counters?.hits ?? 0;
  const misses = counters?.misses ?? 0;
  const readCount = hits + misses;
  const hitRatio = performance.data && readCount >= 0 && (hits + misses) > 0 ? (100 * hits) / readCount : null;
  const latency = o?.latency;
  const nodeCounts = (nodeList || []).reduce((counts, node) => {
    counts.total += 1;
    if (node.state === 'ACTIVE') counts.active += 1;
    if (node.state === 'FAILED') counts.failed += 1;
    if (node.state === 'RECOVERING' || node.state === 'JOINING') counts.recovering += 1;
    return counts;
  }, { total: 0, active: 0, failed: 0, recovering: 0 });
  const ids = (nodeList || []).map((node) => node.id);
  const trafficHasRequests = traffic.data?.some((point) => point.requests > 0);
  const performanceHasReads = performance.data?.timeline?.some((point) => point.hits > 0 || point.misses > 0);
  const visibleEvents = (events.data || []).filter((event) => eventSeverity === 'ALL' || (event.severity === 'CRITICAL' ? 'ERROR' : event.severity) === eventSeverity);
  const trafficSeries = traffic.data || [];
  const requests = trafficSeries.map((point) => point.requests);
  const totalRequestsInRange = requests.reduce((sum, value) => sum + value, 0);
  const rangeAverage = requests.length ? totalRequestsInRange / requests.length : null;
  const rangePeak = requests.length ? Math.max(...requests) : null;
  const latestRps = requests.length ? requests[requests.length - 1] : null;

  return (
    <>
      <PageHeader title="Overview" subtitle="Live health and performance of the distributed cache cluster."
        actions={o ? <StatusBadge status={o.health} label={`Cluster ${o.health}`} /> : undefined} />
      <div className="stack">
        <section aria-label="Cluster measurements">
          <PollContent poll={overview} label="Cluster measurements">
            {o && (
              <div className="grid metrics">
                <MetricCard icon={Gauge} label="Cluster health" value={o.health}
                  tone={o.health === 'HEALTHY' ? 'green' : o.health === 'DEGRADED' ? 'orange' : 'red'}
                  hint={healthReason(o)} />
                <MetricCard icon={Activity} label="Requests / second" value={latestRps === null ? 'Unavailable' : fmtNum(latestRps)}
                  hint={`Current · avg ${rangeAverage === null ? 'Unavailable' : rangeAverage.toFixed(1)} · peak ${rangePeak === null ? 'Unavailable' : fmtNum(rangePeak)} over ${rangeLabel(range)}`} />
                <MetricCard icon={Clock} label="Average latency" value={latency?.samples ? fmtMs(latency.avgMs, 2) : 'Unavailable'}
                  hint={`${fmtNum(latency?.samples || 0)} measured samples`} />
                <MetricCard icon={Clock} label="P95 latency" value={latency?.samples ? fmtMs(latency.p95Ms, 2) : 'Unavailable'}
                  hint={`${fmtNum(latency?.samples || 0)} measured samples`} />
                <MetricCard icon={Clock} label="P99 latency" value={latency?.samples ? fmtMs(latency.p99Ms, 2) : 'Unavailable'}
                  hint={`${fmtNum(latency?.samples || 0)} measured samples`} />
                <MetricCard icon={Cpu} label="Memory utilization" value={fmtPct(o.memoryUtilizationPct)}
                  tone={o.memoryUtilizationPct >= 80 ? 'orange' : undefined} hint={`${fmtNum(o.totalEntries)} entries stored`} />
              </div>
            )}
          </PollContent>
        </section>

        <section aria-label="Node status">
          <PollContent poll={nodes} label="Node status">
            {nodeList && (
              <>
                <div className="grid metrics">
                  <MetricCard icon={Server} label="Total nodes" value={nodeCounts.total} />
                  <MetricCard icon={CheckCircle2} label="Active nodes" value={nodeCounts.active} tone="green" />
                  <MetricCard icon={XCircle} label="Failed nodes" value={nodeCounts.failed} tone={nodeCounts.failed ? 'red' : undefined} />
                  <MetricCard icon={Activity} label="Recovering nodes" value={nodeCounts.recovering} tone={nodeCounts.recovering ? 'orange' : undefined} />
                </div>
                <div className="card" style={{ marginTop: 16 }}>
                  <div className="card-title"><h2>Node status</h2><Link to="/nodes" className="small">View all</Link></div>
                  {nodeList.length ? (
                    <div className="stack" style={{ gap: 8 }}>
                      {nodeList.map((node) => (
                        <div key={node.id} className="spread">
                          <span className="row" style={{ gap: 8 }}>
                            <span className="node-dot" style={{ background: nodeColor(node.id, ids) }} />
                            <b>{node.id}</b><span className="muted small mono">{node.host}:{node.port}</span>
                          </span>
                          <span className="muted small">{node.role && node.role !== 'NONE' ? node.role : '—'}</span>
                          <span className="muted small">{fmtDuration(node.uptimeMs)} · {fmtNum(node.entries)} entries · {fmtPct(node.memoryPct)}</span>
                          <StatusBadge status={node.state} />
                        </div>
                      ))}
                    </div>
                  ) : <div className="state">No nodes are registered in the cluster.</div>}
                </div>
              </>
            )}
          </PollContent>
        </section>

        <div className="grid cols-2">
          <section className="card" aria-label="Request traffic">
            <div className="card-title"><h2>Request traffic</h2><div className="row" aria-label="Traffic time range">
              {[60, 300, 900].map((seconds) => <button key={seconds} type="button" className={`btn small ${range === seconds ? 'primary' : ''}`} aria-pressed={range === seconds} onClick={() => setRange(seconds)}>{rangeLabel(seconds)}</button>)}
            </div></div>
            <PollContent poll={traffic} label="Request traffic">
              {trafficHasRequests
                ? <><RequestTrafficChart data={traffic.data} /><div className="muted small">Measured requests in range: {fmtNum(totalRequestsInRange)}</div></>
                : <div className="state">No requests were measured in this time window.</div>}
            </PollContent>
          </section>
          <section className="card" aria-label="Cache performance">
            <div className="card-title"><h2>Cache performance</h2><span className="muted small">measured hits vs misses</span></div>
            <PollContent poll={performance} label="Cache performance">
              <div className="row" style={{ marginBottom: 12 }}>
                <MetricCard icon={Percent} label="Cache hit ratio" value={hitRatio === null ? 'Unavailable' : fmtPct(hitRatio)} tone="blue"
                  hint={readCount ? `${fmtNum(hits)} hits / ${fmtNum(readCount)} reads` : 'No cache reads measured yet'} />
              </div>
              <div className="row" style={{ gap: 18, marginBottom: 10 }}><span>Hits: <b>{performance.data ? fmtNum(hits) : '—'}</b></span><span>Misses: <b>{performance.data ? fmtNum(misses) : '—'}</b></span><span>Total reads: <b>{performance.data ? fmtNum(readCount) : '—'}</b></span></div>
              {performanceHasReads
                ? <CacheHitMissChart data={performance.data.timeline} />
                : <div className="state">No cache reads were measured in this time window.</div>}
            </PollContent>
          </section>
        </div>

        <div className="grid cols-2">
          <section className="card" aria-label="Recent cluster events">
            <div className="card-title"><h2>Recent cluster events</h2><Link to="/monitoring" className="small">Monitor</Link></div>
            <PollContent poll={events} label="Recent cluster events">
              <label className="muted small">Severity <select value={eventSeverity} onChange={(e) => setEventSeverity(e.target.value)}><option value="ALL">All</option><option value="INFO">Info</option><option value="WARNING">Warning</option><option value="ERROR">Error</option></select></label>
              {events.data && (visibleEvents.length ? (
                <ul className="timeline">
                  {visibleEvents.map((event) => (
                    <li key={event.id}>
                      <span className="t">{fmtDateTime(event.time)}</span>
                      <span className={`bullet ${event.severity === 'CRITICAL' || event.severity === 'ERROR' ? 'red' : event.severity === 'WARNING' ? 'orange' : 'green'}`} />
                      <span><b>{event.nodeId || 'Cluster'}</b> · {event.message}<span className="muted small"> · {event.severity === 'CRITICAL' ? 'ERROR' : event.severity}</span></span>
                    </li>
                  ))}
                </ul>
              ) : <div className="state">{events.data.length ? 'No events match this severity.' : 'No cluster events have been recorded.'}</div>)}
            </PollContent>
          </section>
          <section className="card" aria-label="Active advisor recommendations">
            <div className="card-title"><h2>Active recommendations</h2><Link to="/advisor" className="small">Advisor</Link></div>
            <PollContent poll={alerts} label="Active recommendations">
              {alerts.data && (alerts.data.length ? (
                <div className="stack" style={{ gap: 10 }}>
                  {alerts.data.slice(0, 3).map((alert) => (
                    <div key={alert.id} className="row" style={{ alignItems: 'flex-start', flexWrap: 'nowrap' }}>
                      <AlertTriangle size={16} color={alert.severity === 'CRITICAL' ? '#c62828' : '#c2640a'} style={{ marginTop: 2, flexShrink: 0 }} />
                      <span><b>{alert.title}</b><span className="muted small"> · {alert.severity || 'INFO'}</span><div className="small">{alert.reason}</div><div className="muted small">Suggested: {alert.recommendedAction}</div></span>
                    </div>
                  ))}
                </div>
              ) : <div className="state">No active recommendations.</div>)}
            </PollContent>
          </section>
        </div>

        <div className="card">
          <div className="card-title"><h2>Quick actions</h2></div>
          <div className="stack" style={{ gap: 12 }}>
            <TrafficControl showSampleData={false} generateLabel="Generate demo cache traffic" />
            <div className="muted small">Generated workload sends real cache reads and writes and is included in measured metrics.</div>
            <div className="row"><Link className="btn small" to="/nodes">Cluster nodes</Link><Link className="btn small" to="/explorer">Explore cache entries</Link><Link className="btn small" to="/simulation">Failure simulation</Link></div>
          </div>
        </div>
      </div>
    </>
  );
}

function PollContent({ poll, label, children }) {
  if (poll.loading && poll.data === null) return <LoadingState label={`Loading ${label.toLowerCase()}…`} />;
  if (poll.error && poll.data === null) return <ErrorState message={poll.error} onRetry={poll.reload} />;
  if (poll.data === null) return <div className="state">No {label.toLowerCase()} data is available.</div>;

  return (
    <>
      {poll.error && (
        <div className="alert warn" role="status" style={{ marginBottom: 12 }}>
          Refresh failed: {poll.error}. Showing the last successful data.
          {' '}<button className="btn small" onClick={poll.reload}>Retry</button>
        </div>
      )}
      {children}
      <div className="muted small" style={{ marginTop: 10 }}>
        Last successful refresh: {fmtDateTime(poll.lastSuccessAt)}{poll.loading ? ' · Refreshing…' : ''}
      </div>
    </>
  );
}

function rangeLabel(seconds) { return seconds < 60 ? `${seconds}s` : `${seconds / 60} minute${seconds === 60 ? '' : 's'}`; }
function healthReason(o) {
  if (o.health === 'HEALTHY') return `${fmtNum(o.activeNodes)}/${fmtNum(o.totalNodes)} nodes active`;
  if (o.activeNodes === 0) return 'No active nodes are available';
  const issues = [];
  if (o.failedNodes) issues.push(`${o.failedNodes} failed`);
  if (o.suspectedNodes) issues.push(`${o.suspectedNodes} suspected`);
  if (o.recoveringNodes) issues.push(`${o.recoveringNodes} recovering`);
  return issues.length ? `Cluster issues: ${issues.join(', ')}` : `${fmtNum(o.activeNodes)}/${fmtNum(o.totalNodes)} nodes active`;
}
