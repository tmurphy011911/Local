/// <reference types="https://esm.sh/@supabase/functions-js/src/edge-runtime.d.ts" />

// RevenueCat webhook → user_profiles entitlement sync.
//
// RevenueCat POSTs subscription lifecycle events here. We map them onto the
// is_pro / pro_* columns, keyed by app_user_id (which equals the Supabase auth
// uid because the client calls Purchases.logIn(authUid)).
//
// Security:
//   * Gated by a shared secret in the Authorization header (set both here as
//     the RC_WEBHOOK_SECRET function secret AND in the RevenueCat dashboard
//     webhook config). Any mismatch → 401, no DB touch.
//   * is_pro is derived from expiry vs now, NOT the event type — so a
//     CANCELLATION keeps the user pro until the period actually ends.
//   * Anonymous ids ($RCAnonymousID:...) can't map to a user → logged + 200.
//   * Idempotent: only writes when the event is newer than pro_updated_at.

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const RC_WEBHOOK_SECRET = Deno.env.get("RC_WEBHOOK_SECRET")!;

interface RcEvent {
  type?: string;
  app_user_id?: string;
  aliases?: string[];
  product_id?: string;
  store?: string;
  expiration_at_ms?: number | null;
  event_timestamp_ms?: number;
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

Deno.serve(async (req) => {
  if (req.method !== "POST") {
    return json(405, { ok: false, error: "method not allowed" });
  }

  // Constant-ish secret check. RevenueCat sends whatever Authorization value
  // you configure; we require an exact match before doing anything else.
  if (req.headers.get("Authorization") !== RC_WEBHOOK_SECRET) {
    return json(401, { ok: false, error: "unauthorized" });
  }

  let event: RcEvent;
  try {
    const payload = await req.json();
    event = payload?.event ?? {};
  } catch (_e) {
    return json(400, { ok: false, error: "invalid json" });
  }

  const type = event.type ?? "UNKNOWN";

  // RevenueCat's connectivity test event — acknowledge without touching the DB.
  if (type === "TEST") {
    return json(200, { ok: true, ignored: "test event" });
  }

  const appUserId = event.app_user_id ?? "";
  if (!appUserId || appUserId.startsWith("$RCAnonymousID:")) {
    console.log(`rc-webhook: anonymous/empty app_user_id (type=${type}) — ignoring`);
    return json(200, { ok: true, ignored: "anonymous id" });
  }

  // Derive pro state from the actual expiry, not the event type.
  const expMs = event.expiration_at_ms ?? null;
  const isPro = expMs != null && expMs > Date.now();
  const proExpiresAt = expMs != null ? new Date(expMs).toISOString() : null;
  const eventTs = event.event_timestamp_ms ?? Date.now();
  const eventTsIso = new Date(eventTs).toISOString();

  const client = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY);

  // Idempotency / out-of-order guard: skip if we've already applied an event
  // at or after this one's timestamp.
  const { data: existing, error: readErr } = await client
    .from("user_profiles")
    .select("id, pro_updated_at")
    .eq("id", appUserId)
    .maybeSingle();

  if (readErr) {
    console.error("rc-webhook: read error:", readErr);
    return json(500, { ok: false, error: readErr.message });
  }
  if (!existing) {
    // No profile row for this uid (e.g. event arrived before onboarding wrote
    // the row, or stale/aliased id). 200 so RevenueCat doesn't retry forever.
    console.log(`rc-webhook: no user_profiles row for ${appUserId} (type=${type})`);
    return json(200, { ok: true, ignored: "no profile row" });
  }
  if (existing.pro_updated_at && new Date(existing.pro_updated_at).getTime() >= eventTs) {
    console.log(`rc-webhook: stale event for ${appUserId} (type=${type}) — ignoring`);
    return json(200, { ok: true, ignored: "stale event" });
  }

  const { error: updateErr } = await client
    .from("user_profiles")
    .update({
      is_pro: isPro,
      pro_expires_at: proExpiresAt,
      pro_product_id: event.product_id ?? null,
      pro_store: event.store ?? null,
      pro_updated_at: eventTsIso,
    })
    .eq("id", appUserId);

  if (updateErr) {
    console.error("rc-webhook: update error:", updateErr);
    return json(500, { ok: false, error: updateErr.message });
  }

  console.log(`rc-webhook: ${appUserId} type=${type} is_pro=${isPro} expires=${proExpiresAt}`);
  return json(200, { ok: true });
});
