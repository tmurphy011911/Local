import "@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

// ════════════════════════════════════════════════════════════════
// Share Preview Edge Function
//
// Serves rich HTML with OG meta tags for plan/event/venue share links.
// iMessage, WhatsApp, etc. fetch these tags to render link previews.
//
// Routes:
//   ?type=plan&token=abc123     → plan preview
//   ?type=event&id=uuid         → event preview (future)
//   ?type=venue&id=uuid         → venue preview (future)
// ════════════════════════════════════════════════════════════════

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
};

const BRAND = {
  coral: "#FF6B6B",
  coralDark: "#E85D5D",
  snow: "#FAFAFA",
  ink: "#1A1A2E",
  ink3: "#8E8E9A",
  white: "#FFFFFF",
};

const APP_STORE_URL = "https://apps.apple.com/app/id6761425705";
const PLAY_STORE_URL =
  "https://play.google.com/store/apps/details?id=live.localapp.local";

// Single fixed share card used for ALL link previews (plan/event/venue).
// Served from the Cloudflare worker on the same origin as the links.
const SHARE_IMAGE_URL = "https://localapp.live/share-card.png";

// Universal Links / App Links open the app directly when installed, so this
// HTML only renders for users without the app — pick the right store by platform.
function storeUrlFor(userAgent: string | null): string {
  return /android/i.test(userAgent || "") ? PLAY_STORE_URL : APP_STORE_URL;
}

// On Android, ride the deep-link path through the Play install via the Install
// Referrer (read on first launch). iOS has no equivalent — it uses the clipboard.
function withReferrer(storeUrl: string, deepLinkPath: string): string {
  if (storeUrl !== PLAY_STORE_URL) return storeUrl;
  return `${storeUrl}&referrer=${encodeURIComponent(`dl=${deepLinkPath}`)}`;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response(null, { headers: CORS_HEADERS });
  }

  const url = new URL(req.url);
  const type = url.searchParams.get("type") || "plan";
  const token = url.searchParams.get("token");
  const id = url.searchParams.get("id");
  const storeUrl = storeUrlFor(req.headers.get("user-agent"));

  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  );

  if (type === "plan" && token) {
    return await handlePlanPreview(supabase, token, storeUrl);
  }

  if (type === "event" && id) {
    return await handleEventPreview(supabase, id, storeUrl);
  }

  if (type === "venue" && id) {
    return await handleVenuePreview(supabase, id, storeUrl);
  }

  return new Response("Not found", { status: 404, headers: CORS_HEADERS });
});

// ════════════════════════════════════════════════════════════════
// Plan Preview
// ════════════════════════════════════════════════════════════════

interface PlanSlot {
  name: string;
  time: string;
  emoji: string;
  detail: string;
  price: string;
  is_free: boolean;
  image_url: string | null;
}

interface PlanDay {
  label: string;
  short_name: string;
  slots: PlanSlot[];
}

interface PlanRow {
  name: string;
  city: string;
  date: string | null;
  days: PlanDay[];
  share_token: string;
}

async function handlePlanPreview(supabase: any, token: string, storeUrl: string) {
  const { data: plan, error } = await supabase
    .from("plans")
    .select("name, city, date, days, share_token")
    .eq("share_token", token)
    .single();

  if (error || !plan) {
    return htmlResponse(notFoundPage(storeUrl));
  }

  const p = plan as PlanRow;
  const allSlots = p.days.flatMap((d) => d.slots);
  const totalStops = allSlots.length;
  const venueNames = allSlots.map((s) => s.name).slice(0, 3).join(", ");

  const description = `${p.city} · ${totalStops} ${totalStops === 1 ? "stop" : "stops"} · ${venueNames}`;
  const shareUrl = `https://localapp.live/p/${token}`;
  const appStoreUrl = withReferrer(storeUrl, `/p/${token}`);

  // OG tags for iMessage/social previews (crawlers don't run JS)
  // JS redirect sends real users to App Store (Universal Links open app if installed)
  const html = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>${esc(p.name)} — Local</title>
  <meta property="og:type" content="website" />
  <meta property="og:title" content="${esc(p.name)}" />
  <meta property="og:description" content="${esc(description)}" />
  <meta property="og:url" content="${shareUrl}" />
  <meta property="og:site_name" content="Local" />
  <meta property="og:image" content="${SHARE_IMAGE_URL}" />
  <meta name="twitter:card" content="summary_large_image" />
  <meta name="twitter:title" content="${esc(p.name)}" />
  <meta name="twitter:description" content="${esc(description)}" />
  <meta name="twitter:image" content="${SHARE_IMAGE_URL}" />
  <script>
    // Copy share URL to clipboard for deferred deep linking after App Store install
    try { navigator.clipboard.writeText("${shareUrl}"); } catch(e) {
      var ta = document.createElement("textarea");
      ta.value = "${shareUrl}";
      ta.style.position = "fixed";
      ta.style.left = "-9999px";
      document.body.appendChild(ta);
      ta.select();
      document.execCommand("copy");
      document.body.removeChild(ta);
    }
    // Redirect to App Store (Universal Links open app if installed)
    setTimeout(function() { window.location.href = "${appStoreUrl}"; }, 100);
  </script>
</head>
<body style="font-family:-apple-system,sans-serif;text-align:center;padding:60px 20px;background:#FAFAFA;">
  <h1 style="font-size:24px;font-weight:800;color:#1A1A2E;">${esc(p.name)}</h1>
  <p style="color:#8E8E9A;margin-top:8px;">${esc(description)}</p>
  <a href="${appStoreUrl}" style="display:inline-block;margin-top:24px;background:#FF6B6B;color:white;padding:12px 24px;border-radius:12px;text-decoration:none;font-weight:700;">Get Local</a>
</body>
</html>`;

  return htmlResponse(html);
}

// ════════════════════════════════════════════════════════════════
// Event Preview (stub — expand later)
// ════════════════════════════════════════════════════════════════

async function handleEventPreview(supabase: any, id: string, storeUrl: string) {
  const { data: event } = await supabase
    .from("events")
    .select("id, title, description, category, start_at, venue_name, venue_city, image_url, is_free, price_min, price_max")
    .eq("id", id)
    .single();

  if (!event) return htmlResponse(notFoundPage(storeUrl));

  const priceText = event.is_free ? "Free" : event.price_min ? `From $${event.price_min}` : "";
  const desc = `${event.venue_name || ""} · ${event.venue_city || ""}${priceText ? " · " + priceText : ""}`;

  const ogTags = `
    <meta property="og:type" content="website" />
    <meta property="og:title" content="${esc(event.title)}" />
    <meta property="og:description" content="${esc(desc)}" />
    <meta property="og:url" content="https://localapp.live/e/${event.id}" />
    <meta property="og:site_name" content="Local" />
    <meta property="og:image" content="${SHARE_IMAGE_URL}" />
    <meta name="twitter:card" content="summary_large_image" />
    <meta name="twitter:title" content="${esc(event.title)}" />
    <meta name="twitter:description" content="${esc(desc)}" />
    <meta name="twitter:image" content="${SHARE_IMAGE_URL}" />
  `;

  const eventUrl = `https://localapp.live/e/${event.id}`;
  const appStoreUrl = withReferrer(storeUrl, `/e/${event.id}`);

  return htmlResponse(`<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${esc(event.title)} — Local</title>${ogTags}
  <script>
    try { navigator.clipboard.writeText("${eventUrl}"); } catch(e) {}
    setTimeout(function() { window.location.href = "${appStoreUrl}"; }, 100);
  </script>
  </head><body style="font-family:-apple-system,sans-serif;text-align:center;padding:60px 20px;background:#FAFAFA;">
    <h1 style="font-size:22px;font-weight:800;color:#1A1A2E;">${esc(event.title)}</h1>
    <p style="color:#8E8E9A;margin-top:8px;">${esc(desc)}</p>
    <a href="${appStoreUrl}" style="display:inline-block;margin-top:24px;background:#FF6B6B;color:white;padding:12px 24px;border-radius:12px;text-decoration:none;font-weight:700;">Get Local</a>
  </body></html>`);
}

