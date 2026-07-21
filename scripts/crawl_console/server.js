/**
 * crawl_console/server.js — a locally-hosted crawl repair console.
 *
 * Purpose: fix the "has URL, 0 events" (and "no URL") backlog one venue at a
 * time, with a human in the loop. Filter live venues by state + PIR, paste a
 * candidate events URL, run the EXACT production crawler against it with no
 * writes, see what it would extract, and — if it looks good — persist the URL
 * and the events so the next scheduled crawl keeps the venue green.
 *
 * Parity guarantee: /api/test and /api/commit both call run_crawls.js's
 * crawlVenue(), the same per-venue entrypoint the weekly crawl uses. So
 * "works in the console" == "works next crawl". /api/test runs it in the
 * opt-in DRY_RUN mode (no DB writes); /api/commit saves events_url first,
 * then runs it for real.
 *
 * Run it (on your Mac mini / laptop):
 *   SUPABASE_SERVICE_KEY=ey... node scripts/crawl_console/server.js
 *   # then open http://localhost:5178
 *
 * Localhost-only bind. Reads/writes PROD by default (same as the crawl
 * scripts). Point at dev by exporting SUPABASE_URL=https://xbyqwekcanrzovytxmdv.supabase.co
 */

const http = require("http");
const fs = require("fs");
const path = require("path");

const crawler = require("../run_crawls.js");

const SERVICE_KEY = (process.env.SUPABASE_SERVICE_KEY || "").replace(/\s/g, "");
if (!SERVICE_KEY) {
  console.error("Missing SUPABASE_SERVICE_KEY env var.");
  console.error("Run:  SUPABASE_SERVICE_KEY=ey... node scripts/crawl_console/server.js");
  process.exit(1);
}
const SUPABASE_URL = crawler.SUPABASE_URL;
const PORT = parseInt(process.env.PORT || "5178", 10);
const HOST = process.env.HOST || "127.0.0.1";

const SB_HEADERS = {
  apikey: SERVICE_KEY,
  Authorization: `Bearer ${SERVICE_KEY}`,
};

const norm = (s) => (s || "").trim().toLowerCase();
const today = () => new Date().toISOString().slice(0, 10);

// ── Supabase REST helpers ──

async function sbFetchAll(pathAndQuery) {
  const rows = [];
  let offset = 0;
  const limit = 1000;
  while (true) {
    const res = await fetch(`${SUPABASE_URL}/rest/v1/${pathAndQuery}&offset=${offset}&limit=${limit}`, {
      headers: SB_HEADERS,
    });
    if (!res.ok) throw new Error(`Supabase ${res.status}: ${(await res.text()).slice(0, 200)}`);
    const batch = await res.json();
    rows.push(...batch);
    if (batch.length < limit) break;
    offset += limit;
  }
  return rows;
}

async function sbGetVenue(id) {
  const res = await fetch(
    `${SUPABASE_URL}/rest/v1/venues?id=eq.${encodeURIComponent(id)}&select=*&limit=1`,
    { headers: SB_HEADERS }
  );
  if (!res.ok) throw new Error(`Supabase ${res.status}: ${(await res.text()).slice(0, 200)}`);
  const rows = await res.json();
  return rows[0] || null;
}

