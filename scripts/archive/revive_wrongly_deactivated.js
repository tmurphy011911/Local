/**
 * revive_wrongly_deactivated.js — Flip is_active=true on rows that were
 * deactivated despite still being in-window (end_at > NOW). Catches the
 * fallout from the old `deactivate_past_events.js` that ignored end_at.
 *
 * Broader filter than 20260517120100_revive_wrongly_deactivated_events.sql:
 * also revives future-start multi-day events (start_at > NOW) that should
 * never have been marked past in the first place.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/revive_wrongly_deactivated.js
 */

const SUPABASE_URL = "https://blifubxslaucshmlphdc.supabase.co";
const SERVICE_KEY = (process.env.SUPABASE_SERVICE_KEY || "").replace(/\s/g, "");

if (!SERVICE_KEY) {
  console.error("Missing SUPABASE_SERVICE_KEY");
  process.exit(1);
}

const headers = {
  apikey: SERVICE_KEY,
  Authorization: `Bearer ${SERVICE_KEY}`,
  "Content-Type": "application/json",
};

// Walk forward through end_at, day by day. For each day-window the
// SELECT is bounded to a few hundred rows and never hits the 8s timeout.
async function fetchBatch(windowStart, windowEnd) {
  const url =
    `${SUPABASE_URL}/rest/v1/events` +
    `?select=id` +
    `&is_active=eq.false` +
    `&end_at=gte.${encodeURIComponent(windowStart)}` +
    `&end_at=lt.${encodeURIComponent(windowEnd)}` +
    `&order=end_at.asc` +
    `&limit=1000`;
  const res = await fetch(url, { headers });
  if (!res.ok) throw new Error(`fetch: ${res.status} ${await res.text()}`);
  return res.json();
}

async function patchBatch(ids) {
  const url = `${SUPABASE_URL}/rest/v1/events?id=in.(${ids.join(",")})`;
  const res = await fetch(url, {
    method: "PATCH",
    headers: { ...headers, Prefer: "return=minimal" },
    body: JSON.stringify({ is_active: true }),
  });
  if (!res.ok) throw new Error(`patch: ${res.status} ${await res.text()}`);
}

async function main() {
  const start = new Date();             // today — first window starts at "now"
  const horizon = new Date(start);
  horizon.setUTCFullYear(horizon.getUTCFullYear() + 2);  // look 2 years out

  let total = 0;
  let windowStart = new Date(start);
  // 7-day windows; each is small enough to never trip the 8s timeout.
  const stepMs = 7 * 24 * 60 * 60 * 1000;

  while (windowStart < horizon) {
    const windowEnd = new Date(windowStart.getTime() + stepMs);
    const rows = await fetchBatch(windowStart.toISOString(), windowEnd.toISOString());
    if (rows.length > 0) {
      const ids = rows.map((r) => r.id);
      // patch in slices of 100 to stay well under any URL/payload limits
      for (let i = 0; i < ids.length; i += 100) {
        await patchBatch(ids.slice(i, i + 100));
      }
      total += ids.length;
      console.log(`  ${windowStart.toISOString().slice(0,10)} → +${ids.length} (running total ${total})`);
    }
    windowStart = windowEnd;
  }
  console.log(`Done. Revived ${total} event(s).`);
}

main().catch((e) => { console.error(e); process.exit(1); });
