/**
 * venue_state_of_the_union.js — Monthly venue health snapshot ("State of the Union")
 *
 * Produces, across all active venues:
 *   1. Venue count + % share by state.
 *   2. Per-state coverage overview:
 *        - venues with 10+ active events
 *        - venues with 1+ active events
 *        - venues with 0 active events
 *        - venues with NO events_url
 *        - venues WITH an events_url but still 0 events (the crawl content gap)
 *
 * Event linkage mirrors the rest of the pipeline: an event "belongs" to a venue
 * when events.venue_name matches venues.name (case-insensitive) within the same
 * state. There is no events.venue_id FK. "Active" = is_active=true AND
 * start_at >= today (past events are deactivated hourly). This name-match is
 * approximate (alias drift undercounts slightly) but is exactly how the app
 * reasons about which venues have content — see match_event_venues.js.
 *
 * Output:
 *   - Rich Markdown to $GITHUB_STEP_SUMMARY (shows on the Actions run page).
 *   - An HTML digest (./sotu_email.html) + subject (./sotu_subject.txt), which
 *     the monthly workflow emails via Resend (same path as the venue-health alert).
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/venue_state_of_the_union.js
 */

const fs = require("fs");
const { SUPABASE_URL } = require("./lib/secrets");

const SUPABASE_SERVICE_KEY = (process.env.SUPABASE_SERVICE_KEY || "").replace(/\s/g, "");
if (!SUPABASE_SERVICE_KEY) {
  console.error("Missing SUPABASE_SERVICE_KEY env var");
  process.exit(1);
}

const HEADERS = {
  apikey: SUPABASE_SERVICE_KEY,
  Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
};

const TODAY = new Date().toISOString().slice(0, 10); // YYYY-MM-DD (date is injected by the runner)

async function fetchAll(path) {
  const rows = [];
  let offset = 0;
  const limit = 1000;
  while (true) {
    const res = await fetch(`${SUPABASE_URL}/rest/v1/${path}&offset=${offset}&limit=${limit}`, {
      headers: HEADERS,
    });
    if (!res.ok) {
      const body = await res.text();
      throw new Error(`Supabase ${res.status} on ${path}: ${body.slice(0, 200)}`);
    }
    const batch = await res.json();
    rows.push(...batch);
    if (batch.length < limit) break;
    offset += limit;
  }
  return rows;
}

const norm = (s) => (s || "").trim().toLowerCase();
const pct = (n, d) => (d === 0 ? 0 : Math.round((n / d) * 100));
const comma = (n) => n.toLocaleString("en-US");

