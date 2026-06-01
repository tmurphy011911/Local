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

async function fetchHtml(
  url,
  { scrapflyKey, renderJs = false, timeoutMs = 30000, waitForSelector, renderingWait } = {}
) {
  if (scrapflyKey) {
    const params = new URLSearchParams({
      key: scrapflyKey,
      url,
      country: "us",
      asp: "true",                 // anti-bot bypass
      render_js: renderJs ? "true" : "false",
    });
    // Late-AJAX widgets (e.g. TicketWeb tw-calendar) populate the DOM AFTER
    // initial render. Without a wait, Scrapfly captures the "Loading…" shell
    // and the crawler sees 0 events. Only meaningful when render_js is on.
    if (renderJs) {
      if (waitForSelector) params.set("wait_for_selector", waitForSelector);
      // Fallback fixed delay so widgets without a known selector still settle.
      params.set("rendering_wait", String(renderingWait ?? 3000));
    }
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

/**
 * POST a form body and return the response text, routed through Scrapfly's
 * anti-bot bypass when a key is present so Cloudflare-protected XHR endpoints
 * (e.g. WordPress admin-ajax.php behind Cloudflare) don't 403.
 *
 * `formBody` is an already-encoded application/x-www-form-urlencoded string.
 * Returns { body, via }. Throws only if BOTH Scrapfly and the direct fallback
 * fail, so callers get a real error to log.
 */
async function postForm(url, { scrapflyKey, formBody, headers = {}, timeoutMs = 30000 } = {}) {
  const reqHeaders = {
    "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
    "X-Requested-With": "XMLHttpRequest",
    ...headers,
  };

  if (scrapflyKey) {
    const params = new URLSearchParams({
      key: scrapflyKey,
      url,
      country: "us",
      asp: "true",
      method: "POST",
      body: formBody,
    });
    // Scrapfly forwards target request headers via headers[Name]=Value params.
    for (const [k, v] of Object.entries(reqHeaders)) params.set(`headers[${k}]`, v);
    const sUrl = `https://api.scrapfly.io/scrape?${params}`;
    try {
      const res = await fetchWithTimeout(sUrl, {}, timeoutMs);
      if (res.ok) {
        const json = await res.json();
        if (json.result?.content != null) return { body: json.result.content, via: "scrapfly" };
      }
    } catch (_) { /* fall through to direct */ }
  }

  const res = await fetchWithTimeout(
    url,
    { method: "POST", headers: { "User-Agent": CHROME_UA, ...reqHeaders }, body: formBody },
    timeoutMs
  );
  if (!res.ok) throw new Error(`POST ${url}: ${res.status}`);
  return { body: await res.text(), via: "direct" };
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

module.exports = { fetchHtml, postForm };
