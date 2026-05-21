// Load test for the auth flow (phone OTP send + verify).
//
// Path: signInWithOtp (Twilio Verify send) → verifyOtp (consume code) →
// session token. This script exercises the gateway side; if you want to
// exclude Twilio entirely and just measure Supabase Auth's HTTP layer,
// use TWILIO_TEST_PHONE=+15005550006 (Twilio's "always succeeds" Magic
// number) — those don't actually send an SMS or charge.
//
// SAFETY:
//   - Real phone numbers will charge ~$0.05 per OTP send.
//   - Twilio Magic numbers (+15005550006) are free but only valid in
//     Twilio test mode — your Supabase project must be configured with
//     test creds for this to succeed.
//
// We test send + verify separately so you can see which side is slower.
// The verify step uses code "000000" which Supabase + Twilio test mode
// accepts as the canonical valid code for Magic numbers.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { COMMON, RAMP_EXTERNAL } from '../lib/common.js';

const PHONE = __ENV.TWILIO_TEST_PHONE || '+15005550006';
const SKIP_VERIFY = __ENV.SKIP_VERIFY === '1';

if (!COMMON.ANON_KEY) throw new Error('SUPABASE_ANON_KEY required.');

export const options = {
  stages: RAMP_EXTERNAL,
  thresholds: {
    http_req_duration: ['p(50)<1500', 'p(95)<5000', 'p(99)<10000'],
    http_req_failed: ['rate<0.10'],
    'http_req_duration{op:send}':   ['p(95)<5000'],
    'http_req_duration{op:verify}': ['p(95)<3000'],
  },
};

const SEND_URL   = `${COMMON.SUPABASE_URL}/auth/v1/otp`;
const VERIFY_URL = `${COMMON.SUPABASE_URL}/auth/v1/verify`;

export default function () {
  // 1. Request OTP (Twilio send)
  const sendBody = JSON.stringify({
    phone: PHONE,
    create_user: true,
  });

  const sendRes = http.post(SEND_URL, sendBody, {
    headers: COMMON.anonHeaders(),
    tags: { name: 'auth-otp', op: 'send' },
    timeout: '15s',
  });

  check(sendRes, {
    'send 200': (r) => r.status === 200,
  });

  if (SKIP_VERIFY || sendRes.status !== 200) {
    sleep(1);
    return;
  }

  // 2. Verify with the canonical Twilio test code.
  const verifyBody = JSON.stringify({
    type: 'sms',
    phone: PHONE,
    token: '000000',
  });

  const verifyRes = http.post(VERIFY_URL, verifyBody, {
    headers: COMMON.anonHeaders(),
    tags: { name: 'auth-otp', op: 'verify' },
    timeout: '10s',
  });

  check(verifyRes, {
    'verify 200': (r) => r.status === 200,
    'has access_token': (r) => {
      try { return !!r.json().access_token; } catch (_) { return false; }
    },
  });

  // Cool down — auth flows are typically rate-limited per IP.
  sleep(2);
}
