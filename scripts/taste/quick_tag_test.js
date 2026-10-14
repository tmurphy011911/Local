// Throwaway: synchronous tag eyeball test. Fetches a few events, tags them with
// Haiku via the Messages API (NOT the batch queue), prints results. No writes.
const { SUPABASE_URL } = require("../lib/secrets");
const { TAG_FACETS, TAG_MIN, TAG_MAX } = require("./tag_vocabulary");

const SVC = process.env.SUPABASE_SERVICE_KEY;
const AK = process.env.ANTHROPIC_API_KEY;
const N = parseInt(process.env.N || "6", 10);

async function main() {
  const now = new Date().toISOString();
  const subcatFilter = process.env.SUBCAT ? `&subcategory=eq.${process.env.SUBCAT}` : "";
  const url = `${SUPABASE_URL}/rest/v1/events?is_active=eq.true&start_at=gte.${now}${subcatFilter}` +
    `&description=not.is.null&select=id,title,description,venue_name,venue_city,is_free` +
    `&order=start_at.asc&limit=${N}`;
  const evs = await (await fetch(url, { headers: { apikey: SVC, Authorization: `Bearer ${SVC}` } })).json();
  console.log(`Fetched ${evs.length} events to tag.\n`);

  const facetLines = Object.entries(TAG_FACETS).map(([f, t]) => `   • ${f}: ${t.join(", ")}`).join("\n");
  const prompt = `Tag each event below for a local-events app.
For each: choose ${TAG_MIN}-${TAG_MAX} tags ONLY from this faceted vocabulary (never invent), spread across facets:
${facetLines}
Also extract "performers" (named acts/artists/teams in the title) and "brands" (a festival/series/tour/promoter brand ONLY — never the venue, host org, library, museum, studio, or business; most events have none). [] if none.
Return ONLY a JSON array of {id, tags[], performers[], brands[]}.

Events:
${JSON.stringify(evs.map((e) => ({ id: e.id, title: e.title, description: (e.description || "").slice(0, 200), venue: e.venue_name, city: e.venue_city, free: e.is_free })), null, 2)}`;

  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: { "x-api-key": AK, "anthropic-version": "2023-06-01", "content-type": "application/json" },
    body: JSON.stringify({ model: "claude-haiku-4-5-20251001", max_tokens: 2048, messages: [{ role: "user", content: prompt }] }),
  });
  const data = await res.json();
  if (!res.ok) { console.error("Anthropic error:", JSON.stringify(data).slice(0, 300)); process.exit(1); }
  const text = data.content[0].text.trim().replace(/^```json?/, "").replace(/```$/, "").trim();
  const tagged = JSON.parse(text);
  const byId = Object.fromEntries(tagged.map((t) => [t.id, t]));

  for (const e of evs) {
    const t = byId[e.id] || {};
    console.log(`\n▸ ${e.title}  ${e.is_free ? "(free)" : ""}`);
    console.log(`  @ ${e.venue_name || "?"}, ${e.venue_city || "?"}`);
    console.log(`  tags(${(t.tags || []).length}): ${(t.tags || []).join(", ")}`);
    if ((t.performers || []).length) console.log(`  performers: ${t.performers.join(", ")}`);
    if ((t.brands || []).length) console.log(`  brands: ${t.brands.join(", ")}`);
  }
}
main().catch((e) => { console.error(e); process.exit(1); });
