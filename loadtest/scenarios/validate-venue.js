// Load test for the validate-venue edge function.
//
// Used by Bo's `recommend_and_save_venue` tool. Path:
//   1. lookup `venues` by name (DB)
//   2. if missing, hit Google Places API
//   3. insert/update `venues` row + return enriched data
//
// Step 2 has real Google Places cost ($0.017/call). To avoid hammering
// Google during a load test, the script sends RECYCLED venue names so
// most calls hit the DB-cache path. Override with NO_CACHE=1 to test
// the cold (Places-API) path — but expect bills.
//
// SAFETY: requires WRITE=1 + dev project URL.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { COMMON, pick, THRESHOLDS } from '../lib/common.js';

const WRITE = __ENV.WRITE === '1';
const NO_CACHE = __ENV.NO_CACHE === '1';
const FORCE_PROD = __ENV.FORCE_PROD === '1';

if (!WRITE) throw new Error('validate-venue is WRITE-only. Set WRITE=1.');
if (COMMON.SUPABASE_URL.includes('blifubxslaucshmlphdc') && !FORCE_PROD) {
  throw new Error('Refusing to run against prod (Google Places cost).');
}

// Names that almost certainly already exist in venues for a Boston-region
// dev project. Hitting these mostly exercises the DB-lookup branch.
const KNOWN_VENUES = [
  { name: 'Neptune Oyster', city: 'Boston' },
  { name: 'Row 34', city: 'Boston' },
  { name: 'Eataly', city: 'Boston' },
  { name: 'TD Garden', city: 'Boston' },
  { name: 'Wilbur Theatre', city: 'Boston' },
  { name: 'MIT Museum', city: 'Cambridge' },
  { name: 'The Sinclair', city: 'Cambridge' },
  { name: 'Lyric Stage', city: 'Boston' },
];

// Names guaranteed NOT in DB — every call hits Google Places.
const NEW_VENUES = [
  { name: `Loadtest Cafe ${Date.now()}-1`, city: 'Boston' },
  { name: `Loadtest Bar ${Date.now()}-2`, city: 'Cambridge' },
];

export const options = {
  stages: [
    { duration: '30s', target: 2 },
    { duration: '1m',  target: 5 },
    { duration: '30s', target: 10 },
    { duration: '1m',  target: 10 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(50)<800', 'p(95)<3000', 'p(99)<6000'],
    http_req_failed: ['rate<0.05'],
  },
};

const URL = `${COMMON.SUPABASE_URL}/functions/v1/validate-venue`;

export default function () {
  const pool = NO_CACHE ? NEW_VENUES : KNOWN_VENUES;
  const venue = pick(pool);

  const body = JSON.stringify({
    name: venue.name,
    city: venue.city,
    cuisine_type: 'restaurant',
  });

  const res = http.post(URL, body, {
    headers: COMMON.anonHeaders(),
    tags: { name: 'validate-venue', cached: NO_CACHE ? 'no' : 'yes' },
    timeout: '15s',
  });

  check(res, { 'validate 200': (r) => r.status === 200 });

  sleep(0.5);
}
