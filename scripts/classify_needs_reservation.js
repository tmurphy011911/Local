#!/usr/bin/env node
/**
 * Classify dining venues' needs_reservation flag by looking up each one in
 * Google Places and reading the `reservable` field.
 *
 *   true  -> Google says this venue takes reservations
 *   false -> walk-in only
 *   null  -> Places didn't return the venue, or reservable was undefined
 *
 * Targets dining venues where needs_reservation IS NULL (haven't been classified
 * yet). Skips anything already set to true/false.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/classify_needs_reservation.js --dry-run
 *   SUPABASE_SERVICE_KEY=ey... node scripts/classify_needs_reservation.js
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

const DRY_RUN = process.argv.includes("--dry-run");
const LIMIT_ARG = process.argv.find((a) => a.startsWith("--limit="));
const LIMIT = LIMIT_ARG ? parseInt(LIMIT_ARG.split("=")[1], 10) : null;

const DINING_CATEGORIES = [
  "restaurant", "bar", "cafe", "dining",
  "food", "pub", "nightlife",
];

async function supabaseGet(url, key) {
  const res = await fetch(url, { headers: { apikey: key, Authorization: `Bearer ${key}` } });
  return res.json();
}

async function supabasePatch(table, id, data, key) {
  const url = `${SUPABASE_URL}/rest/v1/${table}?id=eq.${id}`;
  const res = await fetch(url, {
    method: "PATCH",
    headers: {
      apikey: key,
      Authorization: `Bearer ${key}`,
      "Content-Type": "application/json",
      Prefer: "return=minimal",
    },
    body: JSON.stringify(data),
  });
  return res.ok;
}

async function placesLookup(apiKey, venue) {
  const textQuery = [venue.name, venue.city].filter(Boolean).join(" ");
  const body = {
    textQuery,
    maxResultCount: 1,
  };
  if (venue.lat != null && venue.lng != null) {
    body.locationBias = {
      circle: { center: { latitude: venue.lat, longitude: venue.lng }, radius: 500 },
    };
  }
  try {
    const res = await fetch("https://places.googleapis.com/v1/places:searchText", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Goog-Api-Key": apiKey,
        "X-Goog-FieldMask": "places.displayName,places.reservable",
      },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(10000),
    });
    if (!res.ok) return { error: `${res.status}` };
    const d = await res.json();
    const p = d.places?.[0];
    if (!p) return { missing: true };
    return { reservable: typeof p.reservable === "boolean" ? p.reservable : null };
  } catch (e) {
    return { error: String(e).slice(0, 80) };
  }
}

async function main() {
  const secrets = await getSecrets(["GOOGLE_PLACES_API_KEY"]);
  if (!secrets.GOOGLE_PLACES_API_KEY) {
    console.error("Missing GOOGLE_PLACES_API_KEY");
    process.exit(1);
  }
  const placesKey = secrets.GOOGLE_PLACES_API_KEY;
  const supaKey = secrets.SUPABASE_SERVICE_KEY;

  const catFilter = DINING_CATEGORIES.map((c) => `category.eq.${c}`).join(",");
  const limitClause = LIMIT ? `&limit=${LIMIT}` : "";
  const venues = await supabaseGet(
    `${SUPABASE_URL}/rest/v1/venues?select=id,name,city,state,lat,lng,category&or=(${catFilter})&is_active=eq.true&needs_reservation=is.null${limitClause}`,
    supaKey,
  );
  console.log(`Candidates (dining, needs_reservation IS NULL): ${venues.length}${DRY_RUN ? " — DRY RUN" : ""}`);

  let trueCount = 0, falseCount = 0, unknownCount = 0, errorCount = 0;
  for (let i = 0; i < venues.length; i++) {
    const v = venues[i];
    const r = await placesLookup(placesKey, v);
    if (r.error) {
      errorCount++;
      console.log(`[${i + 1}/${venues.length}] ${v.name} -> error: ${r.error}`);
    } else if (r.missing) {
      unknownCount++;
      console.log(`[${i + 1}/${venues.length}] ${v.name} (${v.city}, ${v.state}) -> not in Places`);
    } else if (r.reservable === null) {
      unknownCount++;
      console.log(`[${i + 1}/${venues.length}] ${v.name} (${v.city}, ${v.state}) -> reservable undefined`);
    } else {
      if (r.reservable) trueCount++;
      else falseCount++;
      console.log(`[${i + 1}/${venues.length}] ${v.name} (${v.city}, ${v.state}) -> needs_reservation=${r.reservable}`);
      if (!DRY_RUN) await supabasePatch("venues", v.id, { needs_reservation: r.reservable }, supaKey);
    }
    // Keep under the 600/min soft cap — 150ms per call = 400 rpm.
    await new Promise((res) => setTimeout(res, 150));
  }

  console.log("");
  console.log("Done.");
  console.log(`  needs_reservation=true:  ${trueCount}`);
  console.log(`  needs_reservation=false: ${falseCount}`);
  console.log(`  unresolved (left null):  ${unknownCount}`);
  console.log(`  errors:                  ${errorCount}`);
}

main().catch((e) => {
  console.error("Fatal:", e);
  process.exit(1);
});
