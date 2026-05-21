// Load test for the three-lane stratified search RPC.
//
// We've already seen this RPC hit Postgres' 8s statement timeout under
// real-user load. Goal here: find the concurrency cliff and confirm v3
// (with the venue-coords priority + bbox prefilter) holds up.
//
// Each VU iteration picks a random Greater-Boston lat/lng + random date
// + maybe a keyword, hammering the RPC's three lanes (breadth, niche,
// keyword) with realistic input distribution.

import http from 'k6/http';
import { check, sleep } from 'k6';
import {
  COMMON, randomLatLng, randomFutureDate, pick, KEYWORDS,
  THRESHOLDS, RAMP_READ,
} from '../lib/common.js';

export const options = {
  stages: RAMP_READ,
  thresholds: {
    ...THRESHOLDS.medium,
    // Stricter for the RPC — anything > 5s is a red flag.
    http_req_duration: ['p(50)<500', 'p(95)<3000', 'p(99)<5000'],
    'http_req_duration{name:rpc-stratified}': ['p(95)<3000'],
  },
};

const URL = `${COMMON.SUPABASE_URL}/rest/v1/rpc/search_events_stratified`;

export default function () {
  const { lat, lng } = randomLatLng();
  const startDate = randomFutureDate(60);
  const endDate = startDate; // same-day search — most common Bo pattern

  // 30% of iterations carry a keyword, mirroring real chat inputs.
  const keyword = Math.random() < 0.3 ? pick(KEYWORDS) : null;

  const body = JSON.stringify({
    p_user_lat: lat,
    p_user_lng: lng,
    p_radius_miles: 20,
    p_start_at: `${startDate}T00:00:00-04:00`,
    p_end_at: `${endDate}T23:59:59-04:00`,
    p_keyword: keyword,
    p_breadth_per_category: 25,
    p_max_results: 150,
  });

  const res = http.post(URL, body, {
    headers: COMMON.anonHeaders(),
    tags: { name: 'rpc-stratified' },
    timeout: '15s',
  });

  check(res, {
    'rpc 200': (r) => r.status === 200,
    'rpc returns array': (r) => {
      try {
        const j = r.json();
        return Array.isArray(j);
      } catch (_) { return false; }
    },
    // No statement timeouts — those are 504s with code 57014 in body.
    'no statement timeout': (r) => !r.body || !r.body.includes('57014'),
  });

  sleep(0.5);
}
