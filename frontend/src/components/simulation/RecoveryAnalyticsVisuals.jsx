import { CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { fmtDuration } from '../../utils/format';

const COLORS = { detection: '#e8890c', failover: '#2563eb', recovery: '#16a34a', success: '#16a34a', error: '#dc2626' };

function TrendTooltip({ active, payload, label }) {
  if (!active || !payload?.length) return null;
  const sampleFields = {
    avgDetectionMs: 'detectionSamples', avgFailoverMs: 'failoverSamples', avgRecoveryMs: 'recoverySamples',
    requestSuccessPct: 'requestSamples', errorRatePct: 'requestSamples',
  };
  const point = payload[0].payload;
  return (
    <div className="card" style={{ padding: 10, fontSize: 12 }}>
      <b>{label}</b>
      {payload.map((item) => {
        const samples = point[sampleFields[item.dataKey]];
        const isPercent = item.dataKey.endsWith('Pct');
        const value = item.value == null ? 'N/A' : isPercent ? `${Number(item.value).toFixed(2)}%` : fmtDuration(item.value);
        return <div key={item.dataKey} style={{ color: item.color }}>{item.name}: {value} {samples == null ? '' : `(${samples} measurements)`}</div>;
      })}
      <div className="muted">{point.simulations} simulation{point.simulations === 1 ? '' : 's'} in this date bucket</div>
      {point.requestsTotal > 0 && <div className="muted">{point.requestsSucceeded} / {point.requestsTotal} measured requests</div>}
    </div>
  );
}

function TrendPanel({ title, description, data, lines, unit = ' ms', percent = false }) {
  const hasValues = data.some((point) => lines.some((line) => point[line.key] != null));
  return (
    <div className="card">
      <div className="card-title"><div><h2>{title}</h2><div className="muted small">{description}</div></div></div>
      {!data.length || !hasValues ? <div className="state">No measured results for this chart in the selected filters.</div> : (
        <ResponsiveContainer width="100%" height={245}>
          <LineChart data={data} margin={{ top: 8, right: 16, left: -12, bottom: 2 }}>
            <CartesianGrid stroke="#e8ecf4" vertical={false} />
            <XAxis dataKey="date" tick={{ fontSize: 11 }} minTickGap={28} />
            <YAxis tick={{ fontSize: 11 }} unit={percent ? '%' : unit} />
            <Tooltip content={<TrendTooltip />} />
            {lines.length > 1 && <Legend wrapperStyle={{ fontSize: 12 }} />}
            {lines.map((line) => <Line key={line.key} type="monotone" dataKey={line.key} name={line.name}
              stroke={line.color} strokeWidth={2} dot={{ r: 3 }} connectNulls={false} isAnimationActive={false} />)}
          </LineChart>
        </ResponsiveContainer>
      )}
    </div>
  );
}

function ComparisonTable({ title, description, rows }) {
  if (!rows.length) return <div className="card"><h2>{title}</h2><div className="state">No simulation records match these filters.</div></div>;
  const metric = (value, samples) => <>{value == null ? 'N/A' : fmtDuration(value)} <span className="muted small">({samples} samples)</span></>;
  return (
    <div className="card">
      <div className="card-title"><div><h2>{title}</h2><div className="muted small">{description}</div></div></div>
      <div className="table-wrap">
        <table className="data">
          <thead><tr><th>{title.includes('Failure mode') ? 'Failure mode' : 'Node'}</th><th>Simulations</th><th>Detection average</th><th>Failover average</th><th>Recovery average</th></tr></thead>
          <tbody>{rows.map((row) => <tr key={row.group}>
            <td><b>{row.group}</b></td><td>{row.simulations}</td>
            <td>{metric(row.avgDetectionMs, row.detectionSamples)}</td>
            <td>{metric(row.avgFailoverMs, row.failoverSamples)}</td>
            <td>{metric(row.avgRecoveryMs, row.recoverySamples)}</td>
          </tr>)}</tbody>
        </table>
      </div>
    </div>
  );
}

export default function RecoveryAnalyticsVisuals({ trends, byNode, byMode, slowestObservedRecoveryNode }) {
  return (
    <div className="stack">
      <div className="grid cols-2">
        <TrendPanel title="Failure detection trend" description="Daily mean of measured detection durations; tooltip shows sample count." data={trends}
          lines={[{ key: 'avgDetectionMs', name: 'Detection', color: COLORS.detection }]} />
        <TrendPanel title="Failover trend" description="Daily mean from recorded failover start and completion times." data={trends}
          lines={[{ key: 'avgFailoverMs', name: 'Failover', color: COLORS.failover }]} />
        <TrendPanel title="Recovery trend" description="Daily mean of measured restart-to-recovery durations." data={trends}
          lines={[{ key: 'avgRecoveryMs', name: 'Recovery', color: COLORS.recovery }]} />
        <TrendPanel title="Request success and error rate" description="Request-counter totals grouped by simulation start date." data={trends} percent
          lines={[{ key: 'requestSuccessPct', name: 'Success rate', color: COLORS.success }, { key: 'errorRatePct', name: 'Error rate', color: COLORS.error }]} />
        <TrendPanel title="Simulation performance over time" description="Detection, failover and recovery means in milliseconds by date." data={trends}
          lines={[{ key: 'avgDetectionMs', name: 'Detection', color: COLORS.detection }, { key: 'avgFailoverMs', name: 'Failover', color: COLORS.failover }, { key: 'avgRecoveryMs', name: 'Recovery', color: COLORS.recovery }]} />
      </div>
      <div className="grid cols-2">
        <ComparisonTable title="Node-wise recovery comparison" description="Observed measurements by affected node; sample counts are shown per metric." rows={byNode} />
        <ComparisonTable title="Failure mode comparison" description="Observed measurements by failure mode; sample counts are shown per metric." rows={byMode} />
      </div>
      {slowestObservedRecoveryNode
        ? <div className="alert info">Highest observed mean recovery time in this filtered sample: <b>{slowestObservedRecoveryNode}</b>. This is a descriptive comparison, not a node health assessment.</div>
        : <div className="alert info">A slower node cannot be compared yet; at least two nodes need measured recovery results in the selected filters.</div>}
    </div>
  );
}
