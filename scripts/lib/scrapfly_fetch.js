/**
 * scrapfly_fetch.js — fetch HTML through Scrapfly so Cloudflare-protected
 * and JS-rendered (React/Vue/SPA) pages return real content.
 *
 * Falls back to a direct fetch with a desktop Chrome User-Agent if Scrapfly
 * is not configured or fails on a single request — better to get partial
 * HTML than nothing.
 */

const CHROME_UA =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0 Safari/537.36";

async function fetchHtml(url, { scrapflyKey, renderJs = false, timeoutMs = 30000 } = {}) {
  if (scrapflyKey) {
    const params = new URLSearchParams({
      key: scrapflyKey,
      url,
      country: "us",
      asp: "true",                 // anti-bot bypass
      render_js: renderJs ? "true" : "false",
    });
    const sUrl = `https://api.scrapfly.io/scrape?${params}`;
    try {
      const res = await fetchWithTimeout(sUrl, {}, timeoutMs);
      if (res.ok) {
        const json = await res.json();
        if (json.result?.content) return { html: json.result.content, via: "scrapfly" };
      }
    } catch (_) { /* fall through */ }
  }
  const res = await fetchWithTimeout(url, { headers: { "User-Agent": CHROME_UA } }, timeoutMs);
  if (!res.ok) throw new Error(`fetch ${url}: ${res.status}`);
  return { html: await res.text(), via: "direct" };
}

async function fetchWithTimeout(url, init = {}, timeoutMs = 30000) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    return await fetch(url, { ...init, signal: ctrl.signal });
  } finally {
    clearTimeout(t);
  }
}

module.exports = { fetchHtml };
