/**
 * venue_health_snapshot.js — detect venues that SILENTLY go dark.
 *
 * The problem: a venue can stop producing events without any hard error. Its
 * calendar moves to a JS widget or a new URL, the crawler still gets 200 OK on
 * the old page, so crawl_success_rate stays healthy while the venue quietly
 * yields zero events (e.g. ICA Boston). Nothing flags it today.
 *
 * The signal: compare each venue's CURRENT count of active upcoming events
 * against its OWN recent baseline. A venue that reliably carried a stream of
 * upcoming events and now carries ~none has silently failed.
 *
 * How it runs (weekly, after the crawl→classify→dedupe chain finishes):
 *   1. Load watched venues (public_interest_rank >= MIN_RANK, default 25).
 *   2. Count each venue's active upcoming events (is_active=true,
 *      start_at >= today) by venue_name + state — the SAME name-match the rest
 *      of the pipeline uses (there is no events.venue_id FK; see
 *      match_event_venues.js / venue_state_of_the_union.js).
 *   3. Write one snapshot row per watched venue to venue_event_snapshots.
 *   4. For each venue, pull its prior snapshots, take the median of the trailing
 *      BASELINE_WEEKS as its baseline, and flag it "dark" when that baseline was
 *      healthy (>= MIN_BASELINE) but today's count collapsed to <= baseline *
 *      DROP_RATIO. Only venues that are NEWLY dark this run (had events last
 *      run) go in the email — so you're not re-paged weekly for the same venue.
 *
 * Output:
 *   - Rich Markdown to $GITHUB_STEP_SUMMARY (Actions run page).
 *   - venue_alert_email.html + venue_alert_subject.txt (the workflow emails
 *     these ONLY when there is >= 1 newly-dark venue).
 *   - has_alerts / alert_count to $GITHUB_OUTPUT for the conditional send.
 *
 * Warm-up: stateful detection needs history. A venue is only evaluated once it
 * has >= MIN_HISTORY prior snapshots, so the first ~2 weekly runs collect
 * baseline and flag nothing. That is expected, not a bug.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/venue_health_snapshot.js
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

// Tunables (env-overridable).
const MIN_RANK = parseInt(process.env.MIN_RANK || "25", 10); // watch these venues
const BASELINE_WEEKS = parseInt(process.env.BASELINE_WEEKS || "4", 10); // trailing snapshots for baseline
const MIN_HISTORY = parseInt(process.env.MIN_HISTORY || "2", 10); // prior snapshots required before we judge
const MIN_BASELINE = parseInt(process.env.MIN_BASELINE || "3", 10); // baseline must be a real stream to count
const DROP_RATIO = parseFloat(process.env.DROP_RATIO || "0.2"); // "collapsed" = today <= baseline*ratio
const DRY_RUN = process.env.DRY_RUN === "1"; // skip the snapshot write (local testing)

const TODAY = new Date().toISOString().slice(0, 10); // YYYY-MM-DD (runner injects the date)

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

async function insertRows(table, rows) {
  // PostgREST caps payloads; chunk to stay well under it.
  const CHUNK = 500;
  for (let i = 0; i < rows.length; i += CHUNK) {
    const slice = rows.slice(i, i + CHUNK);
    const res = await fetch(`${SUPABASE_URL}/rest/v1/${table}`, {
      method: "POST",
      headers: { ...HEADERS, "Content-Type": "application/json", Prefer: "return=minimal" },
      body: JSON.stringify(slice),
    });
    if (!res.ok) {
      const body = await res.text();
      throw new Error(`Insert into ${table} failed (${res.status}): ${body.slice(0, 200)}`);
    }
  }
}

const norm = (s) => (s || "").trim().toLowerCase();
const comma = (n) => n.toLocaleString("en-US");
const esc = (s) =>
  String(s == null ? "" : s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

function median(nums) {
  if (nums.length === 0) return 0;
  const s = [...nums].sort((a, b) => a - b);
  const mid = Math.floor(s.length / 2);
  return s.length % 2 ? s[mid] : (s[mid - 1] + s[mid]) / 2;
}

const daysAgo = (iso) =>
  iso ? Math.floor((Date.parse(`${TODAY}T00:00:00Z`) - Date.parse(iso)) / 86400000) : null;

async function main() {
  console.log(`Venue health snapshot for ${TODAY} — watching public_interest_rank >= ${MIN_RANK}.`);

  // 1. Watched venues.
  const venues = await fetchAll(
    `venues?is_active=eq.true&public_interest_rank=gte.${MIN_RANK}` +
      `&select=id,name,state,public_interest_rank,events_url,last_crawled_at,crawl_success_rate&`
  );

  // 2. Active upcoming events, counted by state|normalized-name (pipeline convention).
  const events = await fetchAll(
    `events?is_active=eq.true&start_at=gte.${TODAY}T00:00:00Z&select=venue_name,venue_state&`
  );
  const eventCounts = new Map();
  for (const ev of events) {
    const key = `${ev.venue_state || ""}|${norm(ev.venue_name)}`;
    eventCounts.set(key, (eventCounts.get(key) || 0) + 1);
  }

  console.log(`Loaded ${comma(venues.length)} watched venues, ${comma(events.length)} active upcoming events.`);

  const todayCount = new Map(); // venue.id -> upcoming count today
  const snapshotRows = [];
  for (const v of venues) {
    const n = eventCounts.get(`${v.state || ""}|${norm(v.name)}`) || 0;
    todayCount.set(v.id, n);
    snapshotRows.push({
      venue_id: v.id,
      venue_name: v.name,
      venue_state: v.state || null,
      public_interest_rank: v.public_interest_rank ?? null,
      upcoming_count: n,
      snapshot_date: TODAY,
    });
  }

  // 3. Write this week's snapshot (before detection, so today's row exists in history going forward).
  if (DRY_RUN) {
    console.log(`DRY_RUN=1 — skipping write of ${comma(snapshotRows.length)} snapshot rows.`);
  } else {
    await insertRows("venue_event_snapshots", snapshotRows);
    console.log(`Wrote ${comma(snapshotRows.length)} snapshot rows.`);
  }

  // 4. Pull prior snapshots (strictly before today) to build each venue's baseline.
  const lookbackDays = (BASELINE_WEEKS + 2) * 7;
  const since = new Date(Date.parse(`${TODAY}T00:00:00Z`) - lookbackDays * 86400000)
    .toISOString()
    .slice(0, 10);
  const prior = await fetchAll(
    `venue_event_snapshots?snapshot_date=gte.${since}&snapshot_date=lt.${TODAY}` +
      `&select=venue_id,upcoming_count,snapshot_date&order=venue_id,snapshot_date.desc&`
  );
  const priorByVenue = new Map(); // venue_id -> [counts], newest first
  for (const r of prior) {
    if (!priorByVenue.has(r.venue_id)) priorByVenue.set(r.venue_id, []);
    priorByVenue.get(r.venue_id).push(r.upcoming_count);
  }

  // Classify each venue.
  const newlyDark = [];
  let stillDark = 0;
  let evaluated = 0;
  for (const v of venues) {
    const history = priorByVenue.get(v.id) || [];
    if (history.length < MIN_HISTORY) continue; // still warming up
    evaluated++;

    const baseline = median(history.slice(0, BASELINE_WEEKS));
    if (baseline < MIN_BASELINE) continue; // never carried a real stream — not a "failure"

    const threshold = Math.floor(baseline * DROP_RATIO);
    const now = todayCount.get(v.id) || 0;
    const isDarkNow = now <= threshold;
    if (!isDarkNow) continue;

    const prevCount = history[0]; // last run
    const wasDarkLast = prevCount <= threshold;
    if (wasDarkLast) {
      stillDark++;
      continue; // already reported in a prior week — don't re-page
    }

    newlyDark.push({
      name: v.name,
      state: v.state || "??",
      rank: v.public_interest_rank ?? "—",
      baseline,
      now,
      prevCount,
      lastCrawledDays: daysAgo(v.last_crawled_at),
      successRate: v.crawl_success_rate,
      eventsUrl: v.events_url || "",
    });
  }

  newlyDark.sort((a, b) => b.baseline - a.baseline);

  console.log(
    `Evaluated ${comma(evaluated)} venues with history; ${newlyDark.length} newly dark, ${stillDark} still dark.`
  );

  // ── Markdown summary (always) ──
  const md = [];
  md.push(`# 🩺 Venue health — ${TODAY}`);
  md.push("");
  md.push(
    `Watching **${comma(venues.length)}** venues (rank ≥ ${MIN_RANK}); ` +
      `**${comma(evaluated)}** have enough history to judge. ` +
      `Baseline = median of trailing ${BASELINE_WEEKS} weeks; a venue is "dark" when a baseline ≥ ${MIN_BASELINE} ` +
      `collapses to ≤ ${Math.round(DROP_RATIO * 100)}% of it.`
  );
  md.push("");
  if (evaluated === 0) {
    md.push(
      `_Warm-up: no venue has ${MIN_HISTORY}+ prior snapshots yet, so nothing can be flagged. ` +
        `Detection becomes live after ~${MIN_HISTORY} more weekly runs._`
    );
  } else if (newlyDark.length === 0) {
    md.push(`✅ **No venue newly went dark this week.** (${stillDark} still dark from earlier weeks.)`);
  } else {
    md.push(`🚨 **${newlyDark.length} venue(s) newly went dark this week** (${stillDark} still dark from earlier weeks):`);
    md.push("");
    md.push("| Venue | State | Rank | Baseline | Last wk | Now | Last crawled | Success | Events URL |");
    md.push("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |");
    for (const d of newlyDark) {
      const crawled = d.lastCrawledDays == null ? "never" : `${d.lastCrawledDays}d ago`;
      const rate = d.successRate == null ? "—" : d.successRate.toFixed(2);
      const url = d.eventsUrl ? `[link](${d.eventsUrl})` : "—";
      md.push(
        `| ${d.name} | ${d.state} | ${d.rank} | ${d.baseline} | ${d.prevCount} | ${d.now} | ${crawled} | ${rate} | ${url} |`
      );
    }
  }
  const markdown = md.join("\n") + "\n";
  if (process.env.GITHUB_STEP_SUMMARY) fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, markdown);
  console.log("\n" + markdown);

  // ── Email body + GH outputs (only when there is something to report) ──
  const hasAlerts = newlyDark.length > 0;
  if (hasAlerts) {
    const rowsHtml = newlyDark
      .map((d) => {
        const crawled = d.lastCrawledDays == null ? "never" : `${d.lastCrawledDays}d ago`;
        const rate = d.successRate == null ? "—" : d.successRate.toFixed(2);
        const url = d.eventsUrl
          ? `<a href="${esc(d.eventsUrl)}">${esc(d.eventsUrl)}</a>`
          : "—";
        return (
          `<tr>` +
          `<td style="padding:6px 10px;border-bottom:1px solid #eee;"><b>${esc(d.name)}</b></td>` +
          `<td style="padding:6px 10px;border-bottom:1px solid #eee;">${esc(d.state)}</td>` +
          `<td style="padding:6px 10px;border-bottom:1px solid #eee;text-align:right;">${esc(d.rank)}</td>` +
          `<td style="padding:6px 10px;border-bottom:1px solid #eee;text-align:right;">${d.baseline} → <b>${d.now}</b></td>` +
          `<td style="padding:6px 10px;border-bottom:1px solid #eee;text-align:right;">${esc(crawled)}</td>` +
          `<td style="padding:6px 10px;border-bottom:1px solid #eee;text-align:right;">${esc(rate)}</td>` +
          `<td style="padding:6px 10px;border-bottom:1px solid #eee;word-break:break-all;">${url}</td>` +
          `</tr>`
        );
      })
      .join("\n");

    const html =
      `<div style="font-family:-apple-system,Segoe UI,Roboto,sans-serif;color:#222;max-width:760px;">` +
      `<h2 style="margin:0 0 4px;">🩺 ${newlyDark.length} venue(s) went quiet this week</h2>` +
      `<p style="color:#666;margin:0 0 16px;">As of ${TODAY}. These venues reliably carried upcoming events ` +
      `and just collapsed to ~none — a silent failure worth checking (calendar moved, URL changed, or parser broke).</p>` +
      `<table style="border-collapse:collapse;font-size:14px;width:100%;">` +
      `<thead><tr style="text-align:left;background:#f6f6f6;">` +
      `<th style="padding:6px 10px;">Venue</th><th style="padding:6px 10px;">State</th>` +
      `<th style="padding:6px 10px;text-align:right;">Rank</th>` +
      `<th style="padding:6px 10px;text-align:right;">Baseline → now</th>` +
      `<th style="padding:6px 10px;text-align:right;">Last crawled</th>` +
      `<th style="padding:6px 10px;text-align:right;">Success</th>` +
      `<th style="padding:6px 10px;">Events URL</th>` +
      `</tr></thead><tbody>${rowsHtml}</tbody></table>` +
      `<p style="color:#999;font-size:12px;margin-top:16px;">Watching ${comma(venues.length)} venues (rank ≥ ${MIN_RANK}); ` +
      `${stillDark} others remain dark from earlier weeks (not re-listed). Full report in the GitHub Actions run summary.</p>` +
      `</div>`;

    fs.writeFileSync("venue_alert_email.html", html);
    fs.writeFileSync(
      "venue_alert_subject.txt",
      `⚠️ ${newlyDark.length} venue(s) went quiet this week — Local`
    );
    console.log(`Wrote venue_alert_email.html (${newlyDark.length} venues).`);
  }

  if (process.env.GITHUB_OUTPUT) {
    fs.appendFileSync(
      process.env.GITHUB_OUTPUT,
      `has_alerts=${hasAlerts ? "true" : "false"}\nalert_count=${newlyDark.length}\n`
    );
  }
}

main().catch((e) => {
  console.error(e.stack || e.message);
  process.exit(1);
});
