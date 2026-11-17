import { Area, AreaChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { fmtTime } from '../../utils/format';

/** Requests per second (each point is one second of real gateway traffic) plus failures. */
export default function RequestTrafficChart({ data, height = 240, nodeOnly = false }) {
  return (
    <ResponsiveContainer width="100%" height={height}>
      <AreaChart data={data} margin={{ top: 6, right: 8, left: -14, bottom: 0 }}>
        <CartesianGrid stroke="#e8ecf4" vertical={false} />
        <XAxis dataKey="time" tickFormatter={fmtTime} tick={{ fontSize: 11 }} minTickGap={40} />
        <YAxis allowDecimals={false} tick={{ fontSize: 11 }} />
        <Tooltip labelFormatter={fmtTime} />
        <Legend wrapperStyle={{ fontSize: 12 }} />
        <Area type="monotone" dataKey="requests" name="Total" stroke="#2563eb" fill="#2563eb" fillOpacity={0.08} strokeWidth={2} isAnimationActive={false} />
        {!nodeOnly && <Area type="monotone" dataKey="getRequests" name="GET" stroke="#16a34a" fill="none" strokeWidth={1.5} isAnimationActive={false} />}
        {!nodeOnly && <Area type="monotone" dataKey="setRequests" name="SET" stroke="#9333ea" fill="none" strokeWidth={1.5} isAnimationActive={false} />}
        {!nodeOnly && <Area type="monotone" dataKey="updateRequests" name="UPDATE" stroke="#0891b2" fill="none" strokeWidth={1.5} isAnimationActive={false} />}
        {!nodeOnly && <Area type="monotone" dataKey="deleteRequests" name="DELETE" stroke="#e8890c" fill="none" strokeWidth={1.5} isAnimationActive={false} />}
        {!nodeOnly && <Area type="monotone" dataKey="failures" name="Failures" stroke="#dc2626" fill="none" strokeWidth={1.5} isAnimationActive={false} />}
      </AreaChart>
    </ResponsiveContainer>
  );
}
