// Load test for community_interactions writes.
//
// This is the closest to the real prod hot-write path: every event tap,
// favorite, share, and plan_add inserts a row. At app scale this will
// be the highest-RPS write endpoint by an order of magnitude.
//
// Append-only — no contention concerns aside from raw insert throughput
// and downstream materialization (get_community_scores aggregates).
//
// SAFETY: requires SUPABASE_SERVICE_KEY + WRITE=1. Tags source='loadtest'
// for cleanup.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { COMMON, pick, RAMP_WRITE } from '../lib/common.js';

const WRITE = __ENV.WRITE === '1';
const FORCE_PROD = __ENV.FORCE_PROD === '1';
const TEST_USER_ID = __ENV.TEST_USER_ID || '';

if (!WRITE) throw new Error('interactions is WRITE-only. Set WRITE=1.');
if (!COMMON.SERVICE_KEY) throw new Error('SUPABASE_SERVICE_KEY required.');
if (!TEST_USER_ID) throw new Error('TEST_USER_ID required.');
if (COMMON.SUPABASE_URL.includes('blifubxslaucshmlphdc') && !FORCE_PROD) {
  throw new Error('Refusing to run against prod. Use a dev project.');
}

export const options = {
  // Push harder than other writes — this is the high-RPS path in real life.
  stages: [
    { duration: '30s', target: 5 },
    { duration: '1m',  target: 25 },
    { duration: '30s', target: 100 },
    { duration: '2m',  target: 100 },
    { duration: '30s', target: 250 },
    { duration: '2m',  target: 250 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(50)<200', 'p(95)<800', 'p(99)<2000'],
    http_req_failed: ['rate<0.01'],
  },
};

const URL = `${COMMON.SUPABASE_URL}/rest/v1/community_interactions`;

const TYPES = ['click', 'favorite', 'share', 'plan_add'];
const ITEM_TYPES = ['event', 'place'];
const CATEGORIES = ['music', 'sports', 'comedy', 'theatre', 'arts', 'food'];

// Pre-pull a pool of real event/venue ids once — using random UUIDs would
// fail RLS or just spam the DB with bad fks if there's a constraint.
let itemPool = [];

function loadItemPool() {
  const r = http.get(
    `${COMMON.SUPABASE_URL}/rest/v1/events?is_active=eq.true&select=id&limit=500`,
    { headers: COMMON.anonHeaders() }
  );
  if (r.status === 200) {
    try { return r.json().map((e) => e.id); } catch (_) {}
  }
  return [];
}

export default function () {
  if (itemPool.length === 0) itemPool = loadItemPool();
  if (itemPool.length === 0) return;

  const body = JSON.stringify({
    user_id: TEST_USER_ID,
    item_id: pick(itemPool),
    item_type: pick(ITEM_TYPES),
    interaction_type: pick(TYPES),
    category: pick(CATEGORIES),
    source: 'loadtest',
  });

  const res = http.post(URL, body, {
    headers: { ...COMMON.serviceHeaders(), Prefer: 'return=minimal' },
    tags: { name: 'interactions' },
    timeout: '5s',
  });

  check(res, { 'insert 201': (r) => r.status === 201 });

  // Tight loop — interactions writes are fast and frequent in real life.
  sleep(0.1);
}

export function teardown() {
  const url = `${COMMON.SUPABASE_URL}/rest/v1/community_interactions?source=eq.loadtest`;
  http.del(url, null, { headers: COMMON.serviceHeaders() });
}
