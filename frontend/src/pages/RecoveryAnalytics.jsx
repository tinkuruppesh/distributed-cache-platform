import { useRef, useState } from 'react';
import { RotateCcw } from 'lucide-react';
import PageHeader from '../components/common/PageHeader';
import MetricCard from '../components/common/MetricCard';
import DataTable from '../components/common/DataTable';
import StatusBadge from '../components/common/StatusBadge';
import LoadingState from '../components/common/LoadingState';
import ErrorState from '../components/common/ErrorState';
import RecoveryReport from '../components/simulation/RecoveryReport';
import SimulationTimeline from '../components/simulation/SimulationTimeline';
import RecoveryAnalyticsVisuals from '../components/simulation/RecoveryAnalyticsVisuals';
import usePolling from '../hooks/usePolling';
import { simulationService } from '../services/simulationService';
import { errorMessage } from '../services/api';
import { fmtDateTime, fmtDuration } from '../utils/format';

const INITIAL_DRAFT = { q: '', outcome: '', mode: '', nodeId: '', fromDate: '', toDate: '', sortBy: 'date', direction: 'desc', size: 25 };
const INITIAL_QUERY = { page: 0, size: 25, sortBy: 'date', direction: 'desc' };
const duration = (value) => value == null ? 'N/A' : fmtDuration(value);

function startOfDate(value) {
  return value ? new Date(`${value}T00:00:00`).getTime() : undefined;
}

function endOfDateExclusive(value) {
  if (!value) return undefined;
  const date = new Date(`${value}T00:00:00`);
  date.setDate(date.getDate() + 1);
  return date.getTime();
}

function queryFromDraft(draft) {
  const query = { page: 0, size: Number(draft.size), sortBy: draft.sortBy, direction: draft.direction };
  for (const key of ['q', 'outcome', 'mode', 'nodeId']) if (draft[key].trim()) query[key] = draft[key].trim();
  const from = startOfDate(draft.fromDate), to = endOfDateExclusive(draft.toDate);
  if (from != null) query.from = from;
  if (to != null) query.to = to;
  return query;
}

