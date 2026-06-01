const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

let TICKETMASTER_API_KEY, SUPABASE_SERVICE_KEY;

// Per-metro variables set during each iteration
let CURRENT_CITY, CURRENT_LAT, CURRENT_LNG, CURRENT_RADIUS;

// ── Classification segments to query separately (gets us past the 1000-result cap) ──
const SEGMENTS = [
  { name: "Music", id: "KZFzniwnSyZfZ7v7nJ" },
  { name: "Sports", id: "KZFzniwnSyZfZ7v7nE" },
  { name: "Arts & Theatre", id: "KZFzniwnSyZfZ7v7na" },
  { name: "Miscellaneous", id: "KZFzniwnSyZfZ7v7n1" },
];

// ── Category mapping ──

const SEGMENT_MAP = {
  music: "music",
  sports: "sports",
  "arts & theatre": "arts",
  film: "arts",
  miscellaneous: "other",
  comedy: "comedy",
  dance: "arts",
  "food & drink": "food",
  outdoor: "outdoors",
  nightlife: "nightlife",
};

const SPORTS_GENRES = [
  "hockey", "football", "baseball", "basketball", "soccer", "tennis",
  "boxing", "wrestling", "racing", "mma", "golf", "rugby", "lacrosse",
  "volleyball", "skating", "cricket", "motorsports", "equestrian",
];

function mapCategory(classifications) {
  if (!classifications || classifications.length === 0) return "other";
  const segment = (classifications[0].segment?.name || "").toLowerCase();
  const genre = (classifications[0].genre?.name || "").toLowerCase();
  const subGenre = (classifications[0].subGenre?.name || "").toLowerCase();

  if (segment && SEGMENT_MAP[segment]) return SEGMENT_MAP[segment];
  if (SPORTS_GENRES.some((s) => genre.includes(s) || subGenre.includes(s))) return "sports";
  if (genre && SEGMENT_MAP[genre]) return SEGMENT_MAP[genre];
  return "other";
}

// ── Subcategory detection (for venue-based filtering: museum, library, tour, theatre) ──

function detectSubcategory(tm) {
  const venueName = (tm._embedded?.venues?.[0]?.name || "").toLowerCase();
  const eventName = (tm.name || "").toLowerCase();
  const genre = (tm.classifications?.[0]?.genre?.name || "").toLowerCase();
  const subGenre = (tm.classifications?.[0]?.subGenre?.name || "").toLowerCase();

  // Venue-based
  if (venueName.includes("museum")) return "museum";
  if (venueName.includes("library")) return "library";
  if (venueName.includes("gallery") || venueName.includes("art center")) return "gallery";

  // Sports — MUST come before the generic `return genre` fallback below.
  // Ticketmaster files many minor-league / independent games (WooSox, MiLB,
  // indie soccer, etc.) under the "Miscellaneous" segment with a useless
  // genre.name of "Miscellaneous", which would otherwise land in `subcategory`
  // verbatim. The carousel sports pool gates on subcategory ∈ {sports, hockey,
  // baseball, ...} (carousels/index.ts), so "miscellaneous" makes the whole
  // team rail invisible. And because the daily upsert (resolution=merge-
  // duplicates) overwrites subcategory every run, it re-clobbers any classifier
  // or manual backfill that fixed it — the WooSox-rail-vanished regression.
  // Detect games by sport genre OR a "Team A vs/v/@ Team B" title and return a
  // real sport value: visible immediately, and stable across re-syncs.
  const sportGenre = SPORTS_GENRES.find((s) => genre.includes(s) || subGenre.includes(s));
  if (sportGenre) return sportGenre;
  // Single-letter " v " covers Ticketmaster's "New England Patriots v Buffalo
  // Bills" format; also "vs", "vs.", "v.", and "@" (e.g. "Yankees @ Red Sox").
  if (/\s(?:vs?\.?|@)\s/i.test(eventName)) return "sports";

  // Genre-based
  if (genre.includes("theatre") || genre.includes("theater") ||
      genre === "musical" || subGenre.includes("play") || subGenre.includes("musical"))
    return "theatre";

  // Guided/venue tours (not concert tours or comedy tours)
  const isConcertOrComedy = ["music", "rock", "pop", "country", "hip-hop", "r&b", "comedy", "blues", "jazz", "latin", "metal", "alternative", "classical"]
    .some((g) => genre.includes(g) || subGenre.includes(g));
  if (!isConcertOrComedy &&
      (eventName.includes("guided tour") || eventName.includes("walking tour") ||
       eventName.includes("tour of ") || eventName.includes("ghost tour") ||
       eventName.includes("food tour") || eventName.includes("history tour") ||
       eventName.includes("brewery tour") || eventName.includes("boat tour") ||
       (venueName.includes("tour") && !venueName.includes("tournament"))))
    return "tour";

  return genre || null;
}

