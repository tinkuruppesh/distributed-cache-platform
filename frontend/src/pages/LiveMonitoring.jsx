import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Download, Pause, Play, RefreshCw } from 'lucide-react';
import PageHeader from '../components/common/PageHeader';
import MetricCard from '../components/common/MetricCard';
import LoadingState from '../components/common/LoadingState';
import ErrorState from '../components/common/ErrorState';
import TrafficControl from '../components/common/TrafficControl';
import StatusBadge from '../components/common/StatusBadge';
import RequestTrafficChart from '../components/charts/RequestTrafficChart';
import CacheHitMissChart from '../components/charts/CacheHitMissChart';
import LatencyChart from '../components/charts/LatencyChart';
import MemoryUsageChart from '../components/charts/MemoryUsageChart';
import NodeHealthTimeline from '../components/charts/NodeHealthTimeline';
import FailoverHistoryChart from '../components/charts/FailoverHistoryChart';
import usePolling from '../hooks/usePolling';
import { monitoringService } from '../services/monitoringService';
import { clusterService } from '../services/clusterService';
import { advisorService } from '../services/advisorService';
import { useCluster } from '../context/ClusterContext';
import { fmtBytes, fmtDateTime, fmtMs, fmtNum, fmtPct, fmtTime } from '../utils/format';

const RANGES = [{ label: '1 minute', seconds: 60 }, { label: '5 minutes', seconds: 300 }, { label: '15 minutes', seconds: 900 }, { label: '30 minutes', seconds: 1800 }, { label: '1 hour', seconds: 3600 }];
const nil = (value, formatter) => value === null || value === undefined ? 'Unavailable' : formatter(value);

function PollSection({ poll, children, empty, emptyMessage = 'No data recorded yet.' }) {
  if (poll.loading && poll.data === null) return <LoadingState />;
  if (poll.error && poll.data === null) return <ErrorState message={poll.error} onRetry={poll.reload} />;
  return <>
    {poll.error && <div className="alert warn" role="status">Refresh failed. Showing data from {poll.lastSuccessAt ? fmtDateTime(poll.lastSuccessAt) : 'the last successful request'}: {poll.error}</div>}
    {poll.loading && <div className="small muted" role="status">Refreshing…</div>}
    {empty ? <div className="state">{emptyMessage}</div> : children}
  </>;
}

function csvCell(value) { return `"${String(value ?? '').replaceAll('"', '""')}"`; }

