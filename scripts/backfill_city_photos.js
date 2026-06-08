#!/usr/bin/env node
/**
 * backfill_city_photos.js — city/town fallback photos, re-hosted to Storage.
 *
 * Used as a last-resort image for events that don't resolve to a venue
 * (municipal at-large events: venue_name IS NULL). The carousels edge
 * function (cleanEvents) falls back to city_photos[venue_city] when an
 * event has neither image_url nor a venue photo.
 *
 * WHY re-host (was: stored raw Google URLs): Google Places API v1 photo
 * `/media?key=...` URLs (a) ROT — 404/400 within weeks (every one of the
 * old 160 rows was dead), and (b) embed the Places API key, which would
 * leak to every client once served. We download the bytes once and upload
 * to the public `city-photos` bucket at `{slug}.jpg`; Storage URLs are
 * permanent and key-free. Mirrors migrate_venue_photos_to_storage.js.
 *
 * Source per city: Google Places "downtown aerial view" landmark photo,
 * falling back to the top-ranked venue photo already in that city (which is
 * itself a Storage URL post venue-photo migration).
 *
 * Idempotent: a city whose city_photos.photo_url is already a `city-photos`
 * Storage URL is skipped, so the weekly cron doesn't re-download every city.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/backfill_city_photos.js                  # dry run, all cities
 *   SUPABASE_SERVICE_KEY=ey... WRITE=1 node scripts/backfill_city_photos.js           # apply
 *   SUPABASE_SERVICE_KEY=ey... WRITE=1 LIMIT=20 node scripts/backfill_city_photos.js  # cap
 *   SUPABASE_SERVICE_KEY=ey... WRITE=1 CITY="Waltham" node scripts/backfill_city_photos.js # one city
 *   SUPABASE_SERVICE_KEY=ey... WRITE=1 FORCE=1 node scripts/backfill_city_photos.js   # re-host even if already storage-hosted
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

const SERVICE_KEY = (process.env.SUPABASE_SERVICE_KEY || "").replace(/\s/g, "");
const WRITE = process.env.WRITE === "1";
const FORCE = process.env.FORCE === "1";
const LIMIT = parseInt(process.env.LIMIT || "0", 10) || null;
const ONE_CITY = process.env.CITY || null;
const CONCURRENCY = parseInt(process.env.CONCURRENCY || "4", 10);

if (!SERVICE_KEY) { console.error("Missing SUPABASE_SERVICE_KEY"); process.exit(1); }

let GOOGLE_PLACES_API_KEY;

const restHeaders = {
  apikey: SERVICE_KEY,
  Authorization: `Bearer ${SERVICE_KEY}`,
  "Content-Type": "application/json",
};

const STORAGE_BUCKET = "city-photos";
const STORAGE_PUBLIC_BASE = `${SUPABASE_URL}/storage/v1/object/public/${STORAGE_BUCKET}`;

// ── Slug for the storage object key ─────────────────────────────────────
// Strip parenthetical suffixes ("Cambridge (visitor)"), lowercase, collapse
// non-alphanumerics to single hyphens. Keys must be stable per city name.
function citySlug(city, state) {
  const clean = (city || "")
    .replace(/\s*\([^)]*\)\s*/g, " ")
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
  return `${clean}${state ? `-${String(state).toLowerCase()}` : ""}`;
}

// ── Determine the set of cities to cover ────────────────────────────────
// Source of truth = distinct venue_city among active future events, paired
// with the modal venue_state for that city. This is the exact set of cities
// that can appear on a card, so it's what the fallback needs to cover.

async function fetchCities() {
  if (ONE_CITY) {
    // Look up the state for the single city from events.
    const r = await fetch(
      `${SUPABASE_URL}/rest/v1/events?select=venue_state&venue_city=eq.${encodeURIComponent(ONE_CITY)}&is_active=eq.true&limit=1`,
      { headers: restHeaders },
    );
    const rows = r.ok ? await r.json() : [];
    return [{ city: ONE_CITY, state: rows[0]?.venue_state || null }];
  }
  const today = new Date().toISOString().slice(0, 10);
  const counts = new Map(); // city -> Map(state -> n)
  let offset = 0;
  while (true) {
    const url = `${SUPABASE_URL}/rest/v1/events?select=venue_city,venue_state&is_active=eq.true&start_at=gte.${today}&offset=${offset}&limit=1000`;
    const r = await fetch(url, { headers: restHeaders });
    if (!r.ok) throw new Error(`fetch events: ${r.status} ${await r.text()}`);
    const rows = await r.json();
    for (const e of rows) {
      const city = (e.venue_city || "").trim();
      if (!city) continue;
      if (!counts.has(city)) counts.set(city, new Map());
      const sm = counts.get(city);
      const st = e.venue_state || "";
      sm.set(st, (sm.get(st) || 0) + 1);
    }
    process.stdout.write(`  scanned ${offset + rows.length} events\r`);
    if (rows.length < 1000) break;
    offset += 1000;
  }
  console.log("");
  // Pick the modal (most common) state per city.
  const cities = [];
  for (const [city, sm] of counts) {
    let bestState = null, bestN = -1;
    for (const [st, n] of sm) {
      if (n > bestN) { bestN = n; bestState = st || null; }
    }
    cities.push({ city, state: bestState });
  }
  cities.sort((a, b) => a.city.localeCompare(b.city));
  return cities;
}

