import { useState } from 'react';
import { RefreshCw } from 'lucide-react';
import PageHeader from '../components/common/PageHeader';
import DataTable from '../components/common/DataTable';
import LoadingState from '../components/common/LoadingState';
import ErrorState from '../components/common/ErrorState';
import StatusBadge from '../components/common/StatusBadge';
import usePolling from '../hooks/usePolling';
import { clusterService } from '../services/clusterService';
import { useAuth } from '../context/AuthContext';
import { fmtDateTime } from '../utils/format';

const emptyFilters = { action: '', status: '', from: '', to: '' };

export default function AuditLogs() {
  const { hasRole } = useAuth();
  const [q, setQ] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(25);
  const [filters, setFilters] = useState(emptyFilters);
  const [applied, setApplied] = useState(emptyFilters);
  const logs = usePolling(() => (hasRole('ADMIN')
    ? clusterService.auditLogs(query, page, pageSize, {
      action: applied.action,
      status: applied.status,
      from: applied.from ? new Date(`${applied.from}T00:00:00`).getTime() : '',
      to: applied.to ? new Date(`${applied.to}T00:00:00`).getTime() + 86_400_000 : '',
    })
    : Promise.resolve(null)), hasRole('ADMIN') ? 5000 : 0, [query, page, pageSize, applied]);

  if (!hasRole('ADMIN')) return (<><PageHeader title="Audit Logs" /><div className="alert warn">Only administrators can view the audit log.</div></>);
  if (logs.loading && !logs.data) return <LoadingState />;
  if (logs.error && !logs.data) return <ErrorState message={logs.error} onRetry={logs.reload} />;
  const d = logs.data;
  const apply = (event) => { event.preventDefault(); setPage(0); setQuery(q); setApplied(filters); };
  const reset = () => { setQ(''); setQuery(''); setFilters(emptyFilters); setApplied(emptyFilters); setPage(0); };
  const updateFilter = (name, value) => setFilters((current) => ({ ...current, [name]: value }));

  return (
    <>
      <PageHeader title="Audit Logs" subtitle="Persistent record of administrative actions, cache changes, and cluster failure and recovery events. Read-only cache requests are not audited."
        actions={<button type="button" className="btn" onClick={logs.reload} disabled={logs.loading}><RefreshCw size={14} />Refresh</button>} />
      <div className="card">
        <form className="stack" style={{ marginBottom: 12 }} onSubmit={apply}>
          <div className="row">
            <input style={{ flex: 1, minWidth: 200 }} placeholder="Search actor, action or resource" value={q} onChange={(e) => setQ(e.target.value)} />
            <input aria-label="Filter action" placeholder="Action contains…" value={filters.action} onChange={(e) => updateFilter('action', e.target.value)} />
            <select aria-label="Filter result status" value={filters.status} onChange={(e) => updateFilter('status', e.target.value)}>
              <option value="">All results</option><option value="SUCCESS">Successful</option><option value="FAILED">Failed</option><option value="INFO">Info</option><option value="WARNING">Warning</option><option value="CRITICAL">Critical</option>
            </select>
          </div>
          <div className="row">
            <label className="small muted">From <input aria-label="From date" type="date" value={filters.from} onChange={(e) => updateFilter('from', e.target.value)} /></label>
            <label className="small muted">Through <input aria-label="Through date" type="date" value={filters.to} onChange={(e) => updateFilter('to', e.target.value)} /></label>
            <label className="small muted">Rows <select aria-label="Rows per page" value={pageSize} onChange={(e) => { setPageSize(Number(e.target.value)); setPage(0); }}><option value={25}>25</option><option value={50}>50</option><option value={100}>100</option></select></label>
            <button className="btn primary">Apply filters</button>
            <button type="button" className="btn" onClick={reset}>Reset</button>
          </div>
        </form>
        {logs.error && <div className="alert warn" role="status">Audit refresh failed: {logs.error}. Showing the last successful results from {logs.lastSuccessAt ? fmtDateTime(logs.lastSuccessAt) : 'an unavailable time'}.</div>}
        {logs.lastSuccessAt && <div className="muted small" style={{ marginBottom: 10 }}>Last successful refresh: {fmtDateTime(logs.lastSuccessAt)}</div>}
        <DataTable rows={d.items} rowKey={(r) => r.id} empty="No audit entries match the selected filters."
          columns={[
            { key: 'u', header: 'Actor', render: (r) => <b>{r.username}</b> },
            { key: 'a', header: 'Action', render: (r) => <span className="mono">{r.action}</span> },
            { key: 't', header: 'Resource', render: (r) => <span className="muted small">{r.target || '—'}</span> },
            { key: 'description', header: 'Description', render: (r) => <span>{r.description || r.action}</span> },
            { key: 'details', header: 'Details', render: (r) => r.details ? <details><summary>View</summary><code>{r.details}</code></details> : '—' },
            { key: 'ts', header: 'Timestamp', render: (r) => fmtDateTime(r.createdAt) },
            { key: 'r', header: 'Result', render: (r) => <StatusBadge status={/^SUCCESS/i.test(r.result) || /^INFO/i.test(r.result) ? 'ACTIVE' : /^WARNING/i.test(r.result) ? 'WARNING' : 'FAILED'} label={r.result} /> },
            { key: 'ip', header: 'IP address', render: (r) => <span className="mono">{r.ipAddress || '—'}</span> },
          ]} />
        <div className="spread" style={{ marginTop: 12 }}>
          <span className="muted small">{d.totalItems} entries · page {d.totalPages === 0 ? 0 : d.page + 1} of {Math.max(1, d.totalPages)}</span>
          <div className="row"><button className="btn small" disabled={page === 0 || logs.loading} onClick={() => setPage(page - 1)}>Previous</button><button className="btn small" disabled={page + 1 >= d.totalPages || logs.loading} onClick={() => setPage(page + 1)}>Next</button></div>
        </div>
      </div>
    </>
  );
}
