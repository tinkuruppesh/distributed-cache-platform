import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { fmtTime } from '../../utils/format';

/** Average latency per second (ms). P95/P99 are shown as cards from the sliding latency window. */
export default function LatencyChart({ data, height = 240 }) {
  return (
    <ResponsiveContainer width="100%" height={height}>
      <LineChart data={data} margin={{ top: 6, right: 8, left: -14, bottom: 0 }}>
        <CartesianGrid stroke="#e8ecf4" vertical={false} />
        <XAxis dataKey="time" tickFormatter={fmtTime} tick={{ fontSize: 11 }} minTickGap={40} />
        <YAxis tick={{ fontSize: 11 }} unit=" ms" />
        <Tooltip labelFormatter={fmtTime} formatter={(v) => `${Number(v).toFixed(2)} ms`} />
        <Line type="monotone" dataKey="avgLatencyMs" name="Avg latency" stroke="#9333ea" strokeWidth={2} dot={false} isAnimationActive={false} />
      </LineChart>
    </ResponsiveContainer>
  );
}
