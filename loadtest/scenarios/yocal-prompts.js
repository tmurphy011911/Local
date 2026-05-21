// Load test for the yocal-prompts edge function.
//
// Bo blocks on this every chat session (fetches the location-aware
// suggestion prompts). Slow first response = users see Bo as sluggish
// before they've even typed.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { COMMON, randomLatLng, THRESHOLDS } from '../lib/common.js';

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
  thresholds: THRESHOLDS.fast,
};

export default function () {
  const { lat, lng } = randomLatLng();
  const url = `${COMMON.SUPABASE_URL}/functions/v1/yocal-prompts?lat=${lat}&lng=${lng}`;

  const res = http.get(url, {
    headers: COMMON.anonHeaders(),
    tags: { name: 'yocal-prompts' },
    timeout: '5s',
  });

  check(res, {
    'prompts 200': (r) => r.status === 200,
    'has prompts': (r) => {
      try {
        const j = r.json();
        return Array.isArray(j) || (j && (Array.isArray(j.prompts) || j.prompts));
      } catch (_) { return false; }
    },
  });

  sleep(0.3);
}
