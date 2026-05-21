// Load test for the Plans CRUD path (savePlan + getPlans + deletePlan).
//
// Each VU iteration: insert a multi-day plan, fetch user's plans, delete
// the plan. Tags rows source='loadtest' for cleanup.
//
// SAFETY: requires SUPABASE_SERVICE_KEY + WRITE=1. Refuses to run
// against the prod URL unless FORCE_PROD=1 is also set (don't).

import http from 'k6/http';
import { check, sleep } from 'k6';
import { COMMON, randomFutureDate, THRESHOLDS, RAMP_WRITE } from '../lib/common.js';

const WRITE = __ENV.WRITE === '1';
const FORCE_PROD = __ENV.FORCE_PROD === '1';
const TEST_USER_ID = __ENV.TEST_USER_ID || '';

if (!WRITE) {
  throw new Error('plans-crud is a WRITE scenario. Set WRITE=1 to enable.');
}
if (!COMMON.SERVICE_KEY) {
  throw new Error('SUPABASE_SERVICE_KEY required for plans-crud.');
}
if (!TEST_USER_ID) {
  throw new Error('TEST_USER_ID required (auth.users UUID for a test user).');
}
if (COMMON.SUPABASE_URL.includes('blifubxslaucshmlphdc') && !FORCE_PROD) {
  throw new Error('Refusing to run against prod. Set SUPABASE_URL to a dev project (or FORCE_PROD=1, which you should not).');
}

export const options = {
  stages: RAMP_WRITE,
  thresholds: {
    ...THRESHOLDS.medium,
    'http_req_duration{op:save}':   ['p(95)<2500'],
    'http_req_duration{op:list}':   ['p(95)<1500'],
    'http_req_duration{op:delete}': ['p(95)<1000'],
  },
};

const PLANS_URL = `${COMMON.SUPABASE_URL}/rest/v1/plans`;

function buildPlanBody(iter) {
  const date = randomFutureDate(60);
  const days = [
    { label: 'Day 1', short_name: 'Day 1', slots: [], travel: [] },
    { label: 'Day 2', short_name: 'Day 2', slots: [], travel: [] },
  ];
  return JSON.stringify({
    user_id: TEST_USER_ID,
    name: `Loadtest plan vu=${__VU} iter=${iter}`,
    date,
    city: 'Boston',
    days,
    source: 'loadtest',
  });
}

export default function () {
  // 1. Insert
  const insertRes = http.post(PLANS_URL, buildPlanBody(__ITER), {
    headers: COMMON.serviceHeaders(),
    tags: { name: 'plans', op: 'save' },
    timeout: '10s',
  });
  const okSave = check(insertRes, { 'save 201': (r) => r.status === 201 });
  let createdId = null;
  if (okSave) {
    try { createdId = insertRes.json()[0]?.id; } catch (_) {}
  }

  // 2. List user's plans (mirrors PlansViewModel.loadSavedItems)
  const listUrl = `${PLANS_URL}?user_id=eq.${TEST_USER_ID}&select=id,name,date,days&order=created_at.desc&limit=20`;
  const listRes = http.get(listUrl, {
    headers: COMMON.serviceHeaders(),
    tags: { name: 'plans', op: 'list' },
    timeout: '5s',
  });
  check(listRes, { 'list 200': (r) => r.status === 200 });

  // 3. Delete what we created (best-effort; orphans cleanable via
  //    `delete from plans where source='loadtest'`)
  if (createdId) {
    const delUrl = `${PLANS_URL}?id=eq.${createdId}`;
    const delRes = http.del(delUrl, null, {
      headers: COMMON.serviceHeaders(),
      tags: { name: 'plans', op: 'delete' },
      timeout: '5s',
    });
    check(delRes, { 'delete 204': (r) => r.status === 204 });
  }

  sleep(0.5);
}

export function teardown() {
  // Sweep any orphans this run left behind.
  const url = `${PLANS_URL}?source=eq.loadtest`;
  http.del(url, null, { headers: COMMON.serviceHeaders() });
}
