# Load tests

[k6](https://k6.io/) load tests for the Local app's backend. Each scenario
in `scenarios/` is a standalone k6 script — run one, run many, mix and match.

## Install k6

```sh
brew install k6
```

## Configure

Copy `.env.example` → `.env` and fill in the keys you have.

| Variable | Required for | Notes |
|---|---|---|
| `SUPABASE_URL` | everything | already in repo as a constant; default works for prod |
| `SUPABASE_ANON_KEY` | reads, edge funcs | publishable; in `Config.kt` |
| `SUPABASE_SERVICE_KEY` | writes (`plans-crud`, `interactions`) | sensitive; **never run write scenarios against prod without confirmation** |
| `ANTHROPIC_API_KEY` | `bo-full-turn` only | each VU iteration spends ~$0.10–0.20 |
| `TWILIO_TEST_PHONE` | `auth-otp` only | use a Twilio Magic test number — real OTP sends cost ~$0.05 each |

Load env vars with `source .env` before running, or pass `--env`
flags inline (k6 reads both).

## Run a scenario

```sh
# Single scenario at default ramp (5 → 250 VUs over ~10 min)
k6 run scenarios/rpc-search-stratified.js

# Tighter ramp + custom max VUs
k6 run --vus 50 --duration 2m scenarios/carousels.js

# Output JSON summary for later analysis
k6 run --summary-export results/rpc-$(date +%s).json scenarios/rpc-search-stratified.js
```

## Scenarios

### Reads (safe to run against prod, low risk)

| Scenario | What it stresses | Default ramp |
|---|---|---|
| `rpc-search-stratified.js` | Three-lane stratified search RPC | 5 → 250 VU |
| `carousels.js` | Home-feed edge function with cache-busting | 5 → 100 VU |
| `yocal-prompts.js` | Bo startup prompt fetch | 5 → 200 VU |
| `bo-web-gate.js` | Bo's per-turn web-search permission gate | 5 → 200 VU |
| `event-detail.js` | Post-tap event detail page load | 5 → 250 VU |
| `venue-search.js` | Venue lookup queries | 5 → 100 VU |

### Writes (medium risk — flag `WRITE=1`, run against dev project)

| Scenario | Writes to | Notes |
|---|---|---|
| `plans-crud.js` | `plans` table | creates+reads+deletes test plans |
| `interactions.js` | `community_interactions` | append-only, low contention; closest to prod hot path |
| `validate-venue.js` | `venues` (insert if missing) + Google Places API | costs Google Places calls (~$0.017 per) |

### External-cost (run sparingly)

| Scenario | Cost driver | Default cap |
|---|---|---|
| `bo-full-turn.js` | Anthropic Claude API ($0.10–0.20/iter) | 10 VU max |
| `auth-otp.js` | Twilio Verify ($0.05/send) | 5 VU max — uses Magic test numbers |

### Composite

| Scenario | What it does |
|---|---|
| `mixed-realistic.js` | Weighted mix simulating typical app behavior: 60% reads (carousels + RPC + event detail), 30% interaction writes, 10% Bo turns. Use this to estimate real-world capacity. |

## Reading the output

k6 reports for each scenario:

- **`http_req_duration`** — total time per request (p50/p95/p99 are the ones to watch)
- **`http_req_failed`** — error rate; should be near 0%
- **Custom thresholds** — each script sets its own (typically p95 < 2s, p99 < 5s)

Watch for:

- **p95 > 5s** on the RPC = approaching Postgres statement timeout (8s)
- **`http_req_failed` > 1%** = upstream is rejecting; check Supabase logs
- **Throughput plateau** = bottleneck found (CPU, connection pool, rate limit)

## Safety

- **Don't run write scenarios against prod** — the project ref is in
  `lib/common.js`. Override with `SUPABASE_URL=https://your-dev-ref.supabase.co`
  to point at a dev/staging project.
- **Bo full-turn defaults to dry-run mode** (`DRY_RUN=1`) — only sends a
  `system` ping + tool list to estimate input-token cost without firing a
  real generation. Pass `DRY_RUN=0` to spend real tokens.
- **Auth-OTP uses Twilio's Magic test numbers** when `TWILIO_TEST_PHONE`
  is set to one of `+15005550006` family. Real numbers will charge real
  Twilio fees.
- **Cleanup**: write scenarios delete their own rows on iteration end
  (best-effort; orphans are tagged `source='loadtest'` so you can sweep
  with `delete from <table> where source='loadtest'`).

## Adding a new scenario

Copy any read scenario, swap the URL/payload, set thresholds at the
bottom. The k6 script structure is:

```js
import { check } from 'k6';
import http from 'k6/http';
import { COMMON } from '../lib/common.js';

export const options = {
  stages: [/* ramp */],
  thresholds: { http_req_duration: ['p(95)<2000'] },
};

export default function () {
  const r = http.get(...);
  check(r, { 'status 200': (x) => x.status === 200 });
}
```
