/**
 * migrate_venue_photos_to_storage.js — download Google Places venue photos
 * to Supabase Storage so the URLs don't rot.
 *
 * Background: Google Places API v1 photo references rotate over time.
 * Stored URLs like .../photos/{ref}/media?key=... 404 after weeks/months.
 * Once a venue's URL goes stale, every fresh device load shows a placeholder.
 *
 * Fix: re-fetch a fresh photo from Google, download the bytes, upload to
 * the public `venue-photos` bucket at `{venue_id}.jpg`, store the public
 * Storage URL on the venue. Storage URLs are permanent.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/migrate_venue_photos_to_storage.js                         # dry run, all
 *   SUPABASE_SERVICE_KEY=ey... WRITE=1 node scripts/migrate_venue_photos_to_storage.js                 # apply
 *   SUPABASE_SERVICE_KEY=ey... LIMIT=20 WRITE=1 node scripts/migrate_venue_photos_to_storage.js        # cap
 *   SUPABASE_SERVICE_KEY=ey... ONLY_NULL=1 WRITE=1 node scripts/migrate_venue_photos_to_storage.js     # only fill nulls
 *   SUPABASE_SERVICE_KEY=ey... VENUE_ID=<uuid> WRITE=1 node scripts/migrate_venue_photos_to_storage.js # one venue
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

const SERVICE_KEY = (process.env.SUPABASE_SERVICE_KEY || "").replace(/\s/g, "");
const WRITE = process.env.WRITE === "1";
const LIMIT = parseInt(process.env.LIMIT || "0", 10) || null;
const ONLY_NULL = process.env.ONLY_NULL === "1";
const VENUE_ID = process.env.VENUE_ID || null;
const CONCURRENCY = parseInt(process.env.CONCURRENCY || "5", 10);

if (!SERVICE_KEY) { console.error("Missing SUPABASE_SERVICE_KEY"); process.exit(1); }

let GOOGLE_PLACES_API_KEY;

const restHeaders = {
  apikey: SERVICE_KEY,
  Authorization: `Bearer ${SERVICE_KEY}`,
  "Content-Type": "application/json",
};

const STORAGE_BUCKET = "venue-photos";
const STORAGE_PUBLIC_BASE = `${SUPABASE_URL}/storage/v1/object/public/${STORAGE_BUCKET}`;

// ── Fetch venue list ────────────────────────────────────────────────────

async function fetchVenues() {
  if (VENUE_ID) {
    const r = await fetch(
      `${SUPABASE_URL}/rest/v1/venues?id=eq.${VENUE_ID}&select=id,name,city,state,lat,lng,photo_url`,
      { headers: restHeaders },
    );
    return r.json();
  }
  const all = [];
  let offset = 0;
  while (true) {
    let url = `${SUPABASE_URL}/rest/v1/venues?is_active=eq.true&select=id,name,city,state,lat,lng,photo_url&order=public_interest_rank.desc.nullslast&offset=${offset}&limit=1000`;
    if (ONLY_NULL) url += `&photo_url=is.null`;
    const r = await fetch(url, { headers: restHeaders });
    if (!r.ok) throw new Error(`fetch venues: ${r.status} ${await r.text()}`);
    const rows = await r.json();
    all.push(...rows);
    process.stdout.write(`  loaded ${all.length}\r`);
    if (rows.length < 1000) break;
    offset += 1000;
  }
  console.log("");
  return all;
}

// ── Already-migrated detection ──────────────────────────────────────────

function isAlreadyMigrated(url) {
  return !!url && url.startsWith(STORAGE_PUBLIC_BASE);
}

// ── Google Places photo lookup ──────────────────────────────────────────

async function fetchFreshPhotoUrl(name, city, state, lat, lng) {
  const query = [name, city, state].filter(Boolean).join(", ");
  const body = {
    textQuery: query,
    locationBias: lat && lng
      ? { circle: { center: { latitude: lat, longitude: lng }, radius: 5000 } }
      : undefined,
  };
  const res = await fetch("https://places.googleapis.com/v1/places:searchText", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Goog-Api-Key": GOOGLE_PLACES_API_KEY,
      "X-Goog-FieldMask": "places.photos",
    },
    body: JSON.stringify(body),
  });
  if (!res.ok) return null;
  const data = await res.json();
  const photoRef = data.places?.[0]?.photos?.[0]?.name;
  if (!photoRef) return null;
  // The /media endpoint redirects to the actual lh3.googleusercontent.com URL.
  return `https://places.googleapis.com/v1/${photoRef}/media?maxWidthPx=1200&maxHeightPx=1200&key=${GOOGLE_PLACES_API_KEY}`;
}

async function downloadPhoto(googleUrl) {
  // Follow the redirect from places.googleapis.com → lh3.googleusercontent.com
  const res = await fetch(googleUrl, { redirect: "follow" });
  if (!res.ok) throw new Error(`download: ${res.status}`);
  const buf = Buffer.from(await res.arrayBuffer());
  if (buf.length < 200) throw new Error(`tiny payload (${buf.length} bytes)`);
  return buf;
}

// ── Supabase Storage upload ─────────────────────────────────────────────

async function uploadPhoto(venueId, bytes) {
  const path = `${venueId}.jpg`;
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

async function updateVenuePhotoUrl(venueId, photoUrl) {
  const url = `${SUPABASE_URL}/rest/v1/venues?id=eq.${venueId}`;
  const res = await fetch(url, {
    method: "PATCH",
    headers: { ...restHeaders, Prefer: "return=minimal" },
    body: JSON.stringify({ photo_url: photoUrl }),
  });
  if (!res.ok) throw new Error(`patch venue ${res.status}: ${await res.text()}`);
}

// ── Per-venue pipeline ──────────────────────────────────────────────────

async function migrateOne(venue) {
  if (isAlreadyMigrated(venue.photo_url)) {
    return { status: "already-migrated" };
  }
  let freshUrl = null;
  try {
    freshUrl = await fetchFreshPhotoUrl(venue.name, venue.city, venue.state, venue.lat, venue.lng);
  } catch (e) {
    return { status: "search-failed", reason: e.message };
  }
  if (!freshUrl) return { status: "no-photo" };

  let bytes;
  try {
    bytes = await downloadPhoto(freshUrl);
  } catch (e) {
    return { status: "download-failed", reason: e.message };
  }

  if (!WRITE) {
    return { status: "ok-dry", size: bytes.length };
  }

  let storageUrl;
  try {
    storageUrl = await uploadPhoto(venue.id, bytes);
  } catch (e) {
    return { status: "upload-failed", reason: e.message };
  }
  try {
    await updateVenuePhotoUrl(venue.id, storageUrl);
  } catch (e) {
    return { status: "patch-failed", reason: e.message };
  }
  return { status: "ok", url: storageUrl, size: bytes.length };
}

// ── Driver with concurrency ─────────────────────────────────────────────

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
      // Pace each worker to ~6 req/s so combined load stays under Places quota
      await new Promise(r => setTimeout(r, 150));
    }
  }
  await Promise.all(Array.from({ length: limit }, () => worker()));
}

// ── Main ────────────────────────────────────────────────────────────────

async function main() {
  console.log(`Mode: ${WRITE ? "WRITE" : "DRY RUN"}${ONLY_NULL ? " (ONLY_NULL)" : ""}${VENUE_ID ? ` (single)` : ""}`);
  const secrets = await getSecrets(["GOOGLE_PLACES_API_KEY"]);
  GOOGLE_PLACES_API_KEY = secrets.GOOGLE_PLACES_API_KEY;
  if (!GOOGLE_PLACES_API_KEY) { console.error("Missing GOOGLE_PLACES_API_KEY"); process.exit(1); }

  let venues = await fetchVenues();
  console.log(`Loaded ${venues.length} venues`);
  if (LIMIT) venues = venues.slice(0, LIMIT);

  const tally = {};
  await processWithConcurrency(venues, CONCURRENCY, migrateOne, (v, r, done, total) => {
    tally[r.status] = (tally[r.status] || 0) + 1;
    if (done % 25 === 0 || done === total) {
      const summary = Object.entries(tally).map(([k,n]) => `${k}=${n}`).join(" ");
      process.stdout.write(`  ${done}/${total} | ${summary}\r`);
    }
    if (r.status.startsWith("ok") && WRITE) {
      // small per-success logging for diagnosis
    }
    if (r.status === "no-photo" || r.status.endsWith("-failed")) {
      // surface failures
      // (don't print on the same line as the progress carriage return)
    }
  });
  console.log("");
  console.log("\n=== Summary ===");
  for (const [k, n] of Object.entries(tally)) console.log(`  ${k}: ${n}`);
}

main().catch(e => { console.error(e); process.exit(1); });
