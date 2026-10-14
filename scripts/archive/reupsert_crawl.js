const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

async function main() {
  const secrets = await getSecrets([]);
  const SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;

  const fs = require("fs");
  const raw = JSON.parse(fs.readFileSync(`${__dirname}/crawl_results.json`, "utf8"));
  console.log(`Loaded ${raw.length} events from crawl_results.json`);

  // Deduplicate by dedupe_hash — keep last occurrence
  const seen = new Map();
  for (const row of raw) seen.set(row.dedupe_hash, row);
  const rows = [...seen.values()];
  console.log(`After dedup: ${rows.length} unique events (removed ${raw.length - rows.length} duplicates)`);

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
      errors += batch.length;
      console.error(`Batch ${Math.floor(i/batchSize)} error:`, body);
    } else {
      inserted += batch.length;
    }
  }

  console.log(`\nDone. Upserted: ${inserted}, Errors: ${errors}`);
}

main().catch(err => { console.error(err); process.exit(1); });
