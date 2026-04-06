/**
 * Cloudflare Worker for localapp.live
 *
 * Routes share links to the Supabase share-preview edge function.
 * Serves apple-app-site-association for Universal Links.
 * Everything else passes through to the origin (Squarespace).
 */

const SUPABASE_FUNCTION_URL =
  "https://blifubxslaucshmlphdc.supabase.co/functions/v1/share-preview";

const SUPABASE_ANON_KEY =
  "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImJsaWZ1YnhzbGF1Y3NobWxwaGRjIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzI3MjgyNjEsImV4cCI6MjA4ODMwNDI2MX0.xx-iy7kKhHviAjtjfXdxNHp4pmKU-in3efr2SkvWTf0";

// Apple App Site Association for Universal Links
const AASA = JSON.stringify({
  applinks: {
    apps: [],
    details: [
      {
        appID: "2BGU22C396.live.localapp.local",
        paths: ["/p/*", "/e/*", "/v/*"],
      },
    ],
  },
});

function supabaseFetch(url, request) {
  return fetch(url, {
    headers: {
      "Authorization": `Bearer ${SUPABASE_ANON_KEY}`,
      "apikey": SUPABASE_ANON_KEY,
      "User-Agent": request.headers.get("User-Agent") || "",
    },
  });
}

export default {
  async fetch(request) {
    const url = new URL(request.url);
    const path = url.pathname;

    // ── Apple App Site Association (Universal Links) ──
    if (
      path === "/.well-known/apple-app-site-association" ||
      path === "/apple-app-site-association"
    ) {
      return new Response(AASA, {
        headers: {
          "Content-Type": "application/json",
          "Cache-Control": "public, max-age=86400",
        },
      });
    }

    // ── Plan share links: /p/{token} ──
    const planMatch = path.match(/^\/p\/([A-Za-z0-9_-]+)$/);
    if (planMatch) {
      const token = planMatch[1];
      return supabaseFetch(`${SUPABASE_FUNCTION_URL}?type=plan&token=${token}`, request);
    }

    // ── Event share links: /e/{id} ──
    const eventMatch = path.match(/^\/e\/([A-Za-z0-9-]+)$/);
    if (eventMatch) {
      const id = eventMatch[1];
      return supabaseFetch(`${SUPABASE_FUNCTION_URL}?type=event&id=${id}`, request);
    }

    // ── Venue share links: /v/{id} ──
    const venueMatch = path.match(/^\/v\/([A-Za-z0-9-]+)$/);
    if (venueMatch) {
      const id = venueMatch[1];
      return supabaseFetch(`${SUPABASE_FUNCTION_URL}?type=venue&id=${id}`, request);
    }

    // ── Everything else → pass through to origin (Squarespace) ──
    return fetch(request);
  },
};