async function sbPatchVenue(id, patch) {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/venues?id=eq.${encodeURIComponent(id)}`, {
    method: "PATCH",
    headers: { ...SB_HEADERS, "Content-Type": "application/json", Prefer: "return=minimal" },
    body: JSON.stringify(patch),
  });
  if (!res.ok) throw new Error(`Venue PATCH ${res.status}: ${(await res.text()).slice(0, 200)}`);
}

// ── Venue listing with live event counts (matches the dashboard's name+state join) ──

async function listVenues({ state, minPir, coverage }) {
  const st = (state || "").trim().toUpperCase();
  const pir = Number.isFinite(+minPir) ? +minPir : 0;

  let vq = `venues?is_active=eq.true&public_interest_rank=gt.${pir}` +
    `&select=id,name,city,state,public_interest_rank,events_url,last_crawled_at,crawl_success_rate,needs_stealth` +
    `&order=public_interest_rank.desc`;
  if (st) vq += `&state=eq.${encodeURIComponent(st)}`;
  const venues = await sbFetchAll(vq);

  // Active upcoming events for the same state, counted by normalized name.
  let eq = `events?is_active=eq.true&start_at=gte.${today()}T00:00:00Z&select=venue_name,venue_state`;
  if (st) eq += `&venue_state=eq.${encodeURIComponent(st)}`;
  const events = await sbFetchAll(eq);
  const counts = new Map();
  for (const ev of events) {
    const key = `${ev.venue_state || ""}|${norm(ev.venue_name)}`;
    counts.set(key, (counts.get(key) || 0) + 1);
  }

  let out = venues.map((v) => ({
    id: v.id,
    name: v.name,
    city: v.city || "",
    state: v.state || "",
    pir: v.public_interest_rank,
    events_url: v.events_url || "",
    needs_stealth: !!v.needs_stealth,
    last_crawled_at: v.last_crawled_at,
    crawl_success_rate: v.crawl_success_rate,
    event_count: counts.get(`${v.state || ""}|${norm(v.name)}`) || 0,
  }));

  if (coverage === "zero") out = out.filter((v) => v.event_count === 0);
  else if (coverage === "lt2") out = out.filter((v) => v.event_count < 2);
  else if (coverage === "nourl") out = out.filter((v) => !v.events_url);

  // Worst first: zero-event, no-URL, stalest.
  out.sort((a, b) => a.event_count - b.event_count || (b.pir || 0) - (a.pir || 0));
  return out;
}

// Shrink a captured/normalized event row to what the UI needs to eyeball it.
function previewRow(r) {
  return {
    title: r.title || "(untitled)",
    start_at: r.start_at || null,
    venue_name: r.venue_name || null,
    category: r.category || null,
    source_url: r.source_url || null,
  };
}

// Merge rows captured by internal upserts (platform crawlers) with rows returned
// up to the caller (Claude-fallback / multi-url path). A crawl takes one path,
// but merging + de-duping by dedupe_hash is robust to both.
function collectRows(capturedRows, result) {
  const byKey = new Map();
  for (const r of [...capturedRows, ...(result.rows || [])]) {
    const k = r.dedupe_hash || `${norm(r.title)}|${r.start_at}`;
    byKey.set(k, r);
  }
  return [...byKey.values()];
}

async function testCrawl({ venueId, url, needsStealth }) {
  const venueRow = await sbGetVenue(venueId);
  if (!venueRow) throw new Error("venue not found");
  const venue = { ...venueRow, events_url: (url || "").trim(), needs_stealth: !!needsStealth };
  if (!venue.events_url) throw new Error("no URL to test");

  crawler.setDryRun(true);
  crawler.drainCapturedRows(); // clear any prior capture
  let result, captured = [];
  try {
    result = await crawler.crawlVenue(venue);
  } finally {
    // Always leave the module in a clean, write-enabled state.
    captured = crawler.drainCapturedRows();
    crawler.setDryRun(false);
  }
  const rows = collectRows(captured, result);
  return {
    status: result.status,
    reason: result.reason || null,
    count: rows.length,
    events: rows
      .sort((a, b) => String(a.start_at || "").localeCompare(String(b.start_at || "")))
      .slice(0, 50)
      .map(previewRow),
  };
}

async function commitCrawl({ venueId, url, needsStealth }) {
  const venueRow = await sbGetVenue(venueId);
  if (!venueRow) throw new Error("venue not found");
  const cleanUrl = (url || "").trim();
  if (!cleanUrl) throw new Error("no URL to save");

  // 1. Persist the URL (and stealth flag) so the next scheduled crawl uses it.
  const patch = { events_url: cleanUrl, needs_stealth: !!needsStealth };
  await sbPatchVenue(venueId, patch);

  // 2. Crawl for real, right now, so the venue goes green immediately.
  crawler.setDryRun(false);
  const venue = { ...venueRow, events_url: cleanUrl, needs_stealth: !!needsStealth };
  const result = await crawler.crawlVenue(venue);

  // The Claude-fallback / multi-url path returns rows for the caller to upsert
  // (that's what main() does); platform crawlers already upserted internally.
  // Upsert returned rows too — idempotent on dedupe_hash, so no double-writes.
  let saved = result.events || 0;
  if (result.rows && result.rows.length) {
    const up = await crawler.upsertEvents(result.rows);
    saved = Math.max(saved, up.inserted || 0);
  }
  return { ok: true, status: result.status, reason: result.reason || null, saved };
}

// ── HTTP plumbing ──

function sendJson(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(body) });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = "";
    req.on("data", (c) => {
      data += c;
      if (data.length > 1e6) reject(new Error("body too large"));
    });
    req.on("end", () => {
      try {
        resolve(data ? JSON.parse(data) : {});
      } catch (e) {
        reject(new Error("invalid JSON body"));
      }
    });
    req.on("error", reject);
  });
}

const server = http.createServer(async (req, res) => {
  try {
    const u = new URL(req.url, `http://${req.headers.host}`);

    if (req.method === "GET" && u.pathname === "/") {
      const html = fs.readFileSync(path.join(__dirname, "index.html"));
      res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
      return res.end(html);
    }

    if (req.method === "GET" && u.pathname === "/api/venues") {
      const list = await listVenues({
        state: u.searchParams.get("state") || "",
        minPir: u.searchParams.get("minPir") || "0",
        coverage: u.searchParams.get("coverage") || "zero",
      });
      return sendJson(res, 200, { count: list.length, venues: list });
    }

    if (req.method === "POST" && u.pathname === "/api/test") {
      const body = await readBody(req);
      const out = await testCrawl(body);
      return sendJson(res, 200, out);
    }

    if (req.method === "POST" && u.pathname === "/api/commit") {
      const body = await readBody(req);
      const out = await commitCrawl(body);
      return sendJson(res, 200, out);
    }

    sendJson(res, 404, { error: "not found" });
  } catch (err) {
    console.error(err.stack || err.message);
    sendJson(res, 500, { error: err.message });
  }
});

(async () => {
  const { scrapfly } = await crawler.initSecrets();
  server.listen(PORT, HOST, () => {
    console.log(`\n  Crawl console → http://${HOST}:${PORT}`);
    console.log(`  Supabase: ${SUPABASE_URL}`);
    console.log(`  Scrapfly (JS-render): ${scrapfly ? "available" : "NOT configured — 'JS render' venues fall back to direct fetch"}`);
    console.log(`  Ctrl-C to stop.\n`);
  });
})().catch((e) => {
  console.error("Startup failed:", e.message);
  process.exit(1);
});