// ── Helpers ──

function hashEvent(source, sourceId) {
  return `${source}:${sourceId}`;
}

function bestImage(images) {
  if (!images || images.length === 0) return null;
  const ranked = images
    .filter((img) => img.url)
    .sort((a, b) => {
      const aRatio = a.ratio === "16_9" ? 1 : 0;
      const bRatio = b.ratio === "16_9" ? 1 : 0;
      if (aRatio !== bRatio) return bRatio - aRatio;
      return (b.width || 0) - (a.width || 0);
    });
  return ranked[0]?.url || null;
}

function parsePrice(priceRanges) {
  if (!priceRanges || priceRanges.length === 0) return { min: null, max: null };
  const range = priceRanges[0];
  return {
    min: range.min != null ? range.min : null,
    max: range.max != null ? range.max : null,
  };
}

// Resolve a Ticketmaster start/end into an ISO timestamp + a time_known flag.
//
// Ticketmaster gives us three useful fields on dates.start:
//   - dateTime: full UTC ISO ("2026-05-02T23:00:00Z") — only present when both
//     date AND time are confirmed.
//   - localDate: "YYYY-MM-DD" in the venue's local timezone, always present.
//   - localTime: "HH:MM:SS" in venue local TZ, only when time is known.
//   - dateTBD / timeTBA / noSpecificTime: signals that one half is unknown.
//
// The previous implementation fell back to bare `localDate` when dateTime was
// missing, which Postgres interprets as midnight UTC — that renders as 8 PM
// the previous calendar day in Eastern. So a TBD-time Saturday game shows up
// as "Tomorrow 8 PM" on Friday. Fix: when only the date is known, anchor the
// timestamp at noon UTC so it stays on the right calendar day in any US tz,
// and emit time_known=false so the client renders "Time TBD" instead of "12 PM".
function resolveStart(dates) {
  const start = dates?.start;
  if (!start) return { iso: null, timeKnown: false };
  if (start.dateTime) return { iso: start.dateTime, timeKnown: true };
  if (!start.localDate) return { iso: null, timeKnown: false };
  return { iso: `${start.localDate}T12:00:00Z`, timeKnown: false };
}

// Translate Ticketmaster's dates.status.code to our (status, is_active) pair.
// Canceled/postponed/rescheduled events should NOT show in feeds — flip
// is_active=false so the upsert overwrites any prior "active" row. The
// status string is preserved for analytics + future "Postponed" UX.
function mapTicketmasterStatus(tm) {
  const code = (tm.dates?.status?.code || "").toLowerCase();
  switch (code) {
    case "canceled":
    case "cancelled":
      return { status: "canceled", isActive: false };
    case "postponed":
      return { status: "postponed", isActive: false };
    case "rescheduled":
      // Treat rescheduled as inactive until the event reappears with a
      // fresh start_at and onsale/offsale status — prevents stale dates
      // surfacing as "Happening now" before the new time is confirmed.
      return { status: "rescheduled", isActive: false };
    case "onsale":
    case "offsale":
    default:
      return { status: "active", isActive: true };
  }
}

function normalizeEvent(tm) {
  const venue = tm._embedded?.venues?.[0];
  const prices = parsePrice(tm.priceRanges);
  const { iso: startAt, timeKnown } = resolveStart(tm.dates);
  const endAt = tm.dates?.end?.dateTime || null;
  const { status, isActive } = mapTicketmasterStatus(tm);

  return {
    title: tm.name,
    description: tm.info || tm.description || tm.pleaseNote || tm._embedded?.attractions?.[0]?.description || null,
    category: mapCategory(tm.classifications),
    subcategory: detectSubcategory(tm),
    start_at: startAt,
    end_at: endAt,
    time_known: timeKnown,
    is_free: prices.min === 0 && prices.max === 0,
    price_min: prices.min,
    price_max: prices.max,
    ticket_url: tm.url || null,
    image_url: bestImage(tm.images),
    status,
    is_active: isActive,
    venue_name: venue?.name || null,
    venue_address: venue?.address?.line1 || null,
    venue_city: venue?.city?.name || CURRENT_CITY.charAt(0).toUpperCase() + CURRENT_CITY.slice(1),
    venue_state: venue?.state?.stateCode || null,
    venue_lat: venue?.location?.latitude
      ? parseFloat(venue.location.latitude)
      : null,
    venue_lng: venue?.location?.longitude
      ? parseFloat(venue.location.longitude)
      : null,
    source: "ticketmaster",
    source_id: tm.id,
    source_url: tm.url || null,
    confidence_score: 1.0,
    needs_review: false,
    dedupe_hash: hashEvent("ticketmaster", tm.id),
    city: CURRENT_CITY,
    last_synced_at: new Date().toISOString(),
    updated_at: new Date().toISOString(),
  };
}

