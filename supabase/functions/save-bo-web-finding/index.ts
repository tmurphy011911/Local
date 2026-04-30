import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

// ════════════════════════════════════════════════════════════════════════════
// save-bo-web-finding: Bo's chat client calls this after web_search confirms
// an event or venue worth referencing in a plan. We persist into
// bo_web_events / bo_web_venues with state='unverified' and return the new
// row's id, which Bo then puts in present_plan slot's event_id field.
//
// All writes go through service_role here so we don't need to expose RLS
// quirks to the client; the server stamps created_by_user_id and source
// fields canonically.
//
// Endpoint: POST / { user_id, kind: "event"|"venue", payload: {...} }
// Returns:  { id: "<uuid>" }
// ════════════════════════════════════════════════════════════════════════════

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "authorization, content-type",
};

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
  if (req.method !== "POST") return json({ error: "POST only" }, 405);

  const body = await req.json().catch(() => null);
  if (!body || typeof body !== "object") return json({ error: "bad body" }, 400);

  const { user_id, kind, payload } = body;
  if (!user_id || typeof user_id !== "string") return json({ error: "missing user_id" }, 400);
  if (kind !== "event" && kind !== "venue") return json({ error: "kind must be event|venue" }, 400);
  if (!payload || typeof payload !== "object") return json({ error: "missing payload" }, 400);

  const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY);

  if (kind === "event") {
    return saveEvent(supabase, user_id, payload);
  } else {
    return saveVenue(supabase, user_id, payload);
  }
});

async function saveEvent(supabase: any, userId: string, p: any): Promise<Response> {
  if (!p.title || typeof p.title !== "string") return json({ error: "title required" }, 400);
  if (!p.start_at) return json({ error: "start_at required" }, 400);

  // Strict future check at insert time — even if Bo accidentally pulls a past
  // article, we never persist a past event.
  const startDate = new Date(p.start_at);
  if (isNaN(startDate.getTime()) || startDate.getTime() < Date.now() - 60_000) {
    return json({ error: "start_at must be in the future" }, 400);
  }

  // Enrich with Google Places synchronously: fetches the canonical
  // venue_address, venue_lat, venue_lng, image_url so the slot has data
  // for the map pin + thumbnail immediately. The verifier ALSO runs
  // enrichment as a safety net (and rejects on city mismatch).
  const placesKey = await getSecret(supabase, "GOOGLE_PLACES_API_KEY");
  const enriched = placesKey && p.venue_name
    ? await enrichEvent(p, placesKey)
    : {};

  const row: any = {
    state: "unverified",
    title: String(p.title).slice(0, 500),
    description: p.description ? String(p.description).slice(0, 5000) : null,
    start_at: startDate.toISOString(),
    end_at: p.end_at ? new Date(p.end_at).toISOString() : null,
    is_recurring: !!p.is_recurring,
    recurrence_rule: p.recurrence_rule || null,
    category: p.category || null,
    ticket_url: p.ticket_url || null,
    source_url: p.source_url || null,
    image_url: p.image_url || enriched.image_url || null,
    is_free: typeof p.is_free === "boolean" ? p.is_free : null,
    price_min: numOrNull(p.price_min),
    price_max: numOrNull(p.price_max),
    venue_id: isUuid(p.venue_id) ? p.venue_id : null,
    bo_web_venue_id: isUuid(p.bo_web_venue_id) ? p.bo_web_venue_id : null,
    venue_name: p.venue_name || null,
    venue_address: p.venue_address || enriched.venue_address || null,
    venue_city: p.venue_city || null,
    venue_state: p.venue_state ? String(p.venue_state).slice(0, 2) : null,
    venue_lat: numOrNull(p.venue_lat) ?? enriched.venue_lat ?? null,
    venue_lng: numOrNull(p.venue_lng) ?? enriched.venue_lng ?? null,
    created_by_user_id: userId,
    created_via: p.created_via === "bo_web_fetch" ? "bo_web_fetch" : "bo_web_search",
    source_domains: Array.isArray(p.source_domains) ? p.source_domains.slice(0, 10) : [],
  };

  const { data, error } = await supabase
    .from("bo_web_events")
    .insert(row)
    .select("id")
    .single();

  if (error) return json({ error: error.message }, 500);
  // Phase 3: fire-and-forget the verifier. The user gets the id back
  // immediately; verification runs async in ~5-30s. Slot UI shows
  // "verifying" until state flips to verified/rejected.
  fireVerifier(data.id, "event").catch((e) => console.log(`verifier trigger failed: ${e?.message}`));
  // Return the enriched fields too so the client cache (Bo's plan slot
  // rendering) can use them for the map pin + thumbnail without waiting
  // for the verifier or a plan reload.
  return json({
    id: data.id,
    venue_address: row.venue_address,
    venue_lat: row.venue_lat,
    venue_lng: row.venue_lng,
    image_url: row.image_url,
  });
}

