// Shared helpers for k6 scripts. Imported by every scenario.
//
// k6 runs scripts in a Goja JS sandbox — no Node modules, only its own
// `k6/*` imports + ES modules. Keep this file dependency-free.

const SUPABASE_URL = __ENV.SUPABASE_URL || 'https://blifubxslaucshmlphdc.supabase.co';
const ANON_KEY = __ENV.SUPABASE_ANON_KEY || '';
const SERVICE_KEY = __ENV.SUPABASE_SERVICE_KEY || '';
const ANTHROPIC_KEY = __ENV.ANTHROPIC_API_KEY || '';

export const COMMON = {
  SUPABASE_URL,
  ANON_KEY,
  SERVICE_KEY,
  ANTHROPIC_KEY,

  // Default headers for read-only PostgREST calls.
  anonHeaders: () => ({
    apikey: ANON_KEY,
    Authorization: `Bearer ${ANON_KEY}`,
    'Content-Type': 'application/json',
  }),

  // Headers for write calls — bypasses RLS. ONLY use against a dev project.
  serviceHeaders: () => ({
    apikey: SERVICE_KEY,
    Authorization: `Bearer ${SERVICE_KEY}`,
    'Content-Type': 'application/json',
    Prefer: 'return=representation',
  }),
};

// ──────────────── Geo + date generators ────────────────
// Realistic load distributes users across the metro instead of
// hammering a single lat/lng — that maximizes cache misses + tests
// the geo index under varied input.

const METRO_BBOX = {
  // Greater Boston bounding box (rough)
  boston: { latMin: 42.20, latMax: 42.55, lngMin: -71.40, lngMax: -70.95 },
  // Greater Providence
  providence: { latMin: 41.65, latMax: 41.95, lngMin: -71.55, lngMax: -71.30 },
};

export function randomLatLng(metro = 'boston') {
  const bbox = METRO_BBOX[metro] || METRO_BBOX.boston;
  const lat = bbox.latMin + Math.random() * (bbox.latMax - bbox.latMin);
  const lng = bbox.lngMin + Math.random() * (bbox.lngMax - bbox.lngMin);
  return { lat, lng };
}

// Random date within the next 30 days, returned as YYYY-MM-DD.
export function randomFutureDate(daysAhead = 30) {
  const d = new Date();
  d.setDate(d.getDate() + Math.floor(Math.random() * daysAhead));
  return d.toISOString().slice(0, 10);
}

// Pick one element of an array at random.
export function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

// Common categories users actually filter by.
export const CATEGORIES = [
  'music', 'sports', 'comedy', 'theatre', 'arts',
  'food', 'outdoors', 'for-kids', 'museums', 'tours',
];

// Common keywords / venue-name fragments seen in real Bo queries.
export const KEYWORDS = [
  'jazz', 'comedy', 'museum', 'lacrosse', 'free',
  'dance', 'family', 'brewery', 'wine', 'tour',
];

// ──────────────── Threshold presets ────────────────
// Most scenarios share these. Override per-script if expectations differ
// (Bo full-turn has 30s+ p95, edge functions have 1.5s, etc.).

export const THRESHOLDS = {
  fast: {
    http_req_duration: ['p(50)<300', 'p(95)<1500', 'p(99)<3000'],
    http_req_failed: ['rate<0.01'],
  },
  medium: {
    http_req_duration: ['p(50)<800', 'p(95)<3000', 'p(99)<6000'],
    http_req_failed: ['rate<0.02'],
  },
  // For Bo full-turn — Claude streaming is naturally slow.
  slow: {
    http_req_duration: ['p(50)<5000', 'p(95)<20000', 'p(99)<45000'],
    http_req_failed: ['rate<0.05'],
  },
};

// ──────────────── Standard ramp profiles ────────────────

// Sane "find the cliff" profile for read endpoints. Adjust per-script.
export const RAMP_READ = [
  { duration: '30s', target: 5 },
  { duration: '1m',  target: 5 },
  { duration: '30s', target: 25 },
  { duration: '1m',  target: 25 },
  { duration: '30s', target: 100 },
  { duration: '1m',  target: 100 },
  { duration: '30s', target: 250 },
  { duration: '1m',  target: 250 },
  { duration: '30s', target: 0 },
];

// Conservative profile for writes / external-cost scenarios.
export const RAMP_WRITE = [
  { duration: '30s', target: 2 },
  { duration: '1m',  target: 5 },
  { duration: '30s', target: 10 },
  { duration: '1m',  target: 10 },
  { duration: '30s', target: 0 },
];

// Tight cap for Anthropic / Twilio scenarios.
export const RAMP_EXTERNAL = [
  { duration: '15s', target: 2 },
  { duration: '1m',  target: 5 },
  { duration: '30s', target: 10 },
  { duration: '15s', target: 0 },
];
