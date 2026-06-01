const K = process.env.K, P = "blifubxslaucshmlphdc";
const fs = require("fs");
const LIKE = "source_url=like.*.bibliocommons.com/v2/events/*";
(async () => {
  let all = [], from = 0, step = 1000;
  while (true) {
    const url = `https://${P}.supabase.co/rest/v1/events?select=id,source_url&${LIKE}&order=id&limit=${step}&offset=${from}`;
    const r = await fetch(url, { headers: { apikey: K, Authorization: `Bearer ${K}` } });
    const d = await r.json();
    if (!Array.isArray(d) || d.length === 0) break;
    all = all.concat(d);
    from += step;
    if (d.length < step) break;
  }
  const f = "scripts/_backup_2026_06_01_biblio_urls.json";
  fs.writeFileSync(f, JSON.stringify(all));
  const bad = all.filter((r) => !r.source_url.includes(".bibliocommons.com/v2/events/")).length;
  console.log("snapshotted rows:", all.length, "->", f);
  console.log("rows NOT matching pattern (should be 0):", bad);
})();