// ── Existing city_photos rows (for idempotency) ─────────────────────────

async function fetchExistingPhotos() {
  const r = await fetch(`${SUPABASE_URL}/rest/v1/city_photos?select=city,photo_url`, { headers: restHeaders });
  if (!r.ok) throw new Error(`fetch city_photos: ${r.status}`);
  const map = new Map();
  for (const row of await r.json()) map.set(row.city, row.photo_url || "");
  return map;
}

function isStorageHosted(url) {
  return !!url && url.startsWith(STORAGE_PUBLIC_BASE);
}

// ── Top venue photo per city (Storage-hosted fallback source) ───────────

async function fetchTopVenuePhotos() {
  const r = await fetch(
    `${SUPABASE_URL}/rest/v1/venues?select=city,photo_url,public_interest_rank&photo_url=not.is.null&is_active=eq.true&order=public_interest_rank.desc.nullslast&limit=10000`,
    { headers: restHeaders },
  );
  const map = {};
  if (r.ok) {
    for (const v of await r.json()) {
      if (v.city && v.photo_url && !map[v.city]) map[v.city] = v.photo_url;
    }
  }
  return map;
}

// ── Google Places landmark photo lookup ─────────────────────────────────

async function fetchCityPhotoUrl(city, state) {
  // Query the LOCALITY itself ("Waltham, Massachusetts"), not a phrase like
  // "<city> downtown aerial view" — the latter matches orgs/BIDs that often
  // have no photos (the old query's failure mode: small towns got nothing).
  // Scan the top few results for the first that actually has a photo.
  const query = `${city}${state ? `, ${state}` : ""}`;
  const res = await fetch("https://places.googleapis.com/v1/places:searchText", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Goog-Api-Key": GOOGLE_PLACES_API_KEY,
      "X-Goog-FieldMask": "places.photos",
    },
    body: JSON.stringify({ textQuery: query, maxResultCount: 3 }),
  });
  if (!res.ok) return null;
  const data = await res.json();
  const photoRef = (data.places || [])
    .map((p) => p.photos?.[0]?.name)
    .find(Boolean);
  if (!photoRef) return null;
  return `https://places.googleapis.com/v1/${photoRef}/media?maxWidthPx=1200&maxHeightPx=1200&key=${GOOGLE_PLACES_API_KEY}`;
}

async function downloadPhoto(googleUrl) {
  const res = await fetch(googleUrl, { redirect: "follow" });
  if (!res.ok) throw new Error(`download: ${res.status}`);
  const buf = Buffer.from(await res.arrayBuffer());
  if (buf.length < 200) throw new Error(`tiny payload (${buf.length} bytes)`);
  return buf;
}

async function uploadPhoto(slug, bytes) {
  const path = `${slug}.jpg`;
  const url = `${SUPABASE_URL}/storage/v1/object/${STORAGE_BUCKET}/${path}`;
  const res = await fetch(url, {
    method: "POST",
    headers: {
      apikey: SERVICE_KEY,
      Authorization: `Bearer ${SERVICE_KEY}`,
      "Content-Type": "image/jpeg",
      "x-upsert": "true",
    },
    body: bytes,
  });
  if (!res.ok) throw new Error(`storage upload ${res.status}: ${await res.text()}`);
  return `${STORAGE_PUBLIC_BASE}/${path}`;
}