async function main() {
  console.log(`Building Venue State of the Union (as of ${TODAY})…`);

  // State code → display name (best-effort; falls back to the raw code).
  let stateNames = {};
  try {
    const states = await fetchAll(`states?select=code,name&`);
    stateNames = Object.fromEntries(states.map((s) => [s.code, s.name]));
  } catch (e) {
    console.warn(`Could not load states table (${e.message}); using raw codes.`);
  }

  const venues = await fetchAll(`venues?is_active=eq.true&select=name,state,events_url&`);
  const events = await fetchAll(
    `events?is_active=eq.true&start_at=gte.${TODAY}T00:00:00Z&select=venue_name,venue_state&`
  );

  console.log(`Loaded ${comma(venues.length)} active venues, ${comma(events.length)} active upcoming events.`);

  // Event counts keyed by "STATE|normalized venue name".
  const eventCounts = new Map();
  for (const ev of events) {
    const key = `${ev.venue_state || ""}|${norm(ev.venue_name)}`;
    eventCounts.set(key, (eventCounts.get(key) || 0) + 1);
  }

  // Per-state aggregation.
  const byState = new Map(); // state -> stats
  for (const v of venues) {
    const state = v.state || "??";
    if (!byState.has(state)) {
      byState.set(state, { total: 0, ge10: 0, ge1: 0, eq0: 0, noUrl: 0, urlButZero: 0 });
    }
    const s = byState.get(state);
    s.total++;

    const n = eventCounts.get(`${state}|${norm(v.name)}`) || 0;
    if (n >= 10) s.ge10++;
    if (n >= 1) s.ge1++;
    else s.eq0++;

    const hasUrl = !!(v.events_url && v.events_url.trim());
    if (!hasUrl) s.noUrl++;
    if (hasUrl && n === 0) s.urlButZero++;
  }

  const totalVenues = venues.length;
  const states = [...byState.entries()].sort((a, b) => b[1].total - a[1].total);
  const label = (code) => stateNames[code] || code;

  // ── Markdown (full) ──
  const md = [];
  md.push(`# 🏛 Venue State of the Union — ${TODAY}`);
  md.push("");
  md.push(`**${comma(totalVenues)} active venues** across ${states.length} states.`);
  md.push("");
  md.push("## Venues by state");
  md.push("");
  md.push("| State | Venues | Share |");
  md.push("| --- | ---: | ---: |");
  for (const [code, s] of states) {
    md.push(`| ${label(code)} (${code}) | ${comma(s.total)} | ${pct(s.total, totalVenues)}% |`);
  }
  md.push("");
  md.push("## Per-state coverage");
  md.push("");
  md.push("_Event match is by venue name within state; “URL + 0 events” = a crawl target that yields nothing (the content gap)._");
  md.push("");
  md.push("| State | Venues | 10+ events | 1+ events | 0 events | No events URL | URL + 0 events |");
  md.push("| --- | ---: | ---: | ---: | ---: | ---: | ---: |");
  for (const [code, s] of states) {
    md.push(
      `| ${label(code)} (${code}) | ${comma(s.total)} ` +
        `| ${pct(s.ge10, s.total)}% (${comma(s.ge10)}) ` +
        `| ${pct(s.ge1, s.total)}% (${comma(s.ge1)}) ` +
        `| ${pct(s.eq0, s.total)}% (${comma(s.eq0)}) ` +
        `| ${pct(s.noUrl, s.total)}% (${comma(s.noUrl)}) ` +
        `| ${pct(s.urlButZero, s.total)}% (${comma(s.urlButZero)}) |`
    );
  }
  const markdown = md.join("\n") + "\n";

  if (process.env.GITHUB_STEP_SUMMARY) {
    fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, markdown);
  }
  console.log("\n" + markdown);

  // ── HTML email digest (emailed via Resend by the workflow) ──
  const esc = (v) => String(v).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  const th = 'style="padding:6px 10px;text-align:right;border-bottom:2px solid #d0d7de;font-size:13px;color:#57606a"';
  const thL = 'style="padding:6px 10px;text-align:left;border-bottom:2px solid #d0d7de;font-size:13px;color:#57606a"';
  const td = 'style="padding:6px 10px;text-align:right;border-bottom:1px solid #eaeef2;font-size:14px"';
  const tdL = 'style="padding:6px 10px;text-align:left;border-bottom:1px solid #eaeef2;font-size:14px"';

  const byStateRows = states
    .map(
      ([code, s]) =>
        `<tr><td ${tdL}>${esc(label(code))} (${code})</td><td ${td}>${comma(s.total)}</td><td ${td}>${pct(s.total, totalVenues)}%</td></tr>`
    )
    .join("");

  const coverageRows = states
    .map(
      ([code, s]) =>
        `<tr><td ${tdL}>${esc(label(code))} (${code})</td>` +
        `<td ${td}>${comma(s.total)}</td>` +
        `<td ${td}>${pct(s.ge10, s.total)}% (${comma(s.ge10)})</td>` +
        `<td ${td}>${pct(s.ge1, s.total)}% (${comma(s.ge1)})</td>` +
        `<td ${td}>${pct(s.eq0, s.total)}% (${comma(s.eq0)})</td>` +
        `<td ${td}>${pct(s.noUrl, s.total)}% (${comma(s.noUrl)})</td>` +
        `<td ${td}>${pct(s.urlButZero, s.total)}% (${comma(s.urlButZero)})</td></tr>`
    )
    .join("");

  const html =
    `<div style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;color:#1a1a1a;max-width:720px">` +
    `<h2 style="margin:0 0 4px">🏛 Venue State of the Union</h2>` +
    `<p style="margin:0 0 20px;color:#57606a">${esc(TODAY)} · <strong>${comma(totalVenues)}</strong> active venues across ${states.length} states</p>` +
    `<h3 style="margin:0 0 8px">Venues by state</h3>` +
    `<table style="border-collapse:collapse;margin-bottom:24px"><thead><tr>` +
    `<th ${thL}>State</th><th ${th}>Venues</th><th ${th}>Share</th></tr></thead><tbody>${byStateRows}</tbody></table>` +
    `<h3 style="margin:0 0 4px">Per-state coverage</h3>` +
    `<p style="margin:0 0 8px;color:#57606a;font-size:13px">Event match is by venue name within state; “URL + 0 events” = a crawl target that yields nothing (the content gap).</p>` +
    `<table style="border-collapse:collapse"><thead><tr>` +
    `<th ${thL}>State</th><th ${th}>Venues</th><th ${th}>10+ events</th><th ${th}>1+ events</th>` +
    `<th ${th}>0 events</th><th ${th}>No events URL</th><th ${th}>URL + 0 events</th></tr></thead><tbody>${coverageRows}</tbody></table>` +
    `</div>`;

  const subject = `🏛 Venue State of the Union — ${TODAY} · ${comma(totalVenues)} venues`;

  fs.writeFileSync("sotu_email.html", html);
  fs.writeFileSync("sotu_subject.txt", subject);
  console.log(`\nEmail digest written (sotu_email.html, sotu_subject.txt): "${subject}"`);
}

main().catch((e) => {
  console.error(e.stack || e.message);
  process.exit(1);
});
