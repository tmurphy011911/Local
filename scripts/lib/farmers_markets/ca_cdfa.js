/**
 * Source module: California certified farmers markets (CDFA).
 *
 * Reads a committed JSON snapshot (scripts/data/ca_cdfa_markets.json) produced
 * periodically by build_ca_cdfa_markets.py from the authoritative CDFA PDF
 * (https://www.cdfa.ca.gov/is/docs/CurrentMrktsCounty.pdf). The PDF has no clean
 * Node table parser and geocoding 700+ addresses every week would be wasteful,
 * so coords are baked into the snapshot. CA markets are stable — re-run the
 * Python step when CDFA publishes a new PDF.
 *
 * Times are Pacific (the shared builder stamps America/Los_Angeles via `tz`).
 */

const fs = require("fs");
const path = require("path");

const DATA = path.join(__dirname, "..", "..", "data", "ca_cdfa_markets.json");

async function fetchMarkets() {
  if (!fs.existsSync(DATA)) {
    console.error(`  CA CDFA: ${DATA} missing — run build_ca_cdfa_markets.py first.`);
    return [];
  }
  const markets = JSON.parse(fs.readFileSync(DATA, "utf8"));
  return markets
    .filter((m) => m.lat != null && m.lng != null)
    .map((m) => ({
      dedupePrefix: "ca_cdfa",
      source: "ca_cdfa_farmers_market",
      sourceId: m.id,
      tz: "America/Los_Angeles",
      name: m.name,
      lat: m.lat,
      lng: m.lng,
      address: m.address || null,
      city: m.city || null,
      state: "CA",
      zip: m.zip || null,
      website: null,
      bio: m.county ? `${m.county} County certified farmers market.` : null,
      imageUrl: null,
      programTags: [],
      occurrences: m.occurrences || [],
    }));
}

module.exports = { fetchMarkets, label: "CDFA (CA)" };
