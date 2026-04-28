/**
 * backfill_curated_images.js — Fill image_url on curated_events rows where it's null.
 *
 * Re-uses the three-pass resolver from refresh_curated_events.js:
 *   1. existing image_url (no-op if already set; this script only targets nulls)
 *   2. og:image from source_url
 *   3. Wikipedia article thumbnail by title
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/backfill_curated_images.js
 *
 * Env:
 *   REGION=MA          — region to backfill (default: MA)
 *   DRY_RUN=1          — log resolutions without updating the DB
 *   LIMIT=100          — max rows to process per run
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

const REGION = process.env.REGION || "MA";
const DRY_RUN = process.env.DRY_RUN === "1";
const LIMIT = parseInt(process.env.LIMIT || "100", 10);

let SUPABASE_SERVICE_KEY;
const BROWSER_UA = "LocalApp/1.0 (+https://localapp.live; curated image backfill)";

// ── Resolver helpers (duplicated from refresh_curated_events.js on purpose —
// extracting to a shared module is a reach for two scripts, and keeping them
// here makes this file runnable standalone) ───────────────────────────────

async function validateImageUrl(url) {
  if (!url || typeof url !== "string") return null;
  if (!/^https?:\/\//.test(url)) return null;
  const headers = { "User-Agent": BROWSER_UA, "Accept": "image/*,*/*;q=0.8" };
  const check = async (method) => {
    try {
      const res = await fetch(url, {
        method, redirect: "follow", signal: AbortSignal.timeout(8000),
        headers: method === "GET" ? { ...headers, Range: "bytes=0-1023" } : headers,
      });
      if (!res.ok && res.status !== 206) return null;
      const type = (res.headers.get("content-type") || "").toLowerCase();
      if (type && !type.startsWith("image/") && !type.includes("octet-stream")) return null;
      return url;
    } catch (_) { return null; }
  };
  return (await check("HEAD")) || (await check("GET"));
}

