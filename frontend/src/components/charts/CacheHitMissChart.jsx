import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { fmtTime } from '../../utils/format';

export default function CacheHitMissChart({ data, height = 240 }) {
  return (
    <ResponsiveContainer width="100%" height={height}>
      <BarChart data={data} margin={{ top: 6, right: 8, left: -14, bottom: 0 }}>
        <CartesianGrid stroke="#e8ecf4" vertical={false} />
        <XAxis dataKey="time" tickFormatter={fmtTime} tick={{ fontSize: 11 }} minTickGap={40} />
        <YAxis allowDecimals={false} tick={{ fontSize: 11 }} />
        <Tooltip labelFormatter={fmtTime} />
        <Legend wrapperStyle={{ fontSize: 12 }} />
        <Bar dataKey="hits" name="Hits" stackId="a" fill="#22a35a" isAnimationActive={false} />
        <Bar dataKey="misses" name="Misses" stackId="a" fill="#e8890c" isAnimationActive={false} />
      </BarChart>
    </ResponsiveContainer>
  );
}
