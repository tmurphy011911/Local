// Load test for the home-feed carousels edge function.
//
// `carousels` caches per (lat, lng, user_id) key for 15 min. To stress
// the cold path (the actual scoring/filtering work), each VU rotates
// lat/lng across the metro and uses a unique user_id — guaranteeing a
// cache miss on every call. To stress the warm path, set WARM=1 in the
// env (each VU reuses the same lat/lng).

import http from 'k6/http';
import { check, sleep } from 'k6';
import {
  COMMON, randomLatLng, THRESHOLDS, RAMP_READ,
} from '../lib/common.js';

const WARM = __ENV.WARM === '1';

export const options = {
  // Carousels is heavier than a raw RPC call — cap lower than other reads.
  stages: [
    { duration: '30s', target: 5 },
    { duration: '1m',  target: 5 },
    { duration: '30s', target: 25 },
    { duration: '1m',  target: 25 },
    { duration: '30s', target: 100 },
    { duration: '2m',  target: 100 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(50)<800', 'p(95)<4000', 'p(99)<8000'],
    http_req_failed: ['rate<0.02'],
  },
};

// One stable lat/lng per VU when WARM=1 — picked at init time.
const WARM_LATLNG = randomLatLng();
// Pseudo-stable user id per VU for warm-cache hits.
function vuUserId() {
  return `loadtest-vu-${__VU}`;
}

export default function () {
  const { lat, lng } = WARM ? WARM_LATLNG : randomLatLng();
  const userId = WARM ? vuUserId() : `loadtest-${__VU}-${__ITER}`;

  const url =
    `${COMMON.SUPABASE_URL}/functions/v1/carousels` +
    `?lat=${lat.toFixed(4)}&lng=${lng.toFixed(4)}&user_id=${userId}`;

  const res = http.get(url, {
    headers: COMMON.anonHeaders(),
    tags: { name: 'carousels', warm: WARM ? 'yes' : 'no' },
    timeout: '20s',
  });

  check(res, {
    'carousels 200': (r) => r.status === 200,
    'has carousels array': (r) => {
      try {
        const j = r.json();
        return Array.isArray(j) || (j && Array.isArray(j.carousels));
      } catch (_) { return false; }
    },
  });

  sleep(0.5);
}