// ════════════════════════════════════════════════════════════════
// Venue Preview
// ════════════════════════════════════════════════════════════════

async function handleVenuePreview(supabase: any, id: string, storeUrl: string) {
  const { data: venue } = await supabase
    .from("venues")
    .select("id, name, city, category, description, photo_url, google_rating")
    .eq("id", id)
    .single();

  if (!venue) return htmlResponse(notFoundPage(storeUrl));

  const desc = [venue.category, venue.city, venue.google_rating ? `${venue.google_rating} stars` : null].filter(Boolean).join(" · ");
  const venueUrl = `https://localapp.live/v/${venue.id}`;
  const appStoreUrl = withReferrer(storeUrl, `/v/${venue.id}`);

  const ogTags = `
    <meta property="og:type" content="website" />
    <meta property="og:title" content="${esc(venue.name)}" />
    <meta property="og:description" content="${esc(desc)}" />
    <meta property="og:url" content="${venueUrl}" />
    <meta property="og:site_name" content="Local" />
    <meta property="og:image" content="${SHARE_IMAGE_URL}" />
    <meta name="twitter:card" content="summary_large_image" />
    <meta name="twitter:title" content="${esc(venue.name)}" />
    <meta name="twitter:description" content="${esc(desc)}" />
    <meta name="twitter:image" content="${SHARE_IMAGE_URL}" />
  `;

  return htmlResponse(`<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${esc(venue.name)} — Local</title>${ogTags}
  <script>
    try { navigator.clipboard.writeText("${venueUrl}"); } catch(e) {}
    setTimeout(function() { window.location.href = "${appStoreUrl}"; }, 100);
  </script>
  </head><body style="font-family:-apple-system,sans-serif;text-align:center;padding:60px 20px;background:#FAFAFA;">
    <h1 style="font-size:22px;font-weight:800;color:#1A1A2E;">${esc(venue.name)}</h1>
    <p style="color:#8E8E9A;margin-top:8px;">${esc(desc)}</p>
    <a href="${appStoreUrl}" style="display:inline-block;margin-top:24px;background:#FF6B6B;color:white;padding:12px 24px;border-radius:12px;text-decoration:none;font-weight:700;">Get Local</a>
  </body></html>`);
}

// ════════════════════════════════════════════════════════════════
// Helpers
// ════════════════════════════════════════════════════════════════

function esc(s: string): string {
  return s
    .replace(/&/g, "&amp;")
    .replace(/"/g, "&quot;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/'/g, "&#39;");
}

function htmlResponse(html: string): Response {
  return new Response(html, {
    headers: {
      ...CORS_HEADERS,
      "Content-Type": "text/html; charset=utf-8",
      "Cache-Control": "public, max-age=300",
    },
  });
}

function notFoundPage(storeUrl: string): string {
  return `<!DOCTYPE html><html><head><meta charset="utf-8"><title>Local</title></head><body style="font-family:-apple-system,sans-serif;text-align:center;padding:60px 20px;">
    <h1 style="font-size:24px;">This link has expired</h1>
    <p style="color:#8E8E9A;margin-top:8px;">The plan you're looking for is no longer available.</p>
    <a href="${storeUrl}" style="display:inline-block;margin-top:24px;background:#FF6B6B;color:white;padding:12px 24px;border-radius:12px;text-decoration:none;font-weight:700;">Get Local</a>
  </body></html>`;
}
