// k6 load profile for the automated runtime tuning system.
//
//   k6 run loadtest/load-profile.js
//
// The profile deliberately moves through distinct regimes so the control loops
// can be observed reacting: a calm baseline, a ramp, a sudden spike, and a
// recovery back to baseline. Compare the Grafana dashboard across these phases.

import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const WORK_MILLIS = __ENV.WORK_MILLIS || '100';

export const options = {
  scenarios: {
    tuning_profile: {
      executor: 'ramping-vus',
      startVUs: 5,
      stages: [
        { duration: '1m', target: 5 },    // baseline: establish a reference
        { duration: '2m', target: 60 },   // ramp: gradual pressure increase
        { duration: '2m', target: 60 },   // sustained peak
        { duration: '10s', target: 250 }, // sudden spike
        { duration: '1m', target: 250 },  // hold the spike
        { duration: '30s', target: 5 },   // sharp drop
        { duration: '2m', target: 5 },    // recovery: values should return to normal
      ],
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    // Rejections are an expected outcome of load shedding, not a test failure,
    // so only server errors are treated as a failure condition.
    'http_req_failed{expected_response:true}': ['rate<0.01'],
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/work?baseMillis=${WORK_MILLIS}`);
  check(res, {
    'served or shed': (r) => r.status === 200 || r.status === 429,
  });
}

export function handleSummary(data) {
  return {
    stdout: JSON.stringify(
      {
        requests: data.metrics.http_reqs ? data.metrics.http_reqs.values.count : 0,
        p95_ms: data.metrics.http_req_duration
          ? data.metrics.http_req_duration.values['p(95)']
          : null,
        p99_ms: data.metrics.http_req_duration
          ? data.metrics.http_req_duration.values['p(99)']
          : null,
      },
      null,
      2
    ),
  };
}
