// Thin PostgREST client (service key). Read-only here — the pipeline never
// writes to events/venues, it only reads the window of upcoming events.

import { supabaseEnv } from "./config.mjs";

export async function selectEvents(params) {
  const { url, key } = supabaseEnv();
  const qs = new URLSearchParams(params).toString();
  const res = await fetch(`${url}/rest/v1/events?${qs}`, {
    headers: { apikey: key, Authorization: `Bearer ${key}` },
  });
  if (!res.ok) {
    throw new Error(`PostgREST ${res.status}: ${await res.text()}`);
  }
  return res.json();
}

// Call a Postgres RPC (same read-only service key). Used for
// get_active_venues_lite, the app's own all-venues-with-rank source.
export async function callRpc(fn, body = {}) {
  const { url, key } = supabaseEnv();
  const res = await fetch(`${url}/rest/v1/rpc/${fn}`, {
    method: "POST",
    headers: { apikey: key, Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    throw new Error(`PostgREST rpc ${fn} ${res.status}: ${await res.text()}`);
  }
  return res.json();
}
