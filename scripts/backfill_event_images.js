/**
 * backfill_event_images.js — populate events.image_url from venues.photo_url
 *
 * One-shot equivalent of the 20260517120200 migration. We had to fall back
 * to a script because the join `events.venue_name = venues.name` has no
 * supporting index, so the cross-table UPDATE blew the Supabase SQL editor
 * timeout in one shot.
 *
 * Strategy: small batch by venue. There are at most thousands of venues
 * with photos; PostgREST PATCH scoped to one venue_name is bounded and
 * fast, and each loop iteration is its own statement so the 8s timeout
 * never compounds.
 *
 * Idempotent: re-running just no-ops on rows whose image_url is already
 * non-null (we filter image_url=is.null on every PATCH).
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/backfill_event_images.js
 */

const { SUPABASE_URL } = require("./lib/secrets");
const SERVICE_KEY = (process.env.SUPABASE_SERVICE_KEY || "").replace(/\s/g, "");
if (!SERVICE_KEY) { console.error("Missing SUPABASE_SERVICE_KEY"); process.exit(1); }

const headers = {
  apikey: SERVICE_KEY,
  Authorization: `Bearer ${SERVICE_KEY}`,
  "Content-Type": "application/json",
};

async function fetchVenuesWithPhotos() {
  const all = [];
  let offset = 0;
  const limit = 1000;
  while (true) {
    const url =
      `${SUPABASE_URL}/rest/v1/venues` +
      `?select=name,photo_url` +
      `&photo_url=not.is.null` +
      `&order=name.asc` +
      `&offset=${offset}&limit=${limit}`;
    const res = await fetch(url, { headers });
    if (!res.ok) throw new Error(`fetch venues: ${res.status} ${await res.text()}`);
    const rows = await res.json();
    all.push(...rows);
    process.stdout.write(`  fetched ${all.length} venues\r`);
    if (rows.length < limit) break;
    offset += limit;
  }
  console.log("");
  return all;
}

async function patchEventsForVenue(venueName, photoUrl) {
  // PostgREST: scope by venue_name AND image_url IS NULL. Each PATCH is
  // its own transaction — bounded by the row count for one venue, which
  // is at most low hundreds.
  const url =
    `${SUPABASE_URL}/rest/v1/events` +
    `?venue_name=eq.${encodeURIComponent(venueName)}` +
    `&image_url=is.null`;
  // Retry on transient network errors (UND_ERR_CONNECT_TIMEOUT, etc.) so a
  // single blip doesn't abort the 13k-venue run. HTTP errors are not
  // retried — those are PostgREST telling us the request itself is bad
  // (414 URL-too-long, etc.), retrying won't help.
  let lastErr;
  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      const res = await fetch(url, {
        method: "PATCH",
        headers: { ...headers, Prefer: "return=representation,count=exact" },
        body: JSON.stringify({ image_url: photoUrl }),
      });
      if (!res.ok) {
        // Skip venues whose PATCH returns non-2xx — log and continue. Not
        // retried because a 4xx/5xx response means PostgREST processed
        // the request and rejected it.
        console.log(`    skip ${venueName}: ${res.status}`);
        return 0;
      }
      // Content-Range: 0-N/N gives the row count when count=exact is set.
      const range = res.headers.get("content-range") || "";
      const match = range.match(/\/(\d+|\*)$/);
      return match && match[1] !== "*" ? parseInt(match[1], 10) : 0;
    } catch (err) {
      lastErr = err;
      const wait = 500 * attempt; // 500ms, 1s, 1.5s
      console.log(`    retry ${attempt}/3 ${venueName}: ${err.code || err.message} (waiting ${wait}ms)`);
      await new Promise((r) => setTimeout(r, wait));
    }
  }
  // All retries failed — log and skip this venue rather than aborting.
  console.log(`    skip ${venueName}: ${lastErr?.code || lastErr?.message || "unknown error"} after 3 attempts`);
  return 0;
}

async function main() {
  console.log("Loading venues with photo_url...");
  const venues = await fetchVenuesWithPhotos();
  console.log(`${venues.length} venues have a photo_url`);

  let totalPatched = 0;
  let venuesTouched = 0;
  for (let i = 0; i < venues.length; i++) {
    const v = venues[i];
    if (!v.name || !v.photo_url) continue;
    const n = await patchEventsForVenue(v.name, v.photo_url);
    if (n > 0) {
      totalPatched += n;
      venuesTouched++;
    }
    if ((i + 1) % 50 === 0 || i === venues.length - 1) {
      process.stdout.write(`  ${i + 1}/${venues.length} venues processed · ${totalPatched} events filled\r`);
    }
  }
  console.log("");
  console.log(`Done. Filled image_url on ${totalPatched} events across ${venuesTouched} venues.`);
}

main().catch((e) => { console.error(e); process.exit(1); });