// ── Date windows: split into monthly chunks so each (segment × month) can return up to 1000 ──

function buildDateWindows(months) {
  const windows = [];
  const now = new Date();
  for (let i = 0; i < months; i++) {
    const start = new Date(now);
    start.setMonth(start.getMonth() + i);
    if (i > 0) start.setDate(1); // first window starts now, rest start on the 1st
    const end = new Date(start);
    end.setMonth(end.getMonth() + 1);
    end.setDate(1); // end at start of next month
    windows.push({
      start: start.toISOString().replace(/\.\d+Z$/, "Z"),
      end: end.toISOString().replace(/\.\d+Z$/, "Z"),
      label: start.toLocaleString("en-US", { month: "short", year: "numeric" }),
    });
  }
  return windows;
}

async function fetchWindow(segment, window) {
  const events = [];
  let page = 0;
  const size = 200;

  while (true) {
    const url = new URL(
      "https://app.ticketmaster.com/discovery/v2/events.json"
    );
    url.searchParams.set("apikey", TICKETMASTER_API_KEY);
    url.searchParams.set("geoPoint", `${CURRENT_LAT},${CURRENT_LNG}`);
    url.searchParams.set("radius", CURRENT_RADIUS);
    url.searchParams.set("unit", "miles");
    url.searchParams.set("size", size.toString());
    url.searchParams.set("page", page.toString());
    url.searchParams.set("sort", "date,asc");
    url.searchParams.set("segmentId", segment.id);
    url.searchParams.set("startDateTime", window.start);
    url.searchParams.set("endDateTime", window.end);

    const res = await fetch(url.toString());
    if (!res.ok) {
      if (res.status === 429) {
        console.log("      Rate limited, waiting 2s...");
        await new Promise((r) => setTimeout(r, 2000));
        continue;
      }
      console.error(`      API error: ${res.status} ${res.statusText}`);
      break;
    }

    const data = await res.json();
    const batch = data._embedded?.events || [];
    events.push(...batch);

    const totalPages = data.page?.totalPages || 1;
    page++;
    if (page >= totalPages || page >= 5) break;

    await new Promise((r) => setTimeout(r, 220));
  }

  return events;
}

async function fetchAllEvents() {
  const seen = new Set();
  const allEvents = [];
  const windows = buildDateWindows(12); // 12 months of events

  for (const segment of SEGMENTS) {
    console.log(`\n${segment.name}:`);
    for (const window of windows) {
      const events = await fetchWindow(segment, window);
      let added = 0;
      for (const e of events) {
        if (!seen.has(e.id)) {
          seen.add(e.id);
          allEvents.push(e);
          added++;
        }
      }
      if (events.length > 0) {
        console.log(`  ${window.label}: ${events.length} fetched, ${added} new`);
      }
      await new Promise((r) => setTimeout(r, 250));
    }
  }

  console.log(`\nTotal unique events: ${allEvents.length}`);
  return allEvents;
}

async function upsertToSupabase(rows) {
  const url = `${SUPABASE_URL}/rest/v1/events?on_conflict=dedupe_hash`;
  const batchSize = 50;
  let inserted = 0;
  let errors = 0;

  for (let i = 0; i < rows.length; i += batchSize) {
    const batch = rows.slice(i, i + batchSize);
    const res = await fetch(url, {
      method: "POST",
      headers: {
        apikey: SUPABASE_SERVICE_KEY,
        Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
        "Content-Type": "application/json",
        Prefer: "resolution=merge-duplicates",
      },
      body: JSON.stringify(batch),
    });

    if (!res.ok) {
      const body = await res.text();
      console.error(`Supabase upsert error (batch ${i / batchSize}): ${res.status} ${body}`);
      errors += batch.length;
    } else {
      inserted += batch.length;
    }
  }

  return { inserted, errors };
}

