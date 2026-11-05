/**
 * backfill_municipal_coords.js — populate venue_lat/venue_lng on already-
 * synced municipal_* events that landed with NULL coords AND have NO named
 * venue (truly at-large civic events — a city-wide block party, a "Town of X"
 * holiday, etc.). The metro center is the correct default ONLY for these.
 *
 * Without coords, the carousel edge function's radius filter drops them all
 * (distance_miles=NULL → treated as 999mi → fails (radius<=25) every time).
 *
 * ⚠️ DO NOT remove the `venue_name=is.null` guard. An earlier version of this
 * script stamped the metro center on EVERY null-coord municipal event,
 * including ones with a real venue_name in a different town. That is exactly
 * the bug that made Scranton / Haverford events render "Here" to a
 * Philadelphia user (and MASS MoCA "<1 mi" to a Boston user). Events WITH a
 * venue_name must get their coords from sync_municipal_feeds.js's
 * resolveVenueCoords (venues-table → Claude), never from this blanket PATCH.
 * See scripts/fix_municipal_centroid_coords.js for the retroactive repair.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/backfill_municipal_coords.js
 */

const { SUPABASE_URL } = require("../lib/secrets");
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
        `&venue_name=is.null` + // at-large civic events only — see header warning
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
    console.log(`  ${source}: backfilled ${metroTotal} at-large events (${m.lat.toFixed(2)}, ${m.lng.toFixed(2)})`);
  }
  console.log(`\nDone. Backfilled ${total} at-large (venue_name IS NULL) municipal events.`);
}

main().catch(e => { console.error(e); process.exit(1); });
