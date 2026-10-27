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