/**
 * Catch events Ticketmaster *removed* from the API after our last sync.
 * Some cancellations are signaled via dates.status.code (handled in
 * normalizeEvent). Others — silent removals — never reach us; the row
 * sits "active" in our DB until the daily past-events sweep finally
 * deactivates it after start_at passes. That left a Celtics game still
 * marked "Happening now" a week after it was canceled.
 *
 * Strategy: after a successful sync, deactivate ticketmaster rows for
 * this city whose start_at is still in the future, were active before,
 * and were NOT seen in this run's source_id set. Sanity-gated on a
 * minimum seen-count so a transient TM outage doesn't wipe the catalog.
 */
async function deactivateMissingTicketmasterEvents(seenSourceIds, syncStartedAt) {
  const MIN_SEEN_FOR_SAFETY = 100;
  if (seenSourceIds.size < MIN_SEEN_FOR_SAFETY) {
    console.log(
      `  Skipping fell-off-API sweep: only ${seenSourceIds.size} ids seen (< ${MIN_SEEN_FOR_SAFETY}); likely partial fetch.`
    );
    return { deactivated: 0 };
  }
  // Page through future TM events for this city that weren't refreshed
  // this run. last_synced_at is touched on every upsert, so the run
  // start time is the cleanest "stale" boundary.
  const PAGE = 500;
  let deactivated = 0;
  while (true) {
    const url =
      `${SUPABASE_URL}/rest/v1/events` +
      `?select=id,source_id` +
      `&source=eq.ticketmaster` +
      `&city=eq.${encodeURIComponent(CURRENT_CITY)}` +
      `&is_active=eq.true` +
      `&start_at=gte.${encodeURIComponent(new Date().toISOString())}` +
      `&last_synced_at=lt.${encodeURIComponent(syncStartedAt)}` +
      `&order=start_at.asc` +
      `&limit=${PAGE}`;
    const res = await fetch(url, {
      headers: {
        apikey: SUPABASE_SERVICE_KEY,
        Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      },
    });
    if (!res.ok) {
      console.error(`  Sweep fetch failed: ${res.status} ${await res.text()}`);
      break;
    }
    const rows = await res.json();
    if (rows.length === 0) break;
    // Defensive: don't deactivate ids we DID see this run (race against a
    // partial successful sync of the same metro).
    const stale = rows.filter((r) => !seenSourceIds.has(r.source_id));
    if (stale.length === 0 && rows.length < PAGE) break;
    if (stale.length > 0) {
      const ids = stale.map((r) => r.id);
      const patch = await fetch(
        `${SUPABASE_URL}/rest/v1/events?id=in.(${ids.join(",")})`,
        {
          method: "PATCH",
          headers: {
            apikey: SUPABASE_SERVICE_KEY,
            Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
            "Content-Type": "application/json",
            Prefer: "return=minimal",
          },
          body: JSON.stringify({ is_active: false, status: "canceled" }),
        }
      );
      if (!patch.ok) {
        console.error(`  Sweep patch failed: ${patch.status} ${await patch.text()}`);
        break;
      }
      deactivated += stale.length;
    }
    if (rows.length < PAGE) break;
  }
  return { deactivated };
}

async function fetchActiveMetros() {
  const res = await fetch(
    `${SUPABASE_URL}/rest/v1/metros?is_active=eq.true&select=*`,
    {
      headers: {
        apikey: SUPABASE_SERVICE_KEY,
        Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      },
    }
  );

  if (!res.ok) {
    const body = await res.text();
    throw new Error(`Failed to fetch metros: ${res.status} ${body}`);
  }

  return res.json();
}