async function saveVenue(supabase: any, userId: string, p: any): Promise<Response> {
  if (!p.name || typeof p.name !== "string") return json({ error: "name required" }, 400);

  // Same Google Places enrichment as saveEvent — populates address,
  // lat/lng, image at insert time so the row is useful immediately.
  const placesKey = await getSecret(supabase, "GOOGLE_PLACES_API_KEY");
  const enriched = placesKey
    ? await enrichVenue(p, placesKey)
    : {};

  const row: any = {
    state: "unverified",
    name: String(p.name).slice(0, 500),
    address: p.address || enriched.address || null,
    city: p.city || null,
    state_code: p.state_code ? String(p.state_code).slice(0, 2) : null,
    lat: numOrNull(p.lat) ?? enriched.lat ?? null,
    lng: numOrNull(p.lng) ?? enriched.lng ?? null,
    category: p.category || null,
    description: p.description ? String(p.description).slice(0, 5000) : null,
    website: p.website || null,
    phone: p.phone || null,
    source_url: p.source_url || null,
    image_url: p.image_url || enriched.image_url || null,
    created_by_user_id: userId,
    source_domains: Array.isArray(p.source_domains) ? p.source_domains.slice(0, 10) : [],
  };

  const { data, error } = await supabase
    .from("bo_web_venues")
    .insert(row)
    .select("id")
    .single();

  if (error) return json({ error: error.message }, 500);
  fireVerifier(data.id, "venue").catch((e) => console.log(`verifier trigger failed: ${e?.message}`));
  return json({ id: data.id });
}

// ── Google Places enrichment ──────────────────────────────────────────────
// Used at save-time to fill in venue_address, venue_lat/lng, image_url
// from the Google Places Text Search API. Synchronous so the slot Bo just
// built has full data for the map pin + thumbnail without waiting on the
// verifier. The verifier still runs and re-validates city match.

async function enrichEvent(p: any, apiKey: string): Promise<{ venue_address?: string; venue_lat?: number; venue_lng?: number; image_url?: string }> {
  const result = await placesSearch(`${p.venue_name} ${p.venue_city ?? ""}`.trim(), apiKey);
  if (!result) return {};
  return {
    venue_address: result.address,
    venue_lat: result.lat,
    venue_lng: result.lng,
    image_url: result.photoUrl,
  };
}

async function enrichVenue(p: any, apiKey: string): Promise<{ address?: string; lat?: number; lng?: number; image_url?: string }> {
  const result = await placesSearch(`${p.name} ${p.city ?? ""}`.trim(), apiKey);
  if (!result) return {};
  return {
    address: result.address,
    lat: result.lat,
    lng: result.lng,
    image_url: result.photoUrl,
  };
}

async function placesSearch(query: string, apiKey: string): Promise<{ address: string | null; lat: number | null; lng: number | null; photoUrl: string | null } | null> {
  if (!query) return null;
  try {
    const res = await fetch("https://places.googleapis.com/v1/places:searchText", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Goog-Api-Key": apiKey,
        "X-Goog-FieldMask": "places.id,places.formattedAddress,places.location,places.photos",
      },
      body: JSON.stringify({ textQuery: query, maxResultCount: 1 }),
    });
    if (!res.ok) return null;
    const data = await res.json();
    const place = data.places?.[0];
    if (!place) return null;
    const photoName = place.photos?.[0]?.name;
    return {
      address: place.formattedAddress ?? null,
      lat: place.location?.latitude ?? null,
      lng: place.location?.longitude ?? null,
      photoUrl: photoName ? `https://places.googleapis.com/v1/${photoName}/media?maxHeightPx=600&maxWidthPx=800&key=${apiKey}` : null,
    };
  } catch {
    return null;
  }
}

async function getSecret(supabase: any, key: string): Promise<string | null> {
  const { data } = await supabase.from("secrets").select("value").eq("key", key).maybeSingle();
  return data?.value ?? null;
}

/**
 * Fire-and-forget the verifier edge function. We don't await — the user
 * gets the new row id immediately while verification runs in the
 * background. Service-role auth means no extra header dance.
 */
async function fireVerifier(id: string, kind: "event" | "venue"): Promise<void> {
  const url = `${SUPABASE_URL}/functions/v1/verify-bo-web-event`;
  await fetch(url, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Authorization": `Bearer ${SUPABASE_SERVICE_ROLE_KEY}`,
    },
    body: JSON.stringify({ id, kind }),
  });
}

function numOrNull(v: any): number | null {
  if (v == null) return null;
  const n = Number(v);
  return isFinite(n) ? n : null;
}

function isUuid(s: any): boolean {
  return typeof s === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(s);
}

function json(body: any, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...CORS },
  });
}
