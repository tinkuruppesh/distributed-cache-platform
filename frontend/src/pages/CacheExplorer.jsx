import { useEffect, useMemo, useState } from 'react';
import { Download, Eye, Plus, RefreshCw, Search, Trash2 } from 'lucide-react';
import PageHeader from '../components/common/PageHeader';
import MetricCard from '../components/common/MetricCard';
import DataTable from '../components/common/DataTable';
import LoadingState from '../components/common/LoadingState';
import ErrorState from '../components/common/ErrorState';
import Modal from '../components/common/Modal';
import ConfirmDialog from '../components/common/ConfirmDialog';
import RequestTrafficChart from '../components/charts/RequestTrafficChart';
import CacheHitMissChart from '../components/charts/CacheHitMissChart';
import usePolling from '../hooks/usePolling';
import { cacheService } from '../services/cacheService';
import { clusterService } from '../services/clusterService';
import { monitoringService } from '../services/monitoringService';
import { errorMessage } from '../services/api';
import { useAuth } from '../context/AuthContext';
import { useCluster } from '../context/ClusterContext';
import { fmtBytes, fmtDateTime, fmtDuration, fmtMs, fmtNum, fmtPct } from '../utils/format';

const PAGE_DEFAULT = 50;
const EMPTY_FILTERS = { prefix: '', primaryNode: '', replicaNode: '', expiration: 'ALL', expiringWithinSeconds: 300, sortBy: 'KEY', descending: false };

