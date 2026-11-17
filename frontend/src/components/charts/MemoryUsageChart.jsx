import { CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { fmtTime, nodeColor } from '../../utils/format';

/** Memory usage (% of limit) per node over time, sampled by the backend every 2 seconds. */
export default function MemoryUsageChart({ history, nodeIds, height = 260 }) {
  const data = (history || []).map((p) => ({ time: p.time, ...p.memoryPct }));
  return (
    <ResponsiveContainer width="100%" height={height}>
      <LineChart data={data} margin={{ top: 6, right: 8, left: -14, bottom: 0 }}>
        <CartesianGrid stroke="#e8ecf4" vertical={false} />
        <XAxis dataKey="time" tickFormatter={fmtTime} tick={{ fontSize: 11 }} minTickGap={50} />
        <YAxis tick={{ fontSize: 11 }} unit="%" domain={[0, (max) => Math.max(5, Math.ceil(max * 1.2))]} />
        <Tooltip labelFormatter={fmtTime} formatter={(v) => `${v}%`} />
        <Legend wrapperStyle={{ fontSize: 12 }} />
        {(nodeIds || []).map((id) => (
          <Line key={id} type="monotone" dataKey={id} stroke={nodeColor(id, nodeIds)} strokeWidth={2} dot={false} isAnimationActive={false} />
        ))}
      </LineChart>
    </ResponsiveContainer>
  );
}