export default function RecoveryAnalytics() {
  const [draft, setDraft] = useState(INITIAL_DRAFT);
  const [filters, setFilters] = useState(INITIAL_QUERY);
  const [filterError, setFilterError] = useState(null);
  const [selected, setSelected] = useState(null);
  const [detail, setDetail] = useState(null);
  const [detailError, setDetailError] = useState(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const detailRequest = useRef(0);
  const list = usePolling(() => simulationService.analytics(filters), 5000, [filters]);

  const loadDetail = async (id) => {
    const requestId = ++detailRequest.current;
    setSelected(id);
    setDetail(null);
    setDetailError(null);
    setDetailLoading(true);
    try {
      const result = await simulationService.get(id);
      if (detailRequest.current === requestId) setDetail(result);
    } catch (e) {
      if (detailRequest.current === requestId) setDetailError(errorMessage(e));
    } finally {
      if (detailRequest.current === requestId) setDetailLoading(false);
    }
  };

  const applyFilters = (event) => {
    event.preventDefault();
    setFilterError(null);
    if (draft.fromDate && draft.toDate && draft.fromDate > draft.toDate) {
      setFilterError('The start date must be on or before the end date.');
      return;
    }
    setFilters(queryFromDraft(draft));
  };

  const resetFilters = () => {
    setDraft(INITIAL_DRAFT);
    setFilters(INITIAL_QUERY);
    setFilterError(null);
  };

  if (list.loading && !list.data) return <LoadingState />;
  if (list.error && !list.data) return <ErrorState message={list.error} onRetry={list.reload} />;

  const data = list.data;
  const rows = data.items || [];
  const summary = data.summary;
  const selectedRow = rows.find((row) => row.id === selected);
  const firstVisible = data.totalElements === 0 ? 0 : data.page * data.size + 1;
  const lastVisible = Math.min((data.page + 1) * data.size, data.totalElements);

  return (
    <>
      <PageHeader title="Recovery Analytics" subtitle="Measured history, recovery performance and request outcomes from stored failure simulations."
        actions={<button className="btn" onClick={list.reload}><RotateCcw size={14} />Refresh</button>} />
      <div className="stack">
        {list.error
          ? <div className="alert warn">Latest refresh failed: {list.error}. Showing the last successful data from {fmtDateTime(list.lastSuccessAt)}.</div>
          : <div className="muted small">Last successful refresh: {fmtDateTime(list.lastSuccessAt)}</div>}
        <div className="grid metrics">
          <MetricCard label="Simulations" value={summary.totalSimulations}
            hint={`${summary.completedSimulations} completed · ${summary.failedSimulations} failed`} />
          <MetricCard label="Avg detection time" value={duration(summary.avgDetectionMs)} hint={`${summary.detectionSamples} valid completed measurements`} tone="orange" />
          <MetricCard label="Avg failover time" value={duration(summary.avgFailoverMs)} hint={`${summary.failoverSamples} valid completed measurements`} tone="blue" />
          <MetricCard label="Avg recovery time" value={duration(summary.avgRecoveryMs)} hint={`${summary.recoverySamples} valid completed measurements`} tone="green" />
          <MetricCard label="Overall request success" value={summary.requestSuccessPct == null ? 'N/A' : `${summary.requestSuccessPct.toFixed(2)}%`}
            hint={`${(summary.requestsSucceeded ?? 0).toLocaleString()} successful / ${(summary.requestsTotal ?? 0).toLocaleString()} measured requests`} />
        </div>

        <div className="card">
          <form className="stack" onSubmit={applyFilters}>
            <div className="row">
              <input aria-label="Search simulations" style={{ flex: '1 1 230px', minWidth: 200 }} placeholder="Search ID, node or outcome" value={draft.q}
                onChange={(event) => setDraft((current) => ({ ...current, q: event.target.value }))} />
              <label className="small muted">Outcome&nbsp;
                <select aria-label="Filter by outcome" value={draft.outcome} onChange={(event) => setDraft((current) => ({ ...current, outcome: event.target.value }))}>
                  <option value="">All outcomes</option>{data.filters.outcomes.map((value) => <option key={value} value={value}>{value.replaceAll('_', ' ')}</option>)}
                </select>
              </label>
              <label className="small muted">Failure mode&nbsp;
                <select aria-label="Filter by failure mode" value={draft.mode} onChange={(event) => setDraft((current) => ({ ...current, mode: event.target.value }))}>
                  <option value="">All modes</option>{data.filters.modes.map((value) => <option key={value} value={value}>{value}</option>)}
                </select>
              </label>
              <label className="small muted">Affected node&nbsp;
                <select aria-label="Filter by affected node" value={draft.nodeId} onChange={(event) => setDraft((current) => ({ ...current, nodeId: event.target.value }))}>
                  <option value="">All nodes</option>{data.filters.nodes.map((value) => <option key={value} value={value}>{value}</option>)}
                </select>
              </label>
            </div>
            <div className="row">
              <label className="small muted">From&nbsp;<input aria-label="Start date" type="date" value={draft.fromDate} onChange={(event) => setDraft((current) => ({ ...current, fromDate: event.target.value }))} /></label>
              <label className="small muted">Through&nbsp;<input aria-label="End date" type="date" value={draft.toDate} onChange={(event) => setDraft((current) => ({ ...current, toDate: event.target.value }))} /></label>
              <label className="small muted">Sort by&nbsp;
                <select aria-label="Sort simulations by" value={draft.sortBy} onChange={(event) => setDraft((current) => ({ ...current, sortBy: event.target.value }))}>
                  <option value="date">Date</option><option value="detection">Detection time</option><option value="failover">Failover time</option><option value="recovery">Recovery time</option>
                </select>
              </label>
              <label className="small muted">Order&nbsp;
                <select aria-label="Sort direction" value={draft.direction} onChange={(event) => setDraft((current) => ({ ...current, direction: event.target.value }))}>
                  <option value="desc">Descending</option><option value="asc">Ascending</option>
                </select>
              </label>
              <label className="small muted">Rows&nbsp;
                <select aria-label="Rows per page" value={draft.size} onChange={(event) => setDraft((current) => ({ ...current, size: Number(event.target.value) }))}>
                  <option value={10}>10</option><option value={25}>25</option><option value={50}>50</option><option value={100}>100</option>
                </select>
              </label>
              <button className="btn primary" type="submit">Apply filters</button>
              <button className="btn" type="button" onClick={resetFilters}>Reset</button>
            </div>
            {filterError && <div className="alert error">{filterError}</div>}
          </form>
        </div>

        <RecoveryAnalyticsVisuals trends={data.trends} byNode={data.byNode} byMode={data.byMode}
          slowestObservedRecoveryNode={data.slowestObservedRecoveryNode} />

        <div className="card">
          <div className="card-title"><h2>Simulation history</h2><span className="muted small">{data.totalElements.toLocaleString()} matching records</span></div>
          <DataTable rows={rows} rowKey={(row) => row.id} onRowClick={(row) => loadDetail(row.id)} selectedKey={selected}
            empty="No simulations match these filters. Adjust the search or filters, or run a simulation from Failure Simulation."
            columns={[
              { key: 'id', header: 'ID', render: (row) => <span className="mono">{row.id}</span> },
              { key: 't', header: 'Started', render: (row) => fmtDateTime(row.startedAt) },
              { key: 'n', header: 'Node', render: (row) => <b>{row.nodeId}</b> },
              { key: 'm', header: 'Mode', render: (row) => row.mode === 'CRASH' ? 'Crash' : row.mode === 'ISOLATE' ? 'Partition' : row.mode },
              { key: 'o', header: 'Outcome', render: (row) => <StatusBadge status={row.outcome === 'COMPLETED' ? 'COMPLETED' : row.outcome ? 'FAILED' : row.phase} label={(row.outcome || row.phase).replaceAll('_', ' ')} /> },
              { key: 'd', header: 'Detection', render: (row) => duration(row.detectionMs) },
              { key: 'f', header: 'Failover', render: (row) => duration(row.failoverMs) },
              { key: 'r', header: 'Recovery', render: (row) => duration(row.recoveryMs) },
              { key: 'e', header: 'Error rate', render: (row) => (row.errorRatePct != null ? `${row.errorRatePct}%` : 'N/A') },
            ]} />
          <div className="spread" style={{ marginTop: 12 }}>
            <span className="muted small">Showing {firstVisible}-{lastVisible} of {data.totalElements}</span>
            <div className="row">
              <button className="btn" type="button" disabled={data.page <= 0} onClick={() => setFilters((current) => ({ ...current, page: Math.max(0, current.page - 1) }))}>Previous</button>
              <span className="small muted">Page {data.totalPages === 0 ? 0 : data.page + 1} of {data.totalPages}</span>
              <button className="btn" type="button" disabled={data.page + 1 >= data.totalPages} onClick={() => setFilters((current) => ({ ...current, page: current.page + 1 }))}>Next</button>
            </div>
          </div>
        </div>

        {detailLoading && <div className="card"><LoadingState label={`Loading simulation ${selected}`} /></div>}
        {detailError && <div className="card"><ErrorState message={`Could not load simulation ${selected}: ${detailError}`} onRetry={() => selected && loadDetail(selected)} /></div>}
        {detail && detail.summary?.id === selected && (
          <div className="stack">
            <div className="card"><RecoveryReport report={detail.report} /></div>
            <div className="card"><div className="card-title"><h2>Event timeline</h2></div><SimulationTimeline events={detail.events} /></div>
          </div>
        )}
      </div>
    </>
  );
}