export default function LiveMonitoring() {
  const { refreshMs } = useCluster();
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [range, setRange] = useState(60);
  const [nodeFilter, setNodeFilter] = useState('');
  const [severityFilter, setSeverityFilter] = useState('');
  const [eventNodeFilter, setEventNodeFilter] = useState('');
  const [eventTypeFilter, setEventTypeFilter] = useState('');
  const every = autoRefresh ? refreshMs : 0;
  const overview = usePolling(() => monitoringService.overview(), every);
  const nodes = usePolling(() => clusterService.nodes(), every);
  const traffic = usePolling(() => monitoringService.traffic(range), every, [range]);
  const performance = usePolling(() => monitoringService.cachePerformance(range), every, [range]);
  const memory = usePolling(() => monitoringService.memory(300), every);
  const events = usePolling(() => monitoringService.events(100), every);
  const failovers = usePolling(() => clusterService.failovers(), autoRefresh ? Math.max(refreshMs, 4000) : 0);
  const recommendations = usePolling(() => advisorService.list('OPEN'), autoRefresh ? Math.max(refreshMs, 5000) : 0);
  const o = overview.data;
  const nodeRows = nodes.data || [];
  const points = traffic.data || [];
  const counters = performance.data?.counters;
  const latency = performance.data?.latency;
  const rep = performance.data?.replication;
  const requiredReplicationFields = ['sent', 'acked', 'applied', 'alreadyCurrent', 'failed', 'avgAckLatencyMs', 'p95AckLatencyMs', 'ackLatencySampleCount'];
  const missingReplicationFields = [
    ...requiredReplicationFields.filter((field) => rep?.[field] === null || rep?.[field] === undefined).map((field) => `replication.${field}`),
    performance.data && !performance.data.replicationMode ? 'replicationMode' : null,
  ].filter(Boolean);
  const reads = counters ? counters.hits + counters.misses : null;
  const ratio = reads > 0 ? counters.hits * 100 / reads : null;
  const evictions = nodeRows.length ? nodeRows.reduce((sum, node) => sum + (node.evictions || 0), 0) : null;
  const averageRps = points.length ? points.reduce((sum, point) => sum + point.requests, 0) / points.length : null;
  const peakRps = points.length ? Math.max(...points.map((point) => point.requests)) : null;
  const failedRate = counters?.total > 0 ? 100 * counters.failed / counters.total : null;
  const filteredPoints = useMemo(() => points.map((point) => nodeFilter
    ? { ...point, requests: point.requestsByNode?.[nodeFilter] || 0 }
    : point), [points, nodeFilter]);
  const visibleEvents = (events.data || []).filter((event) => (!severityFilter || event.severity === severityFilter)
    && (!eventNodeFilter || event.nodeId === eventNodeFilter) && (!eventTypeFilter || event.type === eventTypeFilter));
  const lastUpdated = [overview, nodes, traffic, performance, memory, events, failovers, recommendations]
    .map((poll) => poll.lastSuccessAt).filter(Boolean).sort((a, b) => b - a)[0];
  const hasChartRequests = filteredPoints.some((point) => point.requests > 0);
  const refreshAll = () => Promise.all([overview.reload(), nodes.reload(), traffic.reload(), performance.reload(), memory.reload(), events.reload(), failovers.reload(), recommendations.reload()]);

  const exportCsv = () => {
    const rows = [['section', 'timestamp', 'name', 'value', 'node']];
    for (const point of points) {
      rows.push(['traffic', point.time, 'total_requests_per_second', point.requests, '']);
      rows.push(['traffic', point.time, 'GET_per_second', point.getRequests, '']);
      rows.push(['traffic', point.time, 'SET_per_second', point.setRequests, '']);
      rows.push(['traffic', point.time, 'UPDATE_per_second', point.updateRequests, '']);
      rows.push(['traffic', point.time, 'DELETE_per_second', point.deleteRequests, '']);
      rows.push(['traffic', point.time, 'hits', point.hits, '']);
      rows.push(['traffic', point.time, 'misses', point.misses, '']);
      for (const [node, requests] of Object.entries(point.requestsByNode || {})) rows.push(['node_traffic', point.time, 'requests_per_second', requests, node]);
    }
    for (const node of nodeRows) {
      rows.push(['node', Date.now(), 'state', node.state, node.id]);
      rows.push(['node', Date.now(), 'entries', node.entries, node.id]);
      rows.push(['node', Date.now(), 'memory_bytes', node.usedBytes, node.id]);
      rows.push(['node', Date.now(), 'gateway_requests_total', node.gatewayRequests, node.id]);
      rows.push(['node', Date.now(), 'node_requests_total', node.nodeRequests, node.id]);
      rows.push(['node', Date.now(), 'replication_writes_received_total', node.replicationWrites, node.id]);
      rows.push(['node', Date.now(), 'cache_hits_total', node.hits, node.id]);
      rows.push(['node', Date.now(), 'cache_misses_total', node.misses, node.id]);
      rows.push(['node', Date.now(), 'evictions_total', node.evictions, node.id]);
    }
    for (const event of events.data || []) rows.push(['event', event.time, `${event.severity}:${event.type}`, event.message, event.nodeId]);
    const blob = new Blob([rows.map((row) => row.map(csvCell).join(',')).join('\r\n')], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = `live-monitoring-${Date.now()}.csv`; anchor.click(); URL.revokeObjectURL(url);
  };

  return <>
    <PageHeader title="Live Monitoring" subtitle="Real cache-engine, node, replication and failover measurements."
      actions={<>
        <span className="small muted">Last successful refresh: {lastUpdated ? fmtDateTime(lastUpdated) : 'Not yet'}</span>
        <button className="btn" onClick={() => setAutoRefresh((enabled) => !enabled)}>{autoRefresh ? <><Pause size={14} />Pause refresh</> : <><Play size={14} />Resume refresh</>}</button>
        <button className="btn" onClick={refreshAll}><RefreshCw size={14} />Refresh now</button>
        <button className="btn" onClick={exportCsv}><Download size={14} />Export CSV</button>
      </>} />
    <div className="stack">
      <div className="card spread">
        <div><b>Measurement window</b><div className="small muted">Per-second gateway buckets. History is process-local and limited to the current management API lifetime.</div></div>
        <div className="row">
          <label className="field">Time range<select value={range} onChange={(e) => setRange(Number(e.target.value))}>{RANGES.map((item) => <option key={item.seconds} value={item.seconds}>{item.label}</option>)}</select></label>
          <label className="field">Node traffic<select value={nodeFilter} onChange={(e) => setNodeFilter(e.target.value)}><option value="">All nodes</option>{nodeRows.map((node) => <option key={node.id} value={node.id}>{node.id}</option>)}</select></label>
        </div>
      </div>

      <div className="card"><div className="card-title"><h2>Cluster overview</h2></div>
      <PollSection poll={overview} empty={!o} emptyMessage="Cluster overview is unavailable.">
        <div className="grid metrics">
          <MetricCard label="Cluster health" value={o?.health || 'Unavailable'} tone={o?.health === 'HEALTHY' ? 'green' : o?.health === 'DEGRADED' ? 'orange' : 'red'} hint={`${fmtNum(o?.activeNodes)} active / ${fmtNum(o?.totalNodes)} nodes`} />
          <MetricCard label="Current requests / second" value={nil(o?.requestsPerSecond, (v) => `${Number(v).toFixed(2)} req/s`)} tone="blue" hint="Backend measured 5-second window" />
          <MetricCard label="Total gateway requests" value={nil(o?.totalRequests, fmtNum)} />
          <MetricCard label="Successful requests" value={nil(o?.successfulRequests, fmtNum)} tone="green" />
          <MetricCard label="Failed requests" value={nil(o?.failedRequests, fmtNum)} tone={o?.failedRequests > 0 ? 'red' : 'green'} />
          <MetricCard label="Fallback requests" value={nil(o?.fallbackRequests, fmtNum)} />
          <MetricCard label="Failovers" value={nil(o?.failoverCount, fmtNum)} />
          <MetricCard label="Memory utilization" value={nil(o?.memoryUtilizationPct, fmtPct)} />
          <MetricCard label="Cache entries" value={nil(o?.totalEntries, fmtNum)} />
          <MetricCard label="Active nodes" value={nil(o?.activeNodes, fmtNum)} tone="green" />
          <MetricCard label="Failed nodes" value={nil(o?.failedNodes, fmtNum)} tone={o?.failedNodes > 0 ? 'red' : 'green'} />
          <MetricCard label="Recovering nodes" value={nil(o?.recoveringNodes, fmtNum)} tone={o?.recoveringNodes > 0 ? 'orange' : undefined} />
          <MetricCard label="Suspected nodes" value={nil(o?.suspectedNodes, fmtNum)} tone={o?.suspectedNodes > 0 ? 'orange' : undefined} />
        </div>
        {o?.health !== 'HEALTHY' && <div className="alert warn">Cluster health is {o?.health || 'unavailable'}: {fmtNum(o?.activeNodes)} active, {fmtNum(o?.suspectedNodes)} suspected, {fmtNum(o?.failedNodes)} failed, {fmtNum(o?.recoveringNodes)} recovering, {fmtNum(o?.maintenanceNodes)} in maintenance.</div>}
      </PollSection>
      </div>

      <div className="card"><div className="card-title"><h2>Cache and replication performance</h2><span className="small muted">Counters and latency samples since API start</span></div>
        <PollSection poll={performance} empty={!performance.data} emptyMessage="Cache performance measurements are unavailable.">
          {missingReplicationFields.length > 0 && <div className="alert error" role="alert">The cache-performance API response is missing replication field(s): {missingReplicationFields.join(', ')}. Restart the management API with the current backend before relying on these replication metrics.</div>}
          <div className="grid metrics">
            <MetricCard label="Cache hit ratio" value={reads === null ? 'Unavailable' : reads === 0 ? 'No reads' : fmtPct(ratio)} tone="green" hint={reads === null ? 'Measurement unavailable' : `${fmtNum(counters.hits)} hits / ${fmtNum(counters.misses)} misses`} />
            <MetricCard label="Cache hits" value={nil(counters?.hits, fmtNum)} tone="green" />
            <MetricCard label="Cache misses" value={nil(counters?.misses, fmtNum)} tone="orange" />
            <MetricCard label="Cache reads" value={reads === null ? 'Unavailable' : fmtNum(reads)} hint="Gateway hit + miss outcomes" />
            <MetricCard label="Failed request rate" value={counters?.total > 0 ? fmtPct(failedRate) : 'Unavailable'} hint="Management API lifetime" />
            <MetricCard label="GET operations" value={nil(counters?.gets, fmtNum)} />
            <MetricCard label="SET operations" value={nil(counters?.sets, fmtNum)} />
            <MetricCard label="UPDATE operations" value={nil(counters?.updates, fmtNum)} />
            <MetricCard label="DELETE operations" value={nil(counters?.deletes, fmtNum)} />
            <MetricCard label="Average latency" value={latency?.samples ? fmtMs(latency.avgMs, 2) : 'Unavailable'} hint={`${fmtNum(latency?.samples)} measured samples`} />
            <MetricCard label="P50 latency" value={latency?.samples ? fmtMs(latency.p50Ms, 2) : 'Unavailable'} />
            <MetricCard label="P95 latency" value={latency?.samples ? fmtMs(latency.p95Ms, 2) : 'Unavailable'} />
            <MetricCard label="P99 latency" value={latency?.samples ? fmtMs(latency.p99Ms, 2) : 'Unavailable'} tone={latency?.p99Ms > 100 ? 'orange' : undefined} />
            <MetricCard label="Replication writes attempted" value={nil(rep?.sent, fmtNum)} hint={`${performance.data?.replicationMode || 'Mode unavailable'}; async writes complete after the primary response`} />
            <MetricCard label="Replica acknowledgements" value={nil(rep?.acked, fmtNum)} hint={`${fmtNum(rep?.ackLatencySampleCount)} APPLIED / already-current response samples`} />
            <MetricCard label="Already-current responses" value={nil(rep?.alreadyCurrent, fmtNum)} hint="Replica already held an equal or newer version" />
            <MetricCard label="Replication failures" value={nil(rep?.failed, fmtNum)} tone={rep?.failed > 0 ? 'red' : 'green'} />
            <MetricCard label="Average acknowledgement latency" value={rep?.ackLatencySampleCount > 0 ? fmtMs(rep.avgAckLatencyMs, 2) : 'Unavailable'} hint={rep?.ackLatencySampleCount === 0 ? 'No replica responses recorded' : 'APPLIED and already-current responses only'} />
            <MetricCard label="P95 acknowledgement latency" value={rep?.ackLatencySampleCount > 0 ? fmtMs(rep.p95AckLatencyMs, 2) : 'Unavailable'} hint={rep?.ackLatencySampleCount === 0 ? 'No replica responses recorded' : 'APPLIED and already-current responses only'} />
          </div>
        </PollSection>
      </div>

      <div className="grid cols-2">
        <div className="card"><div className="card-title"><h2>Request traffic</h2><span className="small muted">{nodeFilter || 'Cluster'} · requests per second</span></div>
          <PollSection poll={traffic} empty={traffic.data !== null && !hasChartRequests} emptyMessage={`No ${nodeFilter || 'gateway'} requests were recorded in the selected ${RANGES.find((item) => item.seconds === range)?.label} window.`}>
            <div className="grid cols-2" style={{ marginBottom: 10 }}><MetricCard label="Average requests / second" value={nil(averageRps, (v) => `${Number(v).toFixed(2)} req/s`)} hint="Selected per-second window" /><MetricCard label="Peak requests / second" value={nil(peakRps, (v) => `${fmtNum(v)} req/s`)} hint="Highest measured one-second bucket" /></div>
            <RequestTrafficChart data={filteredPoints} nodeOnly={!!nodeFilter} />
          </PollSection>
        </div>
        <div className="card"><div className="card-title"><h2>Cache performance</h2><span className="small muted">Real cache read outcomes per second</span></div>
          <PollSection poll={performance} empty={performance.data !== null && !(performance.data.timeline || []).some((p) => p.hits + p.misses > 0)} emptyMessage="No cache hits or misses were recorded in this time range."><CacheHitMissChart data={performance.data?.timeline || []} /></PollSection>
        </div>
        <div className="card"><div className="card-title"><h2>Response latency</h2><span className="small muted">Per-second average from gateway calls</span></div>
          <PollSection poll={traffic} empty={traffic.data !== null && !hasChartRequests} emptyMessage={`No ${nodeFilter || 'gateway'} latency samples were recorded in this time range.`}><LatencyChart data={filteredPoints} /></PollSection>
        </div>
        <div className="card"><div className="card-title"><h2>Memory usage per node</h2></div>
          <PollSection poll={memory} empty={memory.data !== null && !memory.data?.history?.length} emptyMessage="Node memory history is not available yet; the sampler records every two seconds."><MemoryUsageChart history={memory.data?.history} nodeIds={(memory.data?.nodes || []).map((node) => node.id)} /></PollSection>
        </div>
        <div className="card"><div className="card-title"><h2>Node health over time</h2></div>
          <PollSection poll={memory} empty={memory.data !== null && !memory.data?.history?.length} emptyMessage="Node state history has not been sampled yet."><NodeHealthTimeline history={memory.data?.history} nodeIds={(memory.data?.nodes || []).map((node) => node.id)} /></PollSection>
        </div>
        <div className="card"><div className="card-title"><h2>Failover history</h2></div>
          <PollSection poll={failovers} empty={failovers.data !== null && !failovers.data?.length} emptyMessage="No failovers have been recorded in this management API process."><FailoverHistoryChart failovers={failovers.data || []} /></PollSection>
        </div>
      </div>

      <div className="card">
        <div className="card-title"><h2>Node metrics</h2><Link className="btn small" to="/nodes">Open Cluster Nodes</Link></div>
        <PollSection poll={nodes} empty={nodes.data !== null && !nodeRows.length} emptyMessage="The backend did not return any cluster nodes.">
          <div className="table-wrap"><table className="data"><thead><tr><th>Node</th><th>Host</th><th>Status</th><th>Role</th><th>Entries</th><th>Memory</th><th>Gateway requests (total)</th><th>Node requests (total)</th><th>Hits / misses</th><th>Replica writes received</th><th>Evictions</th><th>Latency</th></tr></thead><tbody>
            {nodeRows.filter((node) => !nodeFilter || node.id === nodeFilter).map((node) => <tr key={node.id}><td><b>{node.id}</b></td><td>{node.host}:{node.port}</td><td><StatusBadge status={node.state} /></td><td>{node.role || 'Unavailable'}</td><td>{fmtNum(node.entries)}</td><td>{fmtBytes(node.usedBytes)} / {fmtBytes(node.maxBytes)} ({fmtPct(node.memoryPct)})</td><td>{fmtNum(node.gatewayRequests)}</td><td>{fmtNum(node.nodeRequests)}</td><td>{fmtNum(node.hits)} / {fmtNum(node.misses)}</td><td>{fmtNum(node.replicationWrites)}</td><td>{fmtNum(node.evictions)}</td><td>Unavailable per node</td></tr>)}
          </tbody></table></div>
          <p className="small muted" style={{ marginTop: 10 }}>Per-node latency is not currently recorded by the backend. Request totals are cumulative since the management API started.</p>
        </PollSection>
      </div>

      <div className="card">
        <div className="card-title"><h2>Recent cluster events</h2><div className="row">
          <label className="field">Severity<select value={severityFilter} onChange={(e) => setSeverityFilter(e.target.value)}><option value="">All severities</option><option>INFO</option><option>WARNING</option><option>CRITICAL</option><option>ERROR</option></select></label>
          <label className="field">Node<select value={eventNodeFilter} onChange={(e) => setEventNodeFilter(e.target.value)}><option value="">All nodes</option>{nodeRows.map((node) => <option key={node.id}>{node.id}</option>)}</select></label>
          <label className="field">Type<select value={eventTypeFilter} onChange={(e) => setEventTypeFilter(e.target.value)}><option value="">All event types</option>{[...new Set((events.data || []).map((event) => event.type))].sort().map((type) => <option key={type}>{type}</option>)}</select></label>
        </div></div>
        <PollSection poll={events} empty={events.data !== null && visibleEvents.length === 0} emptyMessage={events.data?.length ? 'No events match the selected filters.' : 'No cluster events have been recorded.'}>
          <ul className="timeline">{visibleEvents.map((event) => <li key={event.id}><span className="t">{fmtTime(event.time)}</span><span className={`bullet ${event.severity === 'CRITICAL' || event.severity === 'ERROR' ? 'red' : event.severity === 'WARNING' ? 'orange' : 'green'}`} /><span><StatusBadge status={event.severity} /> <b className="small muted">{event.type.replaceAll('_', ' ')}</b> {event.nodeId && <code>{event.nodeId}</code>} {event.message}</span></li>)}</ul>
        </PollSection>
      </div>

      <div className="card"><div className="card-title"><h2>Active advisor recommendations</h2><Link className="btn small" to="/advisor">Open Optimization Advisor</Link></div>
        <PollSection poll={recommendations} empty={recommendations.data !== null && !recommendations.data?.length} emptyMessage="There are no open recommendations from the backend advisor.">
          <div className="stack">{(recommendations.data || []).map((item) => <div className="card" key={item.id}>
            <div className="spread"><div className="row"><StatusBadge status={item.severity} /><b>{item.title}</b></div><span className="small muted">{fmtDateTime(item.createdAt)}</span></div>
            <p style={{ marginTop: 8 }}>{item.reason || item.description}</p><div className="small" style={{ marginTop: 8 }}><b>Suggested action:</b> {item.recommendedAction}</div>
          </div>)}</div>
        </PollSection>
      </div>

      <div className="card"><div className="card-title"><h2>Generate real cache workload</h2></div>
        <p className="small muted" style={{ marginBottom: 14 }}>This sends actual SET, GET, UPDATE and DELETE requests to the cache API. Generated keys have a unique prefix, are marked as generated workload, and can be cleaned up without touching other keys.</p>
        <TrafficControl generateLabel="Start workload" />
      </div>
    </div>
  </>;
}