async function extractOgImageFromPage(pageUrl) {
  if (!pageUrl || typeof pageUrl !== "string") return null;
  if (!/^https?:\/\//.test(pageUrl)) return null;
  try {
    const res = await fetch(pageUrl, {
      redirect: "follow", signal: AbortSignal.timeout(10000),
      headers: { "User-Agent": BROWSER_UA, "Accept": "text/html,*/*;q=0.8" },
    });
    if (!res.ok) return null;
    const html = (await res.text()).slice(0, 200_000);
    const patterns = [
      /<meta[^>]+property=["']og:image:secure_url["'][^>]+content=["']([^"']+)["']/i,
      /<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:image:secure_url["']/i,
      /<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']/i,
      /<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:image["']/i,
      /<meta[^>]+name=["']twitter:image["'][^>]+content=["']([^"']+)["']/i,
      /<meta[^>]+content=["']([^"']+)["'][^>]+name=["']twitter:image["']/i,
    ];
    for (const re of patterns) {
      const m = html.match(re);
      if (m && m[1]) {
        try { return new URL(m[1], pageUrl).toString(); } catch { return null; }
      }
    }
    return null;
  } catch (_) { return null; }
}

function stripYearSuffix(title) {
  return (title || "")
    .replace(/\s*\d{4}(?:\s*[–-]\s*\d{2,4})?\s*$/, "")
    .replace(/\s+(spring|summer|fall|autumn|winter)\s*$/i, "")
    .trim();
}

async function extractWikipediaImage(rawTitle) {
  const title = stripYearSuffix(rawTitle);
  if (!title) return null;
  const endpoint = `https://en.wikipedia.org/api/rest_v1/page/summary/${encodeURIComponent(title)}`;
  try {
    const res = await fetch(endpoint, {
      redirect: "follow", signal: AbortSignal.timeout(8000),
      headers: { "User-Agent": BROWSER_UA, "Accept": "application/json" },
    });
    if (!res.ok) {
      const searchUrl = `https://en.wikipedia.org/w/api.php?action=opensearch&search=${encodeURIComponent(title)}&limit=1&namespace=0&format=json`;
      const sres = await fetch(searchUrl, {
        signal: AbortSignal.timeout(8000),
        headers: { "User-Agent": BROWSER_UA },
      });
      if (!sres.ok) return null;
      const [, titles] = await sres.json();
      const picked = titles?.[0];
      if (!picked) return null;
      const retryRes = await fetch(
        `https://en.wikipedia.org/api/rest_v1/page/summary/${encodeURIComponent(picked)}`,
        { signal: AbortSignal.timeout(8000), headers: { "User-Agent": BROWSER_UA } },
      );
      if (!retryRes.ok) return null;
      const json = await retryRes.json();
      return json.originalimage?.source || json.thumbnail?.source || null;
    }
    const json = await res.json();
    return json.originalimage?.source || json.thumbnail?.source || null;
  } catch (_) { return null; }
}

async function searchCommonsImage(rawTitle, region) {
  const title = stripYearSuffix(rawTitle);
  if (!title) return null;
  const query = `${title} ${region || ""}`.trim();
  const apiUrl = `https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrsearch=${encodeURIComponent(query)}&gsrnamespace=6&gsrlimit=10&prop=imageinfo&iiprop=url%7Csize&iiurlwidth=1600&format=json&origin=*`;
  try {
    const res = await fetch(apiUrl, {
      signal: AbortSignal.timeout(10000),
      headers: { "User-Agent": BROWSER_UA, "Accept": "application/json" },
    });
    if (!res.ok) return null;
    const json = await res.json();
    const pages = Object.values(json?.query?.pages || {});
    if (pages.length === 0) return null;
    const blacklistRe = /(logo|logotype|seal|coat[_\s-]?of[_\s-]?arms|flag|icon|wordmark|emblem|crest|svg|\.pdf$|\.tif+$)/i;
    const ranked = pages
      .map((p) => {
        const info = p?.imageinfo?.[0];
        if (!info) return null;
        const filename = (p.title || "").replace(/^File:/, "");
        if (blacklistRe.test(filename)) return null;
        const url = info.thumburl || info.url;
        const width = info.thumbwidth || info.width || 0;
        if (!url || width < 600) return null;
        return { url, width };
      })
      .filter(Boolean)
      .sort((a, b) => b.width - a.width);
    return ranked[0]?.url || null;
  } catch (_) { return null; }
}

async function resolveImage(sourceUrl, title, region) {
  const commons = await searchCommonsImage(title, region);
  if (commons) {
    const commonsValidated = await validateImageUrl(commons);
    if (commonsValidated) return { url: commonsValidated, reason: "commons" };
  }
  const ogRaw = await extractOgImageFromPage(sourceUrl);
  if (ogRaw) {
    const ogValidated = await validateImageUrl(ogRaw);
    if (ogValidated) return { url: ogValidated, reason: "og-image" };
  }
  const wiki = await extractWikipediaImage(title);
  if (wiki) {
    const wikiValidated = await validateImageUrl(wiki);
    if (wikiValidated) return { url: wikiValidated, reason: "wikipedia" };
  }
  return { url: null, reason: "no-image-found" };
}

// ── Main ──────────────────────────────────────────────────────────────

async function main() {
  const secrets = await getSecrets([]);
  SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;

  const filter = `region=eq.${REGION}&is_active=eq.true&image_url=is.null&limit=${LIMIT}`;
  const fetchRes = await fetch(`${SUPABASE_URL}/rest/v1/curated_events?${filter}&select=id,title,source_url`, {
    headers: {
      apikey: SUPABASE_SERVICE_KEY,
      Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
    },
  });
  if (!fetchRes.ok) {
    console.error(`Fetch failed ${fetchRes.status}: ${await fetchRes.text()}`);
    process.exit(1);
  }
  const rows = await fetchRes.json();
  console.log(`[backfill] found ${rows.length} rows with null image_url`);

  let filled = 0;
  for (const row of rows) {
    const { url, reason } = await resolveImage(row.source_url, row.title, REGION);
    if (!url) {
      console.log(`[backfill] SKIP (${reason}): ${row.title}`);
      continue;
    }
    if (DRY_RUN) {
      console.log(`[backfill] DRY (${reason}): ${row.title} → ${url}`);
      filled++;
      continue;
    }
    const patchRes = await fetch(`${SUPABASE_URL}/rest/v1/curated_events?id=eq.${row.id}`, {
      method: "PATCH",
      headers: {
        apikey: SUPABASE_SERVICE_KEY,
        Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ image_url: url }),
    });
    if (!patchRes.ok) {
      console.error(`[backfill] patch failed for ${row.title}: ${await patchRes.text()}`);
      continue;
    }
    filled++;
    console.log(`[backfill] OK (${reason}): ${row.title}`);
  }
  console.log(`[backfill] filled ${filled}/${rows.length}`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