async function main() {
  const secrets = await getSecrets(["TICKETMASTER_API_KEY"]);
  TICKETMASTER_API_KEY = secrets.TICKETMASTER_API_KEY;
  SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;

  console.log("=== Ticketmaster -> Supabase Sync ===\n");

  // Fetch all active metros
  const metros = await fetchActiveMetros();
  console.log(`Found ${metros.length} active metro(s): ${metros.map((m) => m.name).join(", ")}\n`);

  if (metros.length === 0) {
    console.log("No active metros found. Run seed_metros.js first.");
    return;
  }

  let grandTotal = { fetched: 0, valid: 0, upserted: 0, skipped: 0, errors: 0 };

  for (const metro of metros) {
    // Set per-metro variables
    CURRENT_CITY = metro.name.toLowerCase();
    CURRENT_LAT = metro.lat.toString();
    CURRENT_LNG = metro.lng.toString();
    CURRENT_RADIUS = (metro.radius_miles || 75).toString();

    console.log(`\n${"=".repeat(50)}`);
    console.log(`Syncing metro: ${metro.name} (${CURRENT_LAT}, ${CURRENT_LNG}, ${CURRENT_RADIUS}mi)`);
    console.log(`${"=".repeat(50)}`);

    // Stamp this metro's run start so the post-sync sweep can find rows
    // whose last_synced_at is older than this run (i.e. fell off the API).
    const syncStartedAt = new Date().toISOString();

    // 1. Fetch from Ticketmaster (per segment x monthly windows for max coverage)
    const tmEvents = await fetchAllEvents();

    if (tmEvents.length === 0) {
      console.log(`No events found for ${metro.name}. Skipping.`);
      continue;
    }

    // 2. Normalize
    const rows = tmEvents.map(normalizeEvent);

    const valid = rows.filter((r) => r.start_at);
    const skipped = rows.length - valid.length;
    console.log(`Normalized: ${valid.length} valid, ${skipped} skipped (no date)\n`);

    // Status breakdown so canceled/postponed counts are visible in CI logs.
    const statusCounts = valid.reduce((acc, r) => {
      acc[r.status] = (acc[r.status] || 0) + 1;
      return acc;
    }, {});
    if (Object.keys(statusCounts).length > 1) {
      console.log("  Status:", statusCounts);
    }

    // 3. Upsert to Supabase
    const { inserted, errors } = await upsertToSupabase(valid);

    // 4. Sweep: deactivate TM rows for this city that weren't refreshed
    //    this run. Catches silent removals (canceled events TM dropped from
    //    search). Sanity-gated by seen-id count inside the sweep.
    const seenSourceIds = new Set(valid.map((r) => r.source_id));
    const { deactivated } = await deactivateMissingTicketmasterEvents(
      seenSourceIds,
      syncStartedAt,
    );
    if (deactivated > 0) {
      console.log(`  Fell-off-API sweep: deactivated ${deactivated} stale rows.`);
    }

    // 4. Per-metro summary
    console.log(`\n--- ${metro.name} Summary ---`);
    console.log(`  Fetched:   ${tmEvents.length}`);
    console.log(`  Valid:     ${valid.length}`);
    console.log(`  Upserted:  ${inserted}`);
    console.log(`  Skipped:   ${skipped}`);
    console.log(`  Errors:    ${errors}`);

    grandTotal.fetched += tmEvents.length;
    grandTotal.valid += valid.length;
    grandTotal.upserted += inserted;
    grandTotal.skipped += skipped;
    grandTotal.errors += errors;

    // Category breakdown
    const cats = {};
    valid.forEach((r) => {
      cats[r.category] = (cats[r.category] || 0) + 1;
    });
    console.log("\n  By category:");
    Object.entries(cats)
      .sort((a, b) => b[1] - a[1])
      .forEach(([cat, count]) => {
        console.log(`    ${cat}: ${count}`);
      });

    // Subcategory breakdown
    const subs = {};
    valid.forEach((r) => {
      if (r.subcategory) {
        subs[r.subcategory] = (subs[r.subcategory] || 0) + 1;
      }
    });
    console.log("\n  Top subcategories:");
    Object.entries(subs)
      .sort((a, b) => b[1] - a[1])
      .slice(0, 15)
      .forEach(([sub, count]) => {
        console.log(`    ${sub}: ${count}`);
      });
  }

  // Grand total
  console.log(`\n${"=".repeat(50)}`);
  console.log("=== Grand Total ===");
  console.log(`  Metros synced: ${metros.length}`);
  console.log(`  Fetched:   ${grandTotal.fetched}`);
  console.log(`  Valid:     ${grandTotal.valid}`);
  console.log(`  Upserted:  ${grandTotal.upserted}`);
  console.log(`  Skipped:   ${grandTotal.skipped}`);
  console.log(`  Errors:    ${grandTotal.errors}`);
}

main().catch((err) => {
  console.error("Fatal error:", err);
  process.exit(1);
});
