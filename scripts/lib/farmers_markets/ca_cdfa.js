/**
 * Source module: California Department of Food & Agriculture (CDFA) certified
 * farmers' market directory.
 *
 * STATUS: stub. The real CDFA directory is the canonical source for CA markets
 * but pulling it requires fetching their interactive directory (see
 * https://www.cdfa.ca.gov/is/i_&_c/cfm.html) and the corresponding county-level
 * pages, then parsing. That implementation is not yet written.
 *
 * Returning [] keeps `sync_farmers_markets.js` healthy (it previously failed
 * with `Cannot find module './lib/farmers_markets/ca_cdfa'` because this file
 * was referenced before it existed). Once CA goes live in production and we
 * actually want California farmers' markets surfaced, replace this stub with a
 * real fetchMarkets implementation modeled after marketspread.js / visitri.js.
 *
 * Required output shape (per sync_farmers_markets.js builder) — for the eventual
 * implementation:
 *   {
 *     dedupePrefix, source, sourceId, tz,
 *     name, lat, lng, address, city, state: "CA", zip,
 *     website, bio, imageUrl, programTags, occurrences,
 *   }
 *
 * CA is Pacific (tz America/Los_Angeles).
 */

async function fetchMarkets() {
  // Intentional no-op until the CDFA directory crawler is implemented.
  return [];
}

module.exports = { fetchMarkets, label: "ca_cdfa (CA, stub)" };