export default function CacheExplorer() {
  const { hasRole } = useAuth();
  const canWrite = hasRole('ADMIN', 'OPERATOR');
  const isAdmin = hasRole('ADMIN');
  const { refreshMs } = useCluster();
  const overview = usePolling(() => monitoringService.overview(), refreshMs);
  const performance = usePolling(() => monitoringService.cachePerformance(60), refreshMs);
  const traffic = usePolling(() => monitoringService.traffic(60), refreshMs);
  const nodes = usePolling(() => clusterService.nodes(), refreshMs);
  const operations = usePolling(() => monitoringService.cacheOperations(25), refreshMs);

  const [filters, setFilters] = useState(EMPTY_FILTERS);
  const [applied, setApplied] = useState(EMPTY_FILTERS);
  const [reveal, setReveal] = useState(false);
  const [pageSize, setPageSize] = useState(PAGE_DEFAULT);
  const [offset, setOffset] = useState(0);
  const [rows, setRows] = useState(null);
  const [hasMore, setHasMore] = useState(false);
  const [listLoading, setListLoading] = useState(false);
  const [listError, setListError] = useState(null);
  const [listSuccessAt, setListSuccessAt] = useState(null);
  const [selectedKeys, setSelectedKeys] = useState(() => new Set());
  const [detailRow, setDetailRow] = useState(null);
  const [placement, setPlacement] = useState(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [readResult, setReadResult] = useState(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);
  const [entryModal, setEntryModal] = useState(false);
  const [editKey, setEditKey] = useState(null);
  const [form, setForm] = useState({ key: '', value: '', ttl: '' });
  const [ttlValue, setTtlValue] = useState('');
  const [confirm, setConfirm] = useState(null);

  const fetchRows = async (nextOffset = 0, query = applied, revealValue = reveal, size = pageSize) => {
    setListLoading(true); setListError(null);
    try {
      const result = await cacheService.keys(query.prefix, size + 1, revealValue, {
        offset: nextOffset, primaryNode: query.primaryNode || undefined, replicaNode: query.replicaNode || undefined,
        dataType: 'STRING', expiration: query.expiration, expiringWithinSeconds: Number(query.expiringWithinSeconds),
        sortBy: query.sortBy, descending: query.descending,
      });
      setRows(result.slice(0, size)); setHasMore(result.length > size); setOffset(nextOffset); setListSuccessAt(Date.now());
    } catch (e) { setListError(errorMessage(e)); }
    finally { setListLoading(false); }
  };

  useEffect(() => { fetchRows(0, EMPTY_FILTERS, false, PAGE_DEFAULT); /* Initial data load */ }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const refreshAll = () => {
    fetchRows(offset, applied); overview.reload(); performance.reload(); traffic.reload(); nodes.reload(); operations.reload();
  };
  const refreshAfterOperation = async () => {
    await Promise.all([fetchRows(0, applied), overview.reload(), performance.reload(), traffic.reload(), nodes.reload(), operations.reload()]);
  };
  const applyFilters = (e) => { e.preventDefault(); setApplied({ ...filters }); setSelectedKeys(new Set()); fetchRows(0, { ...filters }); };
  const clearFilters = () => { setFilters(EMPTY_FILTERS); setApplied(EMPTY_FILTERS); setSelectedKeys(new Set()); fetchRows(0, EMPTY_FILTERS); };

  const openDetails = async (row) => {
    setDetailRow(row); setPlacement(null); setReadResult(null); setTtlValue(row.ttlMillis < 0 ? '0' : String(Math.ceil(row.ttlMillis / 1000)));
    setDetailLoading(true);
    try { setPlacement(await cacheService.locate(row.key)); }
    catch (e) { setMessage({ ok: false, text: `Could not load ownership for ${row.key}: ${errorMessage(e)}` }); }
    finally { setDetailLoading(false); }
  };

  const getEntry = async (row = detailRow) => {
    if (!row) return;
    setBusy(true); setMessage(null); setReadResult(null);
    try {
      const result = await cacheService.read(row.key); setReadResult(result); setPlacement(result.placement);
      setMessage({ ok: true, text: `${row.key}: ${result.hit ? 'cache HIT' : 'cache MISS'} served by ${result.servedBy || 'no node'}.` });
      await Promise.all([performance.reload(), traffic.reload(), overview.reload(), operations.reload()]);
      if (!result.hit) await fetchRows(0, applied);
    } catch (e) { setMessage({ ok: false, text: errorMessage(e) }); }
    finally { setBusy(false); }
  };

  const editEntry = async (row) => {
    setBusy(true); setMessage(null);
    try {
      const result = await cacheService.read(row.key);
      if (!result.hit) throw new Error('This key is no longer present in the cache. Refresh the list and try again.');
      if (result.masked) throw new Error('The current role cannot read this value for editing.');
      setEditKey(row.key); setForm({ key: row.key, value: result.value, ttl: result.ttlMillis < 0 ? '0' : String(Math.ceil(result.ttlMillis / 1000)) }); setEntryModal(true);
      await Promise.all([performance.reload(), operations.reload()]);
    } catch (e) { setMessage({ ok: false, text: errorMessage(e) }); }
    finally { setBusy(false); }
  };

  const saveEntry = async (e) => {
    e.preventDefault();
    if (!form.key.trim()) return setMessage({ ok: false, text: 'Cache key is required.' });
    if (form.ttl !== '' && (!Number.isInteger(Number(form.ttl)) || Number(form.ttl) < 0 || Number(form.ttl) > 2_592_000)) return setMessage({ ok: false, text: 'TTL must be a whole number between 0 and 2,592,000 seconds.' });
    setBusy(true); setMessage(null);
    try {
      const ttl = form.ttl === '' ? undefined : Number(form.ttl);
      const result = editKey ? await cacheService.update(editKey, form.value, ttl ?? 0) : await cacheService.set(form.key.trim(), form.value, ttl);
      setMessage({ ok: true, text: `${editKey ? 'Updated' : 'Stored'} ${result.key} on ${result.writtenTo}; replication was submitted to ${result.replicas.length} target(s), with ${result.fallbackAttempts} primary fallback(s). Replica acknowledgements are tracked separately.` });
      setEntryModal(false); setEditKey(null); setForm({ key: '', value: '', ttl: '' });
      await refreshAfterOperation();
    } catch (e2) { setMessage({ ok: false, text: errorMessage(e2) }); }
    finally { setBusy(false); }
  };

  const updateTtl = async () => {
    if (!detailRow || !Number.isInteger(Number(ttlValue)) || Number(ttlValue) < 0 || Number(ttlValue) > 2_592_000) return setMessage({ ok: false, text: 'TTL must be a whole number between 0 and 2,592,000 seconds.' });
    setBusy(true); setMessage(null);
    try {
      const result = await cacheService.expire(detailRow.key, Number(ttlValue));
      setMessage({ ok: true, text: result.message });
      await refreshAfterOperation();
      const refreshed = (await cacheService.keys(detailRow.key, 1, reveal))[0];
      if (refreshed) { setDetailRow(refreshed); setTtlValue(refreshed.ttlMillis < 0 ? '0' : String(Math.ceil(refreshed.ttlMillis / 1000))); }
      else setDetailRow(null);
    } catch (e) { setMessage({ ok: false, text: errorMessage(e) }); }
    finally { setBusy(false); }
  };

  const deleteOne = async () => {
    const key = confirm?.key;
    if (!key) return;
    setBusy(true); setMessage(null);
    try { await cacheService.remove(key); setMessage({ ok: true, text: `Deleted ${key}; tombstone invalidation was sent through the cache engine.` }); setConfirm(null); setDetailRow(null); await refreshAfterOperation(); }
    catch (e) { setMessage({ ok: false, text: errorMessage(e) }); }
    finally { setBusy(false); }
  };

  const bulkDelete = async () => {
    const keys = Array.from(selectedKeys);
    if (!keys.length || keys.length > 500) return;
    setBusy(true); setMessage(null);
    try { const result = await cacheService.bulkDelete(keys); setMessage({ ok: result.failed.length === 0, text: `Deleted ${result.deleted.length}; ${Object.keys(result.failed).length} failed.` }); setSelectedKeys(new Set()); setDetailRow(null); setConfirm(null); await refreshAfterOperation(); }
    catch (e) { setMessage({ ok: false, text: errorMessage(e) }); }
    finally { setBusy(false); }
  };

  const clearCache = async () => {
    setBusy(true); setMessage(null);
    try { const result = await cacheService.clear(); setMessage({ ok: Object.keys(result.failed).length === 0, text: `Cleared ${result.deleted.length} entries; ${Object.keys(result.failed).length} failed.` }); setSelectedKeys(new Set()); setConfirm(null); setDetailRow(null); await refreshAfterOperation(); }
    catch (e) { setMessage({ ok: false, text: errorMessage(e) }); }
    finally { setBusy(false); }
  };

  const exportSelected = async () => {
    const chosen = (rows || []).filter((row) => selectedKeys.has(row.key));
    if (!chosen.length) return;
    setBusy(true);
    try {
      const values = await Promise.all(chosen.map(async (row) => {
        try { const read = await cacheService.read(row.key); return { ...row, value: read.hit ? read.value : null, valueMasked: read.masked, readResult: read.hit ? 'HIT' : 'MISS' }; }
        catch { return { ...row, value: null, valueMasked: true, readResult: 'UNAVAILABLE' }; }
      }));
      const url = URL.createObjectURL(new Blob([JSON.stringify(values, null, 2)], { type: 'application/json' }));
      const anchor = document.createElement('a'); anchor.href = url; anchor.download = 'cache-entries.json'; anchor.click(); URL.revokeObjectURL(url);
      await Promise.all([performance.reload(), operations.reload()]);
    } finally { setBusy(false); }
  };

  const toggleOne = (key) => setSelectedKeys((current) => { const next = new Set(current); next.has(key) ? next.delete(key) : next.add(key); return next; });
  const pageSelected = rows?.length > 0 && rows.every((row) => selectedKeys.has(row.key));
  const togglePage = () => setSelectedKeys((current) => { const next = new Set(current); if (pageSelected) rows.forEach((row) => next.delete(row.key)); else rows.forEach((row) => next.add(row.key)); return next; });

  const nodeIds = useMemo(() => (nodes.data || []).map((node) => node.id), [nodes.data]);
  const counters = performance.data?.counters;
  const hits = counters?.hits;
  const misses = counters?.misses;
  const readTotal = hits == null || misses == null ? null : hits + misses;
  const hitRatio = readTotal > 0 ? (100 * hits) / readTotal : null;
  const evictions = nodes.data ? nodes.data.reduce((total, node) => total + node.evictions, 0) : null;
  const columns = [
    { key: 'select', header: <input type="checkbox" aria-label="Select all entries on this page" checked={!!pageSelected} onChange={togglePage} onClick={(e) => e.stopPropagation()} />, render: (row) => <input type="checkbox" aria-label={`Select ${row.key}`} checked={selectedKeys.has(row.key)} onChange={() => toggleOne(row.key)} onClick={(e) => e.stopPropagation()} /> },
    { key: 'key', header: 'Key', render: (row) => <b className="mono">{row.key}</b> },
    { key: 'value', header: 'Value preview', render: (row) => <span className={row.masked ? 'muted' : 'mono'}>{row.valuePreview}</span> },
    { key: 'type', header: 'Type', render: (row) => row.dataType },
    { key: 'owner', header: 'Primary / replicas', render: (row) => <span>{row.primary || 'Unavailable'}<div className="small muted">{row.replicas.length ? row.replicas.join(', ') : 'No replicas'}</div></span> },
    { key: 'size', header: 'Size', render: (row) => fmtBytes(row.sizeBytes) },
    { key: 'created', header: 'Created', render: (row) => fmtDateTime(row.createdAt) },
    { key: 'ttl', header: 'Remaining TTL', render: (row) => row.ttlMillis < 0 ? 'No expiry' : <span>{fmtDuration(row.ttlMillis)}{row.ttlMillis <= Number(applied.expiringWithinSeconds) * 1000 ? <div className="small" style={{ color: '#c2640a' }}>Expiring soon</div> : null}</span> },
    { key: 'status', header: 'Status', render: (row) => row.ttlMillis === 0 ? 'Expiring' : 'Active' },
    { key: 'actions', header: 'Actions', render: (row) => <div className="row" style={{ flexWrap: 'nowrap', gap: 5 }}>
      <button className="btn small" title="View details" aria-label={`View ${row.key}`} onClick={(e) => { e.stopPropagation(); openDetails(row); }}><Eye size={13} /></button>
      <button className="btn small" disabled={busy} onClick={(e) => { e.stopPropagation(); openDetails(row).then(() => getEntry(row)); }}>GET</button>
      {canWrite && <button className="btn small" disabled={busy} onClick={(e) => { e.stopPropagation(); editEntry(row); }}>Edit</button>}
      {canWrite && <button className="btn small danger" disabled={busy} aria-label={`Delete ${row.key}`} onClick={(e) => { e.stopPropagation(); setConfirm({ type: 'delete', key: row.key }); }}><Trash2 size={13} /></button>}
    </div> },
  ];

  return <>
    <PageHeader title="Cache Explorer" subtitle="Inspect real cache entries, ownership, TTL and measured cache activity."
      actions={<div className="row"><button className="btn small" disabled={listLoading || busy} onClick={refreshAll}><RefreshCw size={14} />Refresh</button>{canWrite && <button className="btn primary" onClick={() => { setEditKey(null); setForm({ key: '', value: '', ttl: '' }); setEntryModal(true); }}><Plus size={14} />Add cache entry</button>}</div>} />
    {message && <div className={`alert ${message.ok ? 'ok' : 'error'}`} style={{ marginBottom: 14 }}>{message.text}</div>}

    <section aria-label="Cache overview">
      {overview.loading && !overview.data ? <LoadingState label="Loading cache measurements…" /> : overview.error && !overview.data ? <ErrorState message={overview.error} onRetry={overview.reload} /> : overview.data && <>
        <div className="grid metrics">
          <MetricCard label="Cache entries" value={fmtNum(overview.data.totalEntries)} />
          <MetricCard label="Cache nodes" value={nodes.data ? fmtNum(nodes.data.length) : 'Unavailable'} hint={nodes.data ? `${fmtNum(overview.data.activeNodes)} active` : 'Node API unavailable'} />
          <MetricCard label="Cache hits" value={performance.data ? fmtNum(hits) : 'Unavailable'} />
          <MetricCard label="Cache misses" value={performance.data ? fmtNum(misses) : 'Unavailable'} />
          <MetricCard label="Hit ratio" value={hitRatio === null ? 'Unavailable' : fmtPct(hitRatio)} hint={readTotal === 0 ? 'No reads measured' : `${fmtNum(readTotal)} measured reads`} />
          <MetricCard label="Cache memory" value={fmtPct(overview.data.memoryUtilizationPct)} />
          <MetricCard label="Evictions" value={evictions === null ? 'Unavailable' : fmtNum(evictions)} />
          <MetricCard label="Replication factor" value={fmtNum(overview.data.replicationFactor)} />
        </div>
        <div className="muted small" style={{ marginTop: 8 }}>Last successful metrics refresh: {fmtDateTime(overview.lastSuccessAt)}{overview.loading ? ' · Refreshing…' : ''}</div>
        {overview.error && <div className="alert warn">Metrics refresh failed: {overview.error}. Showing previous values. <button className="btn small" onClick={overview.reload}>Retry</button></div>}
      </>}
    </section>

    <div className="grid cols-2" style={{ marginTop: 16 }}>
      <section className="card"><div className="card-title"><h2>Cache performance</h2></div>{performance.loading && !performance.data ? <LoadingState /> : performance.error && !performance.data ? <ErrorState message={performance.error} onRetry={performance.reload} /> : performance.data && <>
        <div className="grid metrics"><MetricCard label="GET requests" value={fmtNum(counters?.gets)} /><MetricCard label="SET requests" value={fmtNum(counters?.sets)} /><MetricCard label="UPDATE requests" value={fmtNum(counters?.updates)} /><MetricCard label="Average latency" value={counters && performance.data.latency.samples ? fmtMs(performance.data.latency.avgMs, 2) : 'Unavailable'} /><MetricCard label="P95 / P99 latency" value={performance.data.latency.samples ? `${fmtMs(performance.data.latency.p95Ms, 1)} / ${fmtMs(performance.data.latency.p99Ms, 1)}` : 'Unavailable'} /><MetricCard label="Requests / second" value={overview.data ? Number(overview.data.requestsPerSecond).toFixed(1) : 'Unavailable'} /><MetricCard label="Evictions" value={evictions === null ? 'Unavailable' : fmtNum(evictions)} /></div>
        {performance.error && <div className="alert warn">Performance refresh failed: {performance.error}. Showing previous measurements.</div>}
      </>}</section>
      <section className="card"><div className="card-title"><h2>Measured request and cache reads (last minute)</h2></div>{traffic.loading && !traffic.data ? <LoadingState /> : traffic.error && !traffic.data ? <ErrorState message={traffic.error} onRetry={traffic.reload} /> : traffic.data && (traffic.data.some((point) => point.requests || point.hits || point.misses) ? <><RequestTrafficChart data={traffic.data} height={180} /><CacheHitMissChart data={traffic.data} height={180} /></> : <div className="state">No gateway traffic has been recorded in this time window.</div>)}{traffic.error && traffic.data && <div className="alert warn">Traffic refresh failed: {traffic.error}. Showing previous series.</div>}</section>
    </div>

    <form className="card stack" style={{ marginTop: 16 }} onSubmit={applyFilters}>
      <div className="form-grid">
        <label className="field">Search key or prefix<input value={filters.prefix} onChange={(e) => setFilters({ ...filters, prefix: e.target.value })} placeholder="Search cache keys" /></label>
        <label className="field">Primary node<select value={filters.primaryNode} onChange={(e) => setFilters({ ...filters, primaryNode: e.target.value })}><option value="">All primary nodes</option>{nodeIds.map((id) => <option key={id}>{id}</option>)}</select></label>
        <label className="field">Replica node<select value={filters.replicaNode} onChange={(e) => setFilters({ ...filters, replicaNode: e.target.value })}><option value="">All replica nodes</option>{nodeIds.map((id) => <option key={id}>{id}</option>)}</select></label>
        <label className="field">Expiration<select value={filters.expiration} onChange={(e) => setFilters({ ...filters, expiration: e.target.value })}><option value="ALL">All entries</option><option value="HAS_EXPIRY">Has expiration</option><option value="NO_EXPIRY">No expiration</option><option value="EXPIRING">Expiring soon</option></select></label>
        {filters.expiration === 'EXPIRING' && <label className="field">Expiring within (seconds)<input type="number" min="1" max="2592000" value={filters.expiringWithinSeconds} onChange={(e) => setFilters({ ...filters, expiringWithinSeconds: e.target.value })} /></label>}
        <label className="field">Sort by<select value={filters.sortBy} onChange={(e) => setFilters({ ...filters, sortBy: e.target.value })}><option value="KEY">Key</option><option value="SIZE">Entry size</option><option value="TTL">Remaining TTL</option></select></label>
        <label className="check"><input type="checkbox" checked={filters.descending} onChange={(e) => setFilters({ ...filters, descending: e.target.checked })} />Descending</label>
        <div className="row"><button className="btn primary"><Search size={14} />Search / apply</button><button type="button" className="btn" onClick={clearFilters}>Clear filters</button>
          {isAdmin && <button type="button" className="btn" onClick={() => { const next = !reveal; setReveal(next); fetchRows(0, applied, next); }}> {reveal ? 'Hide values' : 'Reveal values'}</button>}</div>
      </div>
      {nodes.error && <div className="alert warn">Node filter options could not refresh: {nodes.error}. {nodes.data ? `Using node list from ${fmtDateTime(nodes.lastSuccessAt)}.` : 'Node filters are unavailable.'} <button type="button" className="btn small" onClick={nodes.reload}>Retry</button></div>}
      {nodes.loading && !nodes.data && <LoadingState label="Loading node filter options…" />}
    </form>

    <div className="row" style={{ margin: '12px 0' }}>
      {canWrite && <><button className="btn small danger" disabled={!selectedKeys.size || selectedKeys.size > 500 || busy} onClick={() => setConfirm({ type: 'bulk' })}><Trash2 size={13} />Delete selected ({selectedKeys.size})</button><button className="btn small" disabled={!selectedKeys.size || busy} onClick={exportSelected}><Download size={13} />Export selected JSON</button><button className="btn small danger" disabled={busy} onClick={() => setConfirm({ type: 'clear' })}>Clear cache</button></>}
      <label className="muted small">Rows per page <select value={pageSize} onChange={(e) => { const size = Number(e.target.value); setPageSize(size); setSelectedKeys(new Set()); fetchRows(0, applied, reveal, size); }}><option value={25}>25</option><option value={50}>50</option><option value={100}>100</option><option value={250}>250</option></select></label>
      <span className="muted small">{rows ? `Showing ${offset + 1}–${offset + rows.length}${hasMore ? '+' : ''}` : 'Entries not loaded'}</span>
      <div className="row" style={{ marginLeft: 'auto' }}><button className="btn small" disabled={offset === 0 || listLoading} onClick={() => fetchRows(Math.max(0, offset - pageSize))}>Previous</button><button className="btn small" disabled={!hasMore || listLoading} onClick={() => fetchRows(offset + pageSize)}>Next</button></div>
    </div>
    {listError && <div className={`alert ${rows ? 'warn' : 'error'}`}>{rows ? `Refresh failed: ${listError}. Showing entries from ${fmtDateTime(listSuccessAt)}.` : listError}<button className="btn small" onClick={() => fetchRows(offset)}>Retry</button></div>}
    {listLoading && !rows ? <LoadingState label="Searching cache entries…" /> : <div className="card" style={{ padding: 8 }}>
      <DataTable columns={columns} rows={rows || []} rowKey={(row) => row.key} onRowClick={openDetails} selectedKey={detailRow?.key} empty={rows ? (applied.prefix ? 'No cache entries match this search and filter.' : 'The cache contains no live entries.') : 'Cache entries are not available.'} />
      {listSuccessAt && <div className="muted small" style={{ margin: 8 }}>Last successful entry refresh: {fmtDateTime(listSuccessAt)}{listLoading ? ' · Refreshing…' : ''}</div>}
    </div>}

    <section className="card" style={{ marginTop: 16 }} aria-label="Recent cache operations"><div className="card-title"><h2>Recent cache operations</h2><button className="btn small" onClick={operations.reload}>Refresh history</button></div>
      <div className="muted small" style={{ marginBottom: 8 }}>This process-local history combines measured gateway calls with node eviction timestamps and replication acknowledgements/failures. Original TTL and last-access timestamps are not retained by the entry model.</div>
      {operations.loading && !operations.data ? <LoadingState /> : operations.error && !operations.data ? <ErrorState message={operations.error} onRetry={operations.reload} /> : operations.data?.length ? <div className="table-wrap"><table className="data"><thead><tr><th>Time</th><th>Operation</th><th>Result</th><th>Node</th><th>Key</th><th>Latency</th></tr></thead><tbody>{operations.data.map((op, index) => <tr key={`${op.time}-${index}`}><td>{fmtDateTime(op.time)}</td><td>{op.operation}</td><td>{op.result}</td><td>{op.node || 'Unavailable'}</td><td className="mono">{op.key || '—'}</td><td>{fmtMs(op.latencyMs, 2)}</td></tr>)}</tbody></table></div> : <div className="state">No cache operations have been recorded in this process yet.</div>}
      {operations.error && operations.data && <div className="alert warn">Operation history refresh failed: {operations.error}. Showing data refreshed {fmtDateTime(operations.lastSuccessAt)}.</div>}
      {operations.lastSuccessAt && <div className="muted small" style={{ marginTop: 8 }}>Last successful history refresh: {fmtDateTime(operations.lastSuccessAt)}</div>}
    </section>

    <Modal open={!!detailRow} wide title={detailRow ? `Cache entry · ${detailRow.key}` : 'Cache entry'} onClose={() => { setDetailRow(null); setReadResult(null); }}>
      {detailRow && <div className="stack">
        {detailLoading ? <LoadingState label="Loading key ownership…" /> : <dl className="kv">
          <dt>Key</dt><dd className="mono">{detailRow.key}</dd><dt>Type / size</dt><dd>{detailRow.dataType} · {fmtBytes(detailRow.sizeBytes)} (engine estimate)</dd>
          <dt>Value</dt><dd className="mono" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{readResult?.hit ? (readResult.masked ? 'Value hidden for this role' : readResult.value) : readResult ? 'Key was not found (MISS).' : detailRow.masked ? 'Hidden until a permitted GET; preview is masked.' : 'Choose GET to retrieve the current value.'}</dd>
          <dt>Created</dt><dd>{fmtDateTime(detailRow.createdAt)}</dd><dt>Last updated</dt><dd>{fmtDateTime(detailRow.updatedAt)}</dd><dt>Last accessed</dt><dd>Unavailable: the cache engine does not retain last-access timestamps.</dd>
          <dt>Original TTL</dt><dd>Unavailable: only expiration time is retained.</dd><dt>Remaining TTL</dt><dd>{detailRow.ttlMillis < 0 ? 'No expiration' : fmtDuration(detailRow.ttlMillis)}</dd><dt>Expires at</dt><dd>{detailRow.expiresAt ? fmtDateTime(detailRow.expiresAt) : 'No expiration'}</dd>
          <dt>Hash ring position</dt><dd>{placement ? placement.ringPosition : 'Unavailable'}</dd><dt>Primary owner</dt><dd>{placement?.primary || detailRow.primary || 'Unavailable'} {placement?.primary && <span className="muted small">({placement.states[placement.primary] || 'state unavailable'})</span>}</dd>
          <dt>Replica owners</dt><dd>{placement ? placement.replicas.map((id) => `${id} (${placement.states[id] || 'state unavailable'})`).join(', ') || 'None configured/available' : detailRow.replicas.join(', ') || 'Unavailable'}</dd>
          <dt>Read result</dt><dd>{readResult ? `${readResult.hit ? 'HIT' : 'MISS'} · node ${readResult.servedBy || 'unavailable'} · ${readResult.attempts} attempt(s) · ${fmtMs(readResult.latencyMs, 2)}` : 'No GET issued yet'}</dd>
          <dt>Replication status</dt><dd>Placement shows configured owners and node states; per-key replica acknowledgements are not exposed by the cache API.</dd>
        </dl>}
        {readResult && !readResult.hit && <div className="alert warn">This GET was a measured cache miss. It has been included in backend hit/miss metrics.</div>}
        <div className="row"><button className="btn primary" disabled={busy || detailLoading} onClick={() => getEntry()}>{busy ? 'Getting…' : 'GET from cache'}</button>{canWrite && <><label className="field">Set remaining TTL (seconds; 0 means no expiration)<input type="number" min="0" max="2592000" value={ttlValue} onChange={(e) => setTtlValue(e.target.value)} /></label><button className="btn" disabled={busy} onClick={updateTtl}>Update TTL</button><button className="btn" disabled={busy} onClick={() => editEntry(detailRow)}>Edit value</button><button className="btn danger" disabled={busy} onClick={() => setConfirm({ type: 'delete', key: detailRow.key })}>Delete</button></>}</div>
      </div>}
    </Modal>

    <Modal open={entryModal} title={editKey ? `Update ${editKey}` : 'Add cache entry'} onClose={() => !busy && setEntryModal(false)}>
      <form className="stack" onSubmit={saveEntry}>
        <label className="field">Cache key<input required maxLength={200} value={form.key} disabled={!!editKey} onChange={(e) => setForm({ ...form, key: e.target.value })} /></label>
        <label className="field">Cache value<textarea required rows={5} value={form.value} onChange={(e) => setForm({ ...form, value: e.target.value })} /></label>
        <label className="field">Data type <input value="STRING (only type supported by cache engine)" readOnly /></label>
        <label className="field">TTL seconds (blank uses configured default on insert; 0 means no expiry)<input type="number" min="0" max="2592000" required={!!editKey} value={form.ttl} onChange={(e) => setForm({ ...form, ttl: e.target.value })} /></label>
        <div className="row" style={{ justifyContent: 'flex-end' }}><button type="button" className="btn" disabled={busy} onClick={() => setEntryModal(false)}>Cancel</button><button className="btn primary" disabled={busy}>{busy ? 'Saving…' : editKey ? 'Update entry' : 'Add entry'}</button></div>
      </form>
    </Modal>

    <ConfirmDialog open={confirm?.type === 'delete'} danger title={`Delete ${confirm?.key}?`} confirmLabel="Delete entry" busy={busy} message="This performs a cache-router delete and writes the normal replicated tombstone. The operation is audited." onCancel={() => !busy && setConfirm(null)} onConfirm={deleteOne} />
    <ConfirmDialog open={confirm?.type === 'bulk'} danger title={`Delete ${selectedKeys.size} selected entries?`} confirmLabel="Delete selected" busy={busy} message="Each selected key will be deleted through the cache router and replicated as a tombstone." onCancel={() => !busy && setConfirm(null)} onConfirm={bulkDelete} />
    <ConfirmDialog open={confirm?.type === 'clear'} danger title="Clear all live cache entries?" confirmLabel="Clear cache" busy={busy} message="This scans current active cache nodes and deletes every discovered live entry through the real cache router. Each delete is replicated and the clear request is audited." onCancel={() => !busy && setConfirm(null)} onConfirm={clearCache} />
  </>;
}
