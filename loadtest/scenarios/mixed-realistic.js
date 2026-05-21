// Composite scenario — multiple endpoints concurrently, weighted to
// reflect typical app behavior. Use this to estimate real-world capacity
// rather than per-endpoint cliffs.
//
// Mix (per iteration, weighted random):
//   60% reads  : 25% carousels, 25% RPC search, 10% event detail
//   30% writes : 30% community_interactions (the prod hot-write path)
//   10% Bo turn: dry-run only (no Anthropic spend by default)
//
// Set WRITE=1 to enable the interactions slice; if not set, that 30%
// converts to additional reads.
// Set ANTHROPIC_API_KEY to enable the Bo slice; if missing, that 10%
// also converts to reads.

import http from 'k6/http';
import { check, sleep } from 'k6';
import {
  COMMON, randomLatLng, randomFutureDate, pick, KEYWORDS,
} from '../lib/common.js';

const WRITE = __ENV.WRITE === '1';
const FORCE_PROD = __ENV.FORCE_PROD === '1';
const TEST_USER_ID = __ENV.TEST_USER_ID || '';
const HAS_BO = !!COMMON.ANTHROPIC_KEY;

const writesEnabled = WRITE && COMMON.SERVICE_KEY && TEST_USER_ID &&
  (!COMMON.SUPABASE_URL.includes('blifubxslaucshmlphdc') || FORCE_PROD);

export const options = {
  scenarios: {
    realistic: {
      executor: 'ramping-vus',
      startVUs: 5,
      stages: [
        { duration: '1m',  target: 25 },
        { duration: '2m',  target: 50 },
        { duration: '3m',  target: 100 },
        { duration: '2m',  target: 150 },
        { duration: '1m',  target: 0 },
      ],
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    http_req_duration: ['p(50)<800', 'p(95)<4000', 'p(99)<8000'],
    http_req_failed: ['rate<0.03'],
  },
};

let eventIdPool = [];

function loadEventPool() {
  const r = http.get(
    `${COMMON.SUPABASE_URL}/rest/v1/events?is_active=eq.true&select=id&limit=300`,
    { headers: COMMON.anonHeaders() }
  );
  if (r.status !== 200) return [];
  try { return r.json().map((e) => e.id); } catch (_) { return []; }
}

function doCarousels() {
  const { lat, lng } = randomLatLng();
  const url =
    `${COMMON.SUPABASE_URL}/functions/v1/carousels` +
    `?lat=${lat.toFixed(4)}&lng=${lng.toFixed(4)}&user_id=loadtest-${__VU}`;
  const r = http.get(url, { headers: COMMON.anonHeaders(), tags: { name: 'mixed-carousels' }, timeout: '15s' });
  check(r, { 'carousels 200': (x) => x.status === 200 });
}

function doRpcSearch() {
  const { lat, lng } = randomLatLng();
  const date = randomFutureDate(60);
  const body = JSON.stringify({
    p_user_lat: lat, p_user_lng: lng, p_radius_miles: 20,
    p_start_at: `${date}T00:00:00-04:00`, p_end_at: `${date}T23:59:59-04:00`,
    p_keyword: Math.random() < 0.3 ? pick(KEYWORDS) : null,
    p_breadth_per_category: 25, p_max_results: 150,
  });
  const r = http.post(
    `${COMMON.SUPABASE_URL}/rest/v1/rpc/search_events_stratified`,
    body, { headers: COMMON.anonHeaders(), tags: { name: 'mixed-rpc' }, timeout: '15s' }
  );
  check(r, { 'rpc 200': (x) => x.status === 200 });
}

function doEventDetail() {
  if (eventIdPool.length === 0) eventIdPool = loadEventPool();
  if (eventIdPool.length === 0) return;
  const id = pick(eventIdPool);
  const r = http.get(
    `${COMMON.SUPABASE_URL}/rest/v1/events?id=eq.${id}&select=*`,
    { headers: COMMON.anonHeaders(), tags: { name: 'mixed-detail' }, timeout: '5s' }
  );
  check(r, { 'detail 200': (x) => x.status === 200 });
}

function doInteraction() {
  if (eventIdPool.length === 0) eventIdPool = loadEventPool();
  if (eventIdPool.length === 0) return;
  const body = JSON.stringify({
    user_id: TEST_USER_ID,
    item_id: pick(eventIdPool),
    item_type: 'event',
    interaction_type: pick(['click', 'favorite', 'share', 'plan_add']),
    category: pick(['music', 'sports', 'comedy', 'arts']),
    source: 'loadtest',
  });
  const r = http.post(
    `${COMMON.SUPABASE_URL}/rest/v1/community_interactions`,
    body,
    { headers: { ...COMMON.serviceHeaders(), Prefer: 'return=minimal' }, tags: { name: 'mixed-interaction' }, timeout: '5s' }
  );
  check(r, { 'interaction 201': (x) => x.status === 201 });
}

function doBoDry() {
  const body = JSON.stringify({
    model: 'claude-haiku-4-5-20251001',
    max_tokens: 50,
    messages: [{ role: 'user', content: 'ok' }],
  });
  const r = http.post('https://api.anthropic.com/v1/messages', body, {
    headers: {
      'x-api-key': COMMON.ANTHROPIC_KEY,
      'anthropic-version': '2023-06-01',
      'Content-Type': 'application/json',
    },
    tags: { name: 'mixed-bo' },
    timeout: '30s',
  });
  check(r, { 'bo 200': (x) => x.status === 200 });
}

export default function () {
  const r = Math.random();

  // Reads: 60%
  if (r < 0.25)      doCarousels();
  else if (r < 0.50) doRpcSearch();
  else if (r < 0.60) doEventDetail();
  // Writes: 30% (or fall back to reads if disabled)
  else if (r < 0.90) {
    if (writesEnabled) doInteraction();
    else doRpcSearch();
  }
  // Bo: 10% (or fall back)
  else {
    if (HAS_BO) doBoDry();
    else doCarousels();
  }

  sleep(0.3 + Math.random() * 0.7); // 0.3–1.0s think time
}

export function teardown() {
  if (writesEnabled) {
    http.del(
      `${COMMON.SUPABASE_URL}/rest/v1/community_interactions?source=eq.loadtest`,
      null, { headers: COMMON.serviceHeaders() }
    );
  }
}
