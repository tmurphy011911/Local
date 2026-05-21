// Load test for the event detail load (post-tap nav).
//
// Each VU pulls a small batch of recent active events on first iter,
// then on each subsequent iter picks a random id and fetches its full
// detail row. Mirrors the "user taps an event card" flow.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { COMMON, pick, THRESHOLDS, RAMP_READ } from '../lib/common.js';

export const options = {
  stages: RAMP_READ,
  thresholds: THRESHOLDS.fast,
};

// Pull a pool of event IDs once per VU (init-time work, not counted).
let eventIds = [];

function loadEventPool() {
  const url =
    `${COMMON.SUPABASE_URL}/rest/v1/events` +
    `?select=id&is_active=eq.true&order=start_at.asc&limit=200`;
  const r = http.get(url, { headers: COMMON.anonHeaders() });
  if (r.status !== 200) return [];
  try {
    return r.json().map((e) => e.id);
  } catch (_) { return []; }
}

export default function () {
  if (eventIds.length === 0) {
    eventIds = loadEventPool();
    if (eventIds.length === 0) return;
  }

  const id = pick(eventIds);
  const url =
    `${COMMON.SUPABASE_URL}/rest/v1/events` +
    `?id=eq.${id}&select=*`;

  const res = http.get(url, {
    headers: COMMON.anonHeaders(),
    tags: { name: 'event-detail' },
    timeout: '5s',
  });

  check(res, {
    'detail 200': (r) => r.status === 200,
    'returns row': (r) => {
      try {
        const j = r.json();
        return Array.isArray(j) && j.length === 1;
      } catch (_) { return false; }
    },
  });

  sleep(0.4);
}
