// Load test for a full Bo turn (Claude API + Supabase chain).
//
// This is the most expensive scenario by far — each iteration spends
// real Claude tokens. To make it useful for capacity planning without
// blowing your Anthropic budget:
//
//   - DRY_RUN=1 (default) — sends a minimal prompt + tool definitions
//     to estimate the Anthropic-side rate-limit ceiling without paying
//     for a full streaming generation.
//   - DRY_RUN=0 — sends a real "what's happening tonight" message; Bo
//     runs its full tool loop. Caps at 10 VU. Cost ~$0.10–0.20/iter.
//
// We're testing two ceilings:
//   1. Anthropic API rate limit (RPM/TPM for the org)
//   2. Supabase RPC + edge fn chain inside Bo's tool loop

import http from 'k6/http';
import { check, sleep } from 'k6';
import { COMMON, RAMP_EXTERNAL } from '../lib/common.js';

const DRY_RUN = __ENV.DRY_RUN !== '0'; // defaults to dry-run

if (!COMMON.ANTHROPIC_KEY) {
  throw new Error('ANTHROPIC_API_KEY required for bo-full-turn.');
}

export const options = {
  stages: RAMP_EXTERNAL,
  thresholds: {
    http_req_duration: DRY_RUN
      ? ['p(50)<1000', 'p(95)<3000', 'p(99)<8000']
      : ['p(50)<8000', 'p(95)<25000', 'p(99)<60000'],
    http_req_failed: ['rate<0.10'],
  },
};

const ANTHROPIC_URL = 'https://api.anthropic.com/v1/messages';

const MINIMAL_PROMPT = 'You are a load-test stub. Respond only with "ok".';
const FULL_PROMPT =
  'You are Bo, a local-events concierge. Answer briefly using your tools.';
const FULL_USER = "What's happening in Boston tonight?";

// Minimal tool definition matching the app's search_events shape.
const TOOLS = [
  {
    name: 'search_events',
    description: 'Search for upcoming events',
    input_schema: {
      type: 'object',
      properties: {
        location: { type: 'string' },
        date_range: { type: 'string' },
      },
    },
  },
];

export default function () {
  const body = JSON.stringify({
    model: DRY_RUN ? 'claude-haiku-4-5-20251001' : 'claude-sonnet-4-6',
    max_tokens: DRY_RUN ? 50 : 1024,
    system: DRY_RUN ? MINIMAL_PROMPT : FULL_PROMPT,
    tools: DRY_RUN ? undefined : TOOLS,
    messages: [
      { role: 'user', content: DRY_RUN ? 'ok' : FULL_USER },
    ],
  });

  const res = http.post(ANTHROPIC_URL, body, {
    headers: {
      'x-api-key': COMMON.ANTHROPIC_KEY,
      'anthropic-version': '2023-06-01',
      'Content-Type': 'application/json',
    },
    tags: { name: 'bo-turn', mode: DRY_RUN ? 'dry' : 'full' },
    timeout: '90s',
  });

  check(res, {
    'turn 200': (r) => r.status === 200,
    'no rate limit': (r) => r.status !== 429,
    'has content': (r) => {
      try {
        const j = r.json();
        return j && Array.isArray(j.content);
      } catch (_) { return false; }
    },
  });

  sleep(DRY_RUN ? 0.5 : 2);
}
