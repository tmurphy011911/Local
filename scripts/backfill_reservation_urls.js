#!/usr/bin/env node
/**
 * Backfill reservation_url for dining venues.
 * Checks OpenTable and Resy for each restaurant/bar/cafe that doesn't have a reservation_url yet.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node backfill_reservation_urls.js
 *   SUPABASE_SERVICE_KEY=ey... node backfill_reservation_urls.js --dry-run
 */

const SUPABASE_URL = "https://blifubxslaucshmlphdc.supabase.co";
const SUPABASE_KEY = process.env.SUPABASE_SERVICE_KEY;
const DRY_RUN = process.argv.includes("--dry-run");

if (!SUPABASE_KEY) {
  console.error("SUPABASE_SERVICE_KEY env var required");
  process.exit(1);
}

async function supabaseGet(table, query) {
  const url = `${SUPABASE_URL}/rest/v1/${table}?${query}`;
  const res = await fetch(url, {
    headers: {
      apikey: SUPABASE_KEY,
      Authorization: `Bearer ${SUPABASE_KEY}`,
    },
  });
  return res.json();
}

async function supabaseUpdate(table, id, data) {
  const url = `${SUPABASE_URL}/rest/v1/${table}?id=eq.${id}`;
  const res = await fetch(url, {
    method: "PATCH",
    headers: {
      apikey: SUPABASE_KEY,
      Authorization: `Bearer ${SUPABASE_KEY}`,
      "Content-Type": "application/json",
      Prefer: "return=minimal",
    },
    body: JSON.stringify(data),
  });
  return res.ok;
}

function slugify(text) {
  return text
    .toLowerCase()
    .replace(/['']/g, "")           // remove apostrophes
    .replace(/[^a-z0-9\s-]/g, "")   // remove special chars
    .replace(/\s+/g, "-")           // spaces to hyphens
    .replace(/-+/g, "-")            // collapse multiple hyphens
    .replace(/^-|-$/g, "");         // trim leading/trailing hyphens
}

async function checkUrl(url) {
  try {
    const res = await fetch(url, {
      method: "GET",
      redirect: "follow",
      signal: AbortSignal.timeout(8000),
      headers: { "User-Agent": "Mozilla/5.0" },
    });
    if (!res.ok) return false;

    // OpenTable redirects to search page if not found
    if (url.includes("opentable.com") && res.url.includes("/s?")) return false;

    // Resy serves 200 for not-found pages — check the body
    if (url.includes("resy.com")) {
      const body = await res.text();
      if (body.includes("Sorry") || body.includes("can't find") || body.includes("couldn't find") || body.includes("page not found") || body.includes("404")) {
        return false;
      }
      // Valid Resy pages have the venue name in meta tags or the booking widget
      if (!body.includes("resy__booking") && !body.includes("og:title")) {
        return false;
      }
    }

    return true;
  } catch (_) {
    return false;
  }
}

async function main() {
  console.log(`Backfilling reservation URLs${DRY_RUN ? " (DRY RUN)" : ""}...`);

  // Fetch all dining venues without reservation_url
  const venues = await supabaseGet(
    "venues",
    "select=id,name,city,state,category&or=(category.eq.restaurant,category.eq.bar,category.eq.cafe,category.eq.dining)&reservation_url=is.null&is_active=eq.true&order=name"
  );

  console.log(`Found ${venues.length} dining venues without reservation_url`);

  let found = 0;
  let checked = 0;

  for (const venue of venues) {
    checked++;
    const nameSlug = slugify(venue.name);
    const citySlug = slugify(venue.city);
    const stateSlug = (venue.state || "MA").toLowerCase();

    // Check OpenTable (HEAD request + redirect check — reliable)
    const otUrl = `https://www.opentable.com/r/${nameSlug}-${citySlug}`;
    const otOk = await checkUrl(otUrl);

    if (otOk) {
      found++;
      console.log(`[${checked}/${venues.length}] ${venue.name} -> OpenTable: ${otUrl}`);
      if (!DRY_RUN) await supabaseUpdate("venues", venue.id, { reservation_url: otUrl });
      continue;
    }

    // Resy is a React SPA — returns 200 for all URLs, can't distinguish valid from invalid
    // server-side. Skipping automated Resy detection until we have API access.

    if (checked % 50 === 0) {
      console.log(`[${checked}/${venues.length}] checked... (${found} found so far)`);
    }

    // Small delay to avoid rate limiting
    await new Promise((r) => setTimeout(r, 200));
  }

  console.log(`\nDone. Found reservation URLs for ${found}/${venues.length} venues.`);
}

main().catch((e) => {
  console.error("Fatal:", e);
  process.exit(1);
});