async function upsertCityPhoto(city, photoUrl, source) {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/city_photos`, {
    method: "POST",
    headers: { ...restHeaders, Prefer: "resolution=merge-duplicates,return=minimal" },
    body: JSON.stringify({ city, photo_url: photoUrl, source }),
  });
  if (!res.ok) throw new Error(`upsert city_photos ${res.status}: ${await res.text()}`);
}

// ── Per-city pipeline ───────────────────────────────────────────────────

async function processCity(entry, existing, topVenuePhoto) {
  const { city, state } = entry;
  const current = existing.get(city);

  // Idempotent skip: already a stable Storage URL (unless FORCE).
  if (!FORCE && isStorageHosted(current)) {
    return { status: "already-hosted" };
  }

  const slug = citySlug(city, state);

  // 1. Try Google landmark photo → download → upload to Storage.
  let googleUrl = null;
  try {
    googleUrl = await fetchCityPhotoUrl(city, state);
  } catch (e) {
    googleUrl = null;
  }

  if (googleUrl) {
    let bytes;
    try {
      bytes = await downloadPhoto(googleUrl);
    } catch (e) {
      bytes = null;
    }
    if (bytes) {
      if (!WRITE) return { status: "ok-dry", source: "google_landmark", size: bytes.length };
      try {
        const storageUrl = await uploadPhoto(slug, bytes);
        await upsertCityPhoto(city, storageUrl, "google_landmark");
        return { status: "ok", source: "google_landmark", url: storageUrl };
      } catch (e) {
        return { status: "upload-failed", reason: e.message };
      }
    }
  }

  // 2. Fallback: top venue photo in that city (already a Storage URL).
  const vp = topVenuePhoto[city];
  if (vp && isStorageHosted(vp)) {
    if (!WRITE) return { status: "ok-dry", source: "venue_fallback" };
    try {
      await upsertCityPhoto(city, vp, "venue_fallback");
      return { status: "ok", source: "venue_fallback", url: vp };
    } catch (e) {
      return { status: "upsert-failed", reason: e.message };
    }
  }

  return { status: "no-photo" };
}

// ── Concurrency driver ──────────────────────────────────────────────────

async function processWithConcurrency(items, limit, fn, onResult) {
  const queue = [...items];
  let done = 0;
  const total = items.length;
  async function worker() {
    while (queue.length) {
      const item = queue.shift();
      const r = await fn(item).catch(e => ({ status: "error", reason: e.message }));
      done++;
      onResult(item, r, done, total);
      await new Promise(res => setTimeout(res, 150)); // ~6 req/s combined under Places quota
    }
  }
  await Promise.all(Array.from({ length: limit }, () => worker()));
}

// ── Main ────────────────────────────────────────────────────────────────

async function main() {
  console.log(`Mode: ${WRITE ? "WRITE" : "DRY RUN"}${FORCE ? " (FORCE)" : ""}${ONE_CITY ? ` (city=${ONE_CITY})` : ""}`);
  const secrets = await getSecrets(["GOOGLE_PLACES_API_KEY"]);
  GOOGLE_PLACES_API_KEY = secrets.GOOGLE_PLACES_API_KEY || process.env.GOOGLE_PLACES_API_KEY;
  if (!GOOGLE_PLACES_API_KEY) {
    console.error("Missing GOOGLE_PLACES_API_KEY (not in Supabase secrets table or env vars)");
    process.exit(1);
  }

  let cities = await fetchCities();
  console.log(`Found ${cities.length} cities (distinct venue_city among active future events)`);
  if (LIMIT) cities = cities.slice(0, LIMIT);

  const [existing, topVenuePhoto] = await Promise.all([
    fetchExistingPhotos(),
    fetchTopVenuePhotos(),
  ]);

  const tally = {};
  const failures = [];
  await processWithConcurrency(cities, CONCURRENCY, (c) => processCity(c, existing, topVenuePhoto), (c, r, done, total) => {
    tally[r.status] = (tally[r.status] || 0) + 1;
    if (done % 10 === 0 || done === total) {
      const summary = Object.entries(tally).map(([k, n]) => `${k}=${n}`).join(" ");
      process.stdout.write(`  ${done}/${total} | ${summary}\r`);
    }
    if (r.status === "no-photo" || r.status.endsWith("-failed") || r.status === "error") {
      failures.push(`${c.city}: ${r.status}${r.reason ? ` (${r.reason})` : ""}`);
    }
  });
  console.log("");
  console.log("\n=== Summary ===");
  for (const [k, n] of Object.entries(tally)) console.log(`  ${k}: ${n}`);
  if (failures.length) {
    console.log(`\n  ${failures.length} cities with no usable photo:`);
    for (const f of failures.slice(0, 40)) console.log(`    - ${f}`);
    if (failures.length > 40) console.log(`    … +${failures.length - 40} more`);
  }
}

main().catch(e => { console.error("Fatal:", e); process.exit(1); });
