import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { fmtTime } from '../../utils/format';

/** Monotonic elapsed time around each replica RPC, including failures and stale acknowledgements. */
export default function ReplicationLagChart({ samples, height = 240 }) {
  return (
    <ResponsiveContainer width="100%" height={height}>
      <LineChart data={samples} margin={{ top: 6, right: 8, left: -14, bottom: 0 }}>
        <CartesianGrid stroke="#e8ecf4" vertical={false} />
        <XAxis dataKey="time" tickFormatter={fmtTime} tick={{ fontSize: 11 }} minTickGap={50} />
        <YAxis tick={{ fontSize: 11 }} unit=" ms" />
        <Tooltip labelFormatter={fmtTime} formatter={(value, name, point) => [`${Number(value).toFixed(2)} ms · ${point.payload.node} · ${point.payload.result}`, 'RPC latency']} />
        <Line type="monotone" dataKey="latencyMs" name="RPC latency" stroke="#0891b2" strokeWidth={2} dot={false} isAnimationActive={false} />
      </LineChart>
    </ResponsiveContainer>
  );
}
