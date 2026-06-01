const K = process.env.K, P = "blifubxslaucshmlphdc";
const fs = require("fs");
const rows = JSON.parse(fs.readFileSync("scripts/_backup_2026_06_01_biblio_urls.json", "utf8"));
const BAD = ".bibliocommons.com/v2/events/", GOOD = ".bibliocommons.com/events/";

async function patch(row) {
  const newUrl = row.source_url.replace(BAD, GOOD);
  if (newUrl === row.source_url) return { id: row.id, status: "skip" };
  const url = `https://${P}.supabase.co/rest/v1/events?id=eq.${row.id}`;
  const r = await fetch(url, {
    method: "PATCH",
    headers: { apikey: K, Authorization: `Bearer ${K}`, "Content-Type": "application/json", Prefer: "return=minimal" },
    body: JSON.stringify({ source_url: newUrl }),
  });
  return { id: row.id, status: r.ok ? "ok" : `err ${r.status}`, body: r.ok ? "" : await r.text() };
}

(async () => {
  let ok = 0, skip = 0, err = 0;
  const errs = [];
  const CONC = 10;
  for (let i = 0; i < rows.length; i += CONC) {
    const batch = rows.slice(i, i + CONC);
    const res = await Promise.all(batch.map(patch));
    for (const x of res) {
      if (x.status === "ok") ok++;
      else if (x.status === "skip") skip++;
      else { err++; errs.push(x); }
    }
  }
  console.log("UPDATE done -> ok:", ok, "skip:", skip, "err:", err);
  if (errs.length) console.log("first errors:", JSON.stringify(errs.slice(0, 5), null, 2));
})();
