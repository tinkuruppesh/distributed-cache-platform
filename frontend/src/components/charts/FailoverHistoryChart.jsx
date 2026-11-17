import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';

/** One bar group per failover: how long detection took (last heartbeat -> FAILED) and how long routing took to update. */
export default function FailoverHistoryChart({ failovers, height = 240 }) {
  const data = [...(failovers || [])].reverse().map((f, i) => ({
    name: `#${i + 1} ${f.nodeId}`,
    detection: Math.max(0, f.detectedAt - f.lastHeartbeatAt),
    routing: Math.max(0, f.failoverCompletedAt - f.failoverStartedAt),
  }));
  if (data.length === 0) return <div className="state">No failovers yet.</div>;
  return (
    <ResponsiveContainer width="100%" height={height}>
      <BarChart data={data} margin={{ top: 6, right: 8, left: -14, bottom: 0 }}>
        <CartesianGrid stroke="#e8ecf4" vertical={false} />
        <XAxis dataKey="name" tick={{ fontSize: 11 }} />
        <YAxis tick={{ fontSize: 11 }} unit=" ms" />
        <Tooltip formatter={(v) => `${v} ms`} />
        <Legend wrapperStyle={{ fontSize: 12 }} />
        <Bar dataKey="detection" name="Detection time" fill="#e8890c" isAnimationActive={false} />
        <Bar dataKey="routing" name="Routing update" fill="#2563eb" isAnimationActive={false} />
      </BarChart>
    </ResponsiveContainer>
  );
}
