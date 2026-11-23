// Load test for esg-score-service: GET /api/scores/{id} with a random company id 1-20.
// Two scenarios: a warm-up (JIT, connection pool), then the measured run.
// Every metric is tagged phase=warmup or phase=measure, so the measure part can be evaluated alone.
// ONLY=warmup or ONLY=measure runs one scenario alone, so CPU and GC can be read between the two.
// The always-passing thresholds only make k6 compute the phase=measure submetrics for the summary.
//
//   k6 run --env BASE_URL=http://localhost:18082 --env VUS=50 --env DURATION=60s --env SUMMARY=out.json load-test.js
import http from 'k6/http';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:18082';
const VUS = parseInt(__ENV.VUS || '50', 10);
const DURATION = __ENV.DURATION || '60s';
const WARMUP = __ENV.WARMUP || '30s';
const WARMUP_VUS = parseInt(__ENV.WARMUP_VUS || '10', 10);
const SUMMARY = __ENV.SUMMARY || 'summary.json';
const ONLY = __ENV.ONLY || '';

const scenarios = {};
if (ONLY !== 'measure') {
  scenarios.warmup = { executor: 'constant-vus', vus: WARMUP_VUS, duration: WARMUP, tags: { phase: 'warmup' } };
}
if (ONLY !== 'warmup') {
  scenarios.measure = {
    executor: 'constant-vus',
    vus: VUS,
    duration: DURATION,
    startTime: ONLY === 'measure' ? '0s' : WARMUP,
    tags: { phase: 'measure' },
  };
}

export const options = {
  // only status codes and timings matter; not keeping bodies saves k6 memory and CPU
  discardResponseBodies: true,
  // 'rate' is not a valid trend statistic in k6 (it would abort the run); request rate is
  // reported through the http_reqs counter instead
  summaryTrendStats: ['med', 'p(99)', 'max', 'count'],
  thresholds: ONLY === 'warmup' ? {} : {
    'http_req_duration{phase:measure}': ['p(99)>=0'],
    'http_reqs{phase:measure}': ['count>=0'],
    'http_req_failed{phase:measure}': ['rate>=0'],
  },
  scenarios,
};

export default function () {
  const id = Math.floor(Math.random() * 20) + 1;
  // one name tag for all 20 URLs, so the output isn't split into 20 separate URL series
  http.get(`${BASE_URL}/api/scores/${id}`, { tags: { name: 'GET /api/scores/{id}' } });
}

// the end-of-test summary as JSON (instead of one JSON line per request, which reaches gigabytes in long runs)
export function handleSummary(data) {
  return { [SUMMARY]: JSON.stringify(data) };
}
