/**
 * refresh_carousel_cache.js — pre-compute home-tab carousels for every
 * active metro × category combo and persist to the `carousel_cache` table.
 *
 * The carousels edge function reads from this table first when a request
 * arrives. Lets the user's home-tab cold open hit a single indexed
 * SELECT (~50 ms) instead of the 3-8 s aggregation path. The slow work
 * still runs — just here, in the background, on a schedule.
 *
 * Cron: every 10 minutes via .github/workflows/refresh_carousels.yml.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/refresh_carousel_cache.js
 */

const { SUPABASE_URL } = require("./lib/secrets");
const SERVICE_KEY = (process.env.SUPABASE_SERVICE_KEY || "").replace(/\s/g, "");
if (!SERVICE_KEY) { console.error("Missing SUPABASE_SERVICE_KEY"); process.exit(1); }

const ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImJsaWZ1YnhzbGF1Y3NobWxwaGRjIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzI3MjgyNjEsImV4cCI6MjA4ODMwNDI2MX0.xx-iy7kKhHviAjtjfXdxNHp4pmKU-in3efr2SkvWTf0";

const headers = {
  apikey: SERVICE_KEY,
  Authorization: `Bearer ${SERVICE_KEY}`,
  "Content-Type": "application/json",
};

const EDGE = `${SUPABASE_URL}/functions/v1/carousels`;

// Lat/lng round to 0.1° — same key the edge function uses for its in-memory
// cache. Keeps the row count small and shared across nearby users.
function bucket(lat, lng) {
  return {
    lat_bucket: Math.round(lat * 10) / 10,
    lng_bucket: Math.round(lng * 10) / 10,
  };
}

async function fetchActiveMetros() {
  const r = await fetch(
    `${SUPABASE_URL}/rest/v1/metros?is_active=eq.true&select=name,lat,lng`,
    { headers },
  );
  if (!r.ok) throw new Error(`metros: ${r.status}`);
  return r.json();
}

async function computeViaEdgeFn(lat, lng, category, locationName) {
  const url = new URL(EDGE);
  url.searchParams.set("lat", lat);
  url.searchParams.set("lng", lng);
  url.searchParams.set("radius", "25");
  url.searchParams.set("location_name", locationName);
  if (category && category !== "foryou") url.searchParams.set("category", category);
  // Header tells the edge function: skip carousel_cache check, we ARE the
  // refresher. Avoids the warm-cache circular hit during refresh.
  const r = await fetch(url, {
    headers: {
      Authorization: `Bearer ${ANON_KEY}`,
      "X-Refresh-Cache": "1",
    },
  });
  if (!r.ok) throw new Error(`edge fn ${r.status}: ${(await r.text()).slice(0, 200)}`);
  return r.text(); // raw JSON — we store as-is, edge fn reads it back
}

async function upsertCache(latBucket, lngBucket, category, payloadJson) {
  // Parse just enough to populate diagnostics columns
  const parsed = JSON.parse(payloadJson);
  const carouselCount = (parsed.carousels || []).length;
  const eventCount = (parsed.carousels || []).reduce((n, c) => n + (c.events?.length || 0), 0);

  const url = `${SUPABASE_URL}/rest/v1/carousel_cache?on_conflict=lat_bucket,lng_bucket,category`;
  const r = await fetch(url, {
    method: "POST",
    headers: { ...headers, Prefer: "return=minimal,resolution=merge-duplicates" },
    body: JSON.stringify([{
      lat_bucket: latBucket,
      lng_bucket: lngBucket,
      category,
      payload: parsed,
      event_count: eventCount,
      carousel_count: carouselCount,
      computed_at: new Date().toISOString(),
    }]),
  });
  if (!r.ok) throw new Error(`upsert: ${r.status} ${(await r.text()).slice(0, 200)}`);
  return { eventCount, carouselCount };
}

const CATEGORIES = ["foryou", "music", "arts", "sports", "food", "experience", "outdoors", "kids", "libraries"];

async function main() {
  console.log("=== Refreshing carousel_cache ===");
  const metros = await fetchActiveMetros();
  console.log(`${metros.length} active metros: ${metros.map(m=>m.name).join(", ")}`);
  console.log(`${CATEGORIES.length} categories: ${CATEGORIES.join(", ")}`);
  console.log(`Total work: ${metros.length * CATEGORIES.length} bucket-category pairs\n`);

  let ok = 0, fail = 0;
  const startMs = Date.now();
  for (const m of metros) {
    const { lat_bucket, lng_bucket } = bucket(m.lat, m.lng);
    for (const cat of CATEGORIES) {
      const t0 = Date.now();
      try {
        const payload = await computeViaEdgeFn(m.lat, m.lng, cat, m.name);
        const { eventCount, carouselCount } = await upsertCache(lat_bucket, lng_bucket, cat, payload);
        const ms = Date.now() - t0;
        console.log(`  ✓ ${m.name}/${cat}: ${eventCount} events, ${carouselCount} carousels (${ms}ms)`);
        ok++;
      } catch (e) {
        console.error(`  ✗ ${m.name}/${cat}: ${e.message.slice(0, 100)}`);
        fail++;
      }
    }
  }
  const totalSec = ((Date.now() - startMs) / 1000).toFixed(1);
  console.log(`\nDone in ${totalSec}s. Refreshed ${ok}, failed ${fail}.`);
}

main().catch(e => { console.error(e); process.exit(1); });
