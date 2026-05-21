// Load test for Bo's per-turn web-search permission gate.
//
// Every Bo turn calls this before deciding whether to invoke web_search.
// Latency here directly adds to every Bo response — if it's > 200ms p95
// users feel Bo as laggy on every interaction.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { COMMON, THRESHOLDS } from '../lib/common.js';

export const options = {
  stages: [
    { duration: '30s', target: 5 },
    { duration: '1m',  target: 5 },
    { duration: '30s', target: 50 },
    { duration: '1m',  target: 50 },
    { duration: '30s', target: 200 },
    { duration: '2m',  target: 200 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    // Tighter than other endpoints — this is on the hot path.
    http_req_duration: ['p(50)<200', 'p(95)<800', 'p(99)<2000'],
    http_req_failed: ['rate<0.01'],
  },
};

const URL = `${COMMON.SUPABASE_URL}/functions/v1/bo-web-gate`;

export default function () {
  const userId = `loadtest-vu-${__VU}`;
  const body = JSON.stringify({ user_id: userId });

  const res = http.post(URL, body, {
    headers: COMMON.anonHeaders(),
    tags: { name: 'bo-web-gate' },
    timeout: '5s',
  });

  check(res, {
    'gate 200': (r) => r.status === 200,
    'has decision': (r) => {
      try {
        const j = r.json();
        return typeof j.allow_web_search === 'boolean';
      } catch (_) { return false; }
    },
  });

  sleep(0.3);
}
