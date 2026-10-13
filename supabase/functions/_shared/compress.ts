// Latency sprint, Day 5 — gzip-compressed JSON responses for edge fns.
//
// Deno's CompressionStream natively supports "gzip" and "deflate". We
// emit gzip when the client advertises support; otherwise plain JSON
// (older clients, debug tools). Brotli requires a third-party module
// (deno.land/x/brotli) and adds ~5 ms CPU at quality 4 — defer until
// gzip alone doesn't meet the latency budget.
//
// Typical payload sizes from carousels/explore-events at current data
// volume (5–8 carousels × 10–25 events): 40–80 KB raw, 9–16 KB gzip.
// Saves ~250–400 ms of LTE transfer time per response.
//
// Usage from any edge function:
//   import { jsonCompressed, CORS_HEADERS_BASE } from "../_shared/compress.ts";
//   return jsonCompressed(req, payload, { extraHeaders: { "X-Cache": "miss" } });
//
// The helper preserves any extra headers the caller wants to inject
// (e.g. cache diagnostics), so existing telemetry is untouched.

import { thumbnailStorageImages } from "./images.ts";

export const CORS_HEADERS_BASE: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type, x-refresh-cache",
  "Access-Control-Max-Age": "86400", // cache preflight 24h
};

const JSON_HEADERS_BASE: Record<string, string> = {
  ...CORS_HEADERS_BASE,
  "Content-Type": "application/json; charset=utf-8",
  Vary: "Accept-Encoding",
};

interface CompressOptions {
  status?: number;
  extraHeaders?: Record<string, string>;
  /**
   * Rewrite Supabase Storage image URLs to the CDN-cached render endpoint at
   * card width (see ./images.ts). Defaults to true — these endpoints are the
   * card/list surfaces. Set false for a payload whose image URLs must stay at
   * source resolution.
   */
  transformImages?: boolean;
}

function pickEncoding(req: Request): "gzip" | "deflate" | null {
  const ae = (req.headers.get("accept-encoding") ?? "").toLowerCase();
  if (ae.includes("gzip")) return "gzip";
  if (ae.includes("deflate")) return "deflate";
  return null;
}

/**
 * Encode `value` as JSON, optionally gzip-compress it, and return a
 * Response. Sets Content-Type, Vary, CORS, and Content-Encoding
 * appropriately. `extraHeaders` win over the base set.
 */
export function jsonCompressed(
  req: Request,
  value: unknown,
  options: CompressOptions = {},
): Response {
  const { status = 200, extraHeaders = {}, transformImages = true } = options;

  // Serve Storage photos at card size rather than source size. Applied here
  // because this is the single serialization chokepoint for the three
  // high-volume card/list endpoints (carousels, explore-events,
  // explore-places) — the only importers of this module. See
  // ./images.ts for the measurements and why detail screens are excluded.
  // A payload with no Supabase Storage URLs passes through unchanged, so this
  // is a no-op for Google-hosted rows. Pass transformImages:false to opt out.
  const payload = transformImages ? thumbnailStorageImages(value) : value;
  const body = JSON.stringify(payload);
  const encoding = pickEncoding(req);

  if (!encoding) {
    return new Response(body, {
      status,
      headers: { ...JSON_HEADERS_BASE, ...extraHeaders },
    });
  }

  const stream = new Response(body).body!.pipeThrough(
    new CompressionStream(encoding),
  );
  return new Response(stream, {
    status,
    headers: {
      ...JSON_HEADERS_BASE,
      ...extraHeaders,
      "Content-Encoding": encoding,
    },
  });
}

/**
 * 204 No Content for CORS preflight. Short-circuits with Max-Age so
 * browsers / native dev tools don't re-preflight for 24h. Native
 * mobile clients (Ktor on Android/iOS) don't preflight at all.
 */
export function corsPreflight(): Response {
  return new Response(null, { status: 204, headers: CORS_HEADERS_BASE });
}
