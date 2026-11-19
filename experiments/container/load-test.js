// Load test for esg-score-service: GET /api/scores/{id} with a random company id 1-20.
// Two scenarios: a short warm-up (JIT, connection pool), then the measured run.
// Every metric is tagged phase=warmup or phase=measure, so the measure part can be evaluated alone.
// No thresholds: the script only produces raw numbers; measure-images.ps1 evaluates them.
//
//   k6 run --env BASE_URL=http://localhost:18082 --env VUS=50 --env DURATION=60s load-test.js
import http from 'k6/http';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:18082';
const VUS = parseInt(__ENV.VUS || '50', 10);
const DURATION = __ENV.DURATION || '60s';
const WARMUP = __ENV.WARMUP || '10s';
const WARMUP_VUS = parseInt(__ENV.WARMUP_VUS || '10', 10);

export const options = {
  // only status codes and timings matter; not keeping bodies saves k6 memory and CPU
  discardResponseBodies: true,
  // 'rate' is not a valid trend statistic in k6 (it would abort the run); request rate is
  // reported through the http_reqs counter instead
  summaryTrendStats: ['med', 'p(99)', 'count'],
  scenarios: {
    warmup: {
      executor: 'constant-vus',
      vus: WARMUP_VUS,
      duration: WARMUP,
      tags: { phase: 'warmup' },
    },
    measure: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
      startTime: WARMUP,
      tags: { phase: 'measure' },
    },
  },
};

export default function () {
  const id = Math.floor(Math.random() * 20) + 1;
  // one name tag for all 20 URLs, so the output isn't split into 20 separate URL series
  http.get(`${BASE_URL}/api/scores/${id}`, { tags: { name: 'GET /api/scores/{id}' } });
}
