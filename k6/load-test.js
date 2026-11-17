// k6 load test for the cache gateway.
//   k6 run -e VUS=100 -e DURATION=60s -e ADMIN_PASSWORD='...' k6/load-test.js
//   k6 run -e VUS=500 ...      k6 run -e VUS=1000 ...
// Run it once on a healthy cluster, once while a node is failed (Failure Simulation page), once during recovery,
// and compare. Only publish numbers you actually measured on your own hardware.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 100);
const DURATION = __ENV.DURATION || '60s';
const KEYSPACE = Number(__ENV.KEYSPACE || 5000);
const READ_RATIO = Number(__ENV.READ_RATIO || 0.8);

const hits = new Counter('cache_hits');
const misses = new Counter('cache_misses');
const errors = new Rate('request_errors');
const getLatency = new Trend('get_latency', true);
const setLatency = new Trend('set_latency', true);

export const options = {
  scenarios: {
    load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: VUS },
        { duration: DURATION, target: VUS },
        { duration: '5s', target: 0 },
      ],
    },
  },
  thresholds: {
    request_errors: ['rate<0.01'],
    http_req_duration: ['p(95)<250', 'p(99)<600'],
  },
};

export function setup() {
  const res = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({ username: 'admin', password: __ENV.ADMIN_PASSWORD || 'Admin@12345' }),
    { headers: { 'Content-Type': 'application/json' } });
  if (res.status !== 200) throw new Error(`login failed: ${res.status} ${res.body}`);
  const token = res.json('token');
  // warm the cache so reads can hit
  const headers = { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
  for (let i = 0; i < Math.min(KEYSPACE, 1000); i++)
    http.post(`${BASE}/api/v1/cache`, JSON.stringify({ key: `load:${i}`, value: `value-${i}`, ttlSeconds: 600 }), { headers });
  return { token };
}

export default function (data) {
  const headers = { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}` };
  const k = Math.floor(Math.random() * KEYSPACE);
  if (Math.random() < READ_RATIO) {
    const r = http.get(`${BASE}/api/v1/cache/load:${k}`, { headers, tags: { op: 'get' } });
    getLatency.add(r.timings.duration);
    const ok = check(r, { 'get 200/404': (x) => x.status === 200 || x.status === 404 });
    errors.add(!ok);
    if (r.status === 200) hits.add(1); else if (r.status === 404) misses.add(1);
  } else {
    const r = http.post(`${BASE}/api/v1/cache`, JSON.stringify({ key: `load:${k}`, value: `value-${Date.now()}`, ttlSeconds: 600 }), { headers, tags: { op: 'set' } });
    setLatency.add(r.timings.duration);
    errors.add(!check(r, { 'set 201': (x) => x.status === 201 }));
  }
  sleep(0.01);
}

export function handleSummary(data) {
  const m = data.metrics;
  const hit = (m.cache_hits?.values.count || 0), miss = (m.cache_misses?.values.count || 0);
  const out = {
    requests: m.http_reqs?.values.count, rps: m.http_reqs?.values.rate,
    avg_ms: m.http_req_duration?.values.avg, p95_ms: m.http_req_duration?.values['p(95)'], p99_ms: m.http_req_duration?.values['p(99)'],
    error_rate: m.request_errors?.values.rate, hit_ratio: hit + miss ? hit / (hit + miss) : null, vus: VUS,
  };
  return { stdout: JSON.stringify(out, null, 2) + '\n', 'k6/last-result.json': JSON.stringify(out, null, 2) };
}
