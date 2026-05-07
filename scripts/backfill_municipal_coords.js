/**
 * backfill_municipal_coords.js — populate venue_lat/venue_lng on already-
 * synced municipal_* events that landed with NULL coords.
 *
 * Without this, the carousel edge function's radius filter drops them all
 * (distance_miles=NULL → treated as 999mi → fails (radius<=25) every time).
 *
 * One PATCH per metro: shifts every municipal_<metro> event with NULL
 * coords to that metro's center. Future syncs do this inline (sync_
 * municipal_feeds.js loads METRO_COORDS at startup).
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/backfill_municipal_coords.js
 */

const { SUPABASE_URL } = require("./lib/secrets");
const SERVICE_KEY = (process.env.SUPABASE_SERVICE_KEY || "").replace(/\s/g, "");
if (!SERVICE_KEY) { console.error("Missing SUPABASE_SERVICE_KEY"); process.exit(1); }

const headers = {
  apikey: SERVICE_KEY,
  Authorization: `Bearer ${SERVICE_KEY}`,
  "Content-Type": "application/json",
};

async function main() {
  // Load active metros
  const mres = await fetch(`${SUPABASE_URL}/rest/v1/metros?is_active=eq.true&select=name,lat,lng`, { headers });
  if (!mres.ok) throw new Error(`metros fetch ${mres.status}`);
  const metros = await mres.json();
  console.log(`${metros.length} active metros: ${metros.map(m=>m.name).join(", ")}`);

  let total = 0;
  for (const m of metros) {
    const slug = (m.name || "").toLowerCase();
    const source = `municipal_${slug}`;
    // Page through to avoid statement timeout — same trick as deactivate_past_events.
    let metroTotal = 0;
    while (true) {
      const url =
        `${SUPABASE_URL}/rest/v1/events` +
        `?source=eq.${source}` +
        `&venue_lat=is.null` +
        `&select=id&limit=500`;
      const res = await fetch(url, { headers });
      if (!res.ok) throw new Error(`select ${source}: ${res.status} ${await res.text()}`);
      const rows = await res.json();
      if (rows.length === 0) break;
      const ids = rows.map(r => r.id);
      const patchUrl = `${SUPABASE_URL}/rest/v1/events?id=in.(${ids.join(",")})`;
      const pres = await fetch(patchUrl, {
        method: "PATCH",
        headers: { ...headers, Prefer: "return=minimal" },
        body: JSON.stringify({ venue_lat: m.lat, venue_lng: m.lng }),
      });
      if (!pres.ok) throw new Error(`patch ${source}: ${pres.status} ${await pres.text()}`);
      metroTotal += ids.length;
      total += ids.length;
      if (rows.length < 500) break;
    }
    console.log(`  ${source}: backfilled ${metroTotal} (${m.lat.toFixed(2)}, ${m.lng.toFixed(2)})`);
  }
  console.log(`\nDone. Backfilled ${total} municipal events.`);
}

main().catch(e => { console.error(e); process.exit(1); });
