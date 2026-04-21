/**
 * backfill_event_descriptions.js — Test & backfill event descriptions
 *
 * Picks a sample of events that have a source_url but a missing/short description,
 * runs the new extractDescriptionFromHtml() parser, and reports what it pulled.
 *
 * Pass WRITE=1 to actually update the DB; default is read-only (prints only).
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/backfill_event_descriptions.js
 *   SUPABASE_SERVICE_KEY=ey... SAMPLE=30 WRITE=1 node scripts/backfill_event_descriptions.js
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");
const { extractDescriptionFromHtml } = require("./run_crawls");

const SAMPLE = parseInt(process.env.SAMPLE || "20", 10);
const WRITE = process.env.WRITE === "1";

async function fetchPage(url) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 20_000);
  try {
    const res = await fetch(url, {
      headers: {
        "User-Agent":
          "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
      },
      signal: controller.signal,
      redirect: "follow",
    });
    clearTimeout(timeout);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return await res.text();
  } catch (err) {
    clearTimeout(timeout);
    throw err;
  }
}

async function main() {
  const secrets = await getSecrets([]);
  const SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;
  if (!SUPABASE_SERVICE_KEY) {
    console.error("Missing SUPABASE_SERVICE_KEY");
    process.exit(1);
  }

  console.log(`Sampling ${SAMPLE} crawler events with no description + source_url...`);
  console.log(`Mode: ${WRITE ? "WRITE (updates DB)" : "READ-ONLY (preview)"}`);
  console.log();

  // Events with missing/short description, source_url set, future start.
  const nowIso = new Date().toISOString();
  const url =
    `${SUPABASE_URL}/rest/v1/events` +
    `?select=id,title,description,source_url,venue_name,source` +
    `&is_active=eq.true` +
    `&start_at=gte.${nowIso}` +
    `&source_url=not.is.null` +
    `&or=(description.is.null,description.eq.)` +
    `&limit=${SAMPLE}`;

  const res = await fetch(url, {
    headers: {
      apikey: SUPABASE_SERVICE_KEY,
      Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
    },
  });
  if (!res.ok) {
    console.error(`Fetch failed: ${res.status} ${await res.text()}`);
    process.exit(1);
  }
  const events = await res.json();

  if (events.length === 0) {
    console.log("No matching events — all have descriptions. Done.");
    return;
  }

  console.log(`Found ${events.length} events. Processing...`);
  console.log();

  let got = 0;
  let empty = 0;
  let failed = 0;
  const toUpdate = [];

  for (let i = 0; i < events.length; i++) {
    const e = events[i];
    const label = `[${i + 1}/${events.length}] ${e.title.slice(0, 60)}`;
    try {
      const html = await fetchPage(e.source_url);
      const desc = extractDescriptionFromHtml(html);
      if (desc && desc.length >= 40) {
        got++;
        console.log(`${label} ✓`);
        console.log(`    → ${desc.slice(0, 180)}${desc.length > 180 ? "..." : ""}`);
        toUpdate.push({ id: e.id, description: desc });
      } else {
        empty++;
        console.log(`${label} — no structured description`);
      }
    } catch (err) {
      failed++;
      console.log(`${label} ✗ fetch failed: ${err.message}`);
    }
  }

  console.log();
  console.log("=== Summary ===");
  console.log(`  got:    ${got}/${events.length}  (${Math.round((got / events.length) * 100)}%)`);
  console.log(`  empty:  ${empty}`);
  console.log(`  failed: ${failed}`);

  if (!WRITE) {
    console.log();
    console.log("Read-only mode. Re-run with WRITE=1 to apply.");
    return;
  }

  if (toUpdate.length === 0) return;

  console.log();
  console.log(`Applying ${toUpdate.length} updates...`);
  // PostgREST doesn't do multi-row updates with different values in one call,
  // so loop. Fast enough for a sample.
  let ok = 0;
  for (const u of toUpdate) {
    const pres = await fetch(`${SUPABASE_URL}/rest/v1/events?id=eq.${u.id}`, {
      method: "PATCH",
      headers: {
        apikey: SUPABASE_SERVICE_KEY,
        Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ description: u.description }),
    });
    if (pres.ok) ok++;
  }
  console.log(`  updated: ${ok}/${toUpdate.length}`);
}

main().catch((err) => {
  console.error("Fatal:", err);
  process.exit(1);
});
