import { fmtTime } from '../../utils/format';

const tone = (type) => {
  if (/FAILED|FAILURE_INJECTED|ERROR|TIMEOUT|BLOCKED/.test(type)) return 'red';
  if (/SUSPECT|AWAITING|DETECTED|STARTED/.test(type)) return 'orange';
  if (/ACTIVE|ALIVE|COMPLETED|DONE|PROMOTED|READY|RESTARTED/.test(type)) return 'green';
  return '';
};

export default function SimulationTimeline({ events }) {
  if (!events || events.length === 0) return <div className="state">Waiting for events…</div>;
  const t0 = events[0].time;
  return (
    <ul className="timeline">
      {events.map((e, i) => (
        <li key={i}>
          <span className="t" title={fmtTime(e.time)}>+{((e.time - t0) / 1000).toFixed(1)}s</span>
          <span className={`bullet ${tone(e.type)}`} />
          <span><b className="small muted">{e.type.replaceAll('_', ' ')}</b><br />{e.message}</span>
        </li>
      ))}
    </ul>
  );
}
