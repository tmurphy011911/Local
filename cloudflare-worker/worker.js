/**
 * Cloudflare Worker for localapp.live
 *
 * Routes share links to the Supabase share-preview edge function.
 * Serves apple-app-site-association + assetlinks for Universal / App Links.
 * Serves the branded /get (·/app ·/download) app-download page with rich link preview.
 * Everything else passes through to the origin (Squarespace).
 *
 * NOTE: wrangler.toml deploys worker-plain.js — this file is a full 1:1 mirror kept
 * in sync as a backup/reference. Any change here must also land in worker-plain.js.
 */

import { captureError, track } from "./observability.js";
import { SHARE_CARD_PNG_B64 } from "./share-card-data.js";
import { APP_ICON_PNG_B64 } from "./app-icon-data.js";

// Decode the embedded share card once at module load (cold start), not per request.
function base64ToBytes(b64) {
  const bin = atob(b64);
  const len = bin.length;
  const bytes = new Uint8Array(len);
  for (let i = 0; i < len; i++) bytes[i] = bin.charCodeAt(i);
  return bytes;
}
var SHARE_CARD_PNG = base64ToBytes(SHARE_CARD_PNG_B64);
var APP_ICON_PNG = base64ToBytes(APP_ICON_PNG_B64);

var SUPABASE_FUNCTION_URL = "https://blifubxslaucshmlphdc.supabase.co/functions/v1/share-preview";
var SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImJsaWZ1YnhzbGF1Y3NobWxwaGRjIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzI3MjgyNjEsImV4cCI6MjA4ODMwNDI2MX0.xx-iy7kKhHviAjtjfXdxNHp4pmKU-in3efr2SkvWTf0";
var AASA_OBJ = { applinks: { apps: [], details: [{ appID: "2BGU22C396.live.localapp.local", paths: ["/p/*", "/e/*", "/v/*"] }] } };
var AASA = JSON.stringify(AASA_OBJ);
var ASSETLINKS = JSON.stringify([{
  relation: ["delegate_permission/common.handle_all_urls"],
  target: {
    namespace: "android_app",
    package_name: "live.localapp.local",
    sha256_cert_fingerprints: ["33:6E:38:76:81:6E:64:6F:18:78:7F:3A:1A:CA:1B:D4:D2:12:9A:6B:21:7D:67:BA:E6:5C:E1:C7:AA:DF:94:A1"]
  }
}]);

// Branded "get the app" page: tap-first store buttons (iOS → App Store,
// Android → Play Store) + rich link-preview meta. Tap-first, NOT an auto-redirect —
// in-app browsers (Messages, Instagram, etc.) block a gesture-less store hand-off
// ("Could not find an appropriate application"); a real tap always resolves.
var GET_APP_HTML = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Get Local — plans worth leaving the house for</title>
  <meta name="description" content="Local finds the best events, venues, and plans near you. Download the app for iPhone and Android." />
  <meta name="theme-color" content="#FF6B6B" />

  <!-- Open Graph / rich link preview -->
  <meta property="og:type" content="website" />
  <meta property="og:site_name" content="Local" />
  <meta property="og:title" content="Get Local" />
  <meta property="og:description" content="The best events, venues, and plans near you — in one app." />
  <meta property="og:url" content="https://localapp.live/get" />
  <meta property="og:image" content="https://localapp.live/share-card.png" />
  <meta property="og:image:width" content="1200" />
  <meta property="og:image:height" content="630" />
  <meta name="twitter:card" content="summary_large_image" />
  <meta name="twitter:title" content="Get Local" />
  <meta name="twitter:description" content="The best events, venues, and plans near you — in one app." />
  <meta name="twitter:image" content="https://localapp.live/share-card.png" />

  <link rel="icon" href="https://localapp.live/app-icon.png" />
  <style>
    :root{
      --coral:#FF6B6B; --coral-dk:#E85555; --ink:#1A1523; --pebble:#7A7488;
      --bg-start:#FFF0EE; --bg-end:#FFE0DC; --white:#fff;
    }
    *{box-sizing:border-box}
    html,body{height:100%;margin:0}
    body{
      font-family:ui-sans-serif,-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
      color:var(--ink);
      background:linear-gradient(160deg,var(--bg-start) 0%,var(--bg-end) 100%);
      -webkit-font-smoothing:antialiased;
    }
    .wrap{
      min-height:100%;display:flex;flex-direction:column;align-items:center;
      justify-content:center;gap:22px;text-align:center;padding:32px 24px;
    }
    .logo{
      width:96px;height:96px;border-radius:24px;
      box-shadow:0 12px 32px rgba(232,85,85,.28);
    }
    h1{
      font-family:Georgia,"Times New Roman",serif;
      font-size:30px;line-height:1.15;margin:6px 0 0;font-weight:700;letter-spacing:-.01em;
    }
    p.sub{margin:0;color:var(--pebble);font-size:16px;max-width:20rem}
    .cta{
      display:inline-flex;align-items:center;justify-content:center;gap:8px;
      margin-top:6px;padding:16px 30px;border-radius:999px;
      background:var(--coral);color:var(--white);font-weight:700;font-size:17px;
      text-decoration:none;box-shadow:0 8px 20px rgba(255,107,107,.4);
      transition:transform .1s ease, box-shadow .1s ease;
    }
    .cta:active{transform:translateY(1px);box-shadow:0 4px 12px rgba(255,107,107,.4)}
    .hint{margin-top:2px;font-size:13px}
    .hint a{color:var(--coral-dk);text-decoration:none;border-bottom:1px solid rgba(232,85,85,.35)}
    .brand{margin-top:auto;padding-top:28px;color:var(--pebble);font-size:12px;letter-spacing:.14em;text-transform:uppercase}
  </style>
</head>
<body>
  <div class="wrap">
    <img class="logo" src="https://localapp.live/app-icon.png" alt="Local" width="96" height="96" />
    <h1>Get Local</h1>
    <p class="sub">The best events, venues, and plans near you — in one app.</p>
    <a id="cta" class="cta" href="https://localapp.live" rel="noopener">Get the app</a>
    <p class="hint"><a id="alt" href="https://localapp.live">on another device?</a></p>
    <div class="brand">Local</div>
  </div>
  <script>
    var PKG      = "live.localapp.local";
    var IOS_URL  = "https://apps.apple.com/us/app/local-app/id6761425705";
    var PLAY_WEB = "https://play.google.com/store/apps/details?id=" + PKG + "&pcampaignid=web_share";
    // intent:// launches the Play Store app directly (with an https fallback baked in)
    // so it survives in-app browsers (Messages, Instagram) that refuse to hand a plain
    // play.google.com link to the Play app — the "Could not find an appropriate
    // application" case.
    var PLAY_APP = "intent://details?id=" + PKG + "&pcampaignid=web_share" +
                   "#Intent;scheme=market;package=com.android.vending;" +
                   "S.browser_fallback_url=" + encodeURIComponent(PLAY_WEB) + ";end";
    var ua = navigator.userAgent || navigator.vendor || window.opera || "";
    var isIOS = /iPad|iPhone|iPod/.test(ua) ||
                (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
    var isAndroid = /android/i.test(ua);
    var cta = document.getElementById("cta");
    var alt = document.getElementById("alt");
    if (isIOS) {
      cta.href = IOS_URL;   cta.textContent = "Download on the App Store";
      alt.href = PLAY_WEB;  alt.textContent = "On Android? Get it on Google Play";
    } else if (isAndroid) {
      cta.href = PLAY_APP;  cta.textContent = "Get it on Google Play";
      alt.href = PLAY_WEB;  alt.textContent = "Trouble? Open Google Play in your browser";
    } else {
      cta.href = IOS_URL;   cta.textContent = "Download on the App Store";
      alt.href = PLAY_WEB;  alt.textContent = "Get it on Google Play";
    }
  </script>
</body>
</html>`;

async function supabaseFetch(url, request) {
  var resp = await fetch(url, {
    headers: {
      "Authorization": "Bearer " + SUPABASE_ANON_KEY,
      "apikey": SUPABASE_ANON_KEY,
      "User-Agent": request.headers.get("User-Agent") || ""
    }
  });
  var body = await resp.text();
  return new Response(body, {
    status: resp.status,
    headers: {
      "Content-Type": "text/html; charset=utf-8",
      "Cache-Control": "public, max-age=300"
    }
  });
}

export default {
  async fetch(request, env, ctx) {
    var url = new URL(request.url);
    var path = url.pathname;
    var route = "passthrough";
    var status = 0;

    try {
      if (path === "/.well-known/apple-app-site-association" || path === "/apple-app-site-association") {
        route = "aasa";
        const resp = new Response(AASA, {
          headers: { "Content-Type": "application/json", "Cache-Control": "public, max-age=86400" }
        });
        status = resp.status;
        return resp;
      }

      if (path === "/share-card.png") {
        route = "share_card";
        const resp = new Response(SHARE_CARD_PNG, {
          headers: {
            "Content-Type": "image/png",
            // Long cache: the card is static. Bump the og:image query string
            // (?v=N) in share-preview if it ever needs to change.
            "Cache-Control": "public, max-age=31536000, immutable",
          },
        });
        status = resp.status;
        return resp;
      }

      if (path === "/app-icon.png") {
        route = "app_icon";
        const resp = new Response(APP_ICON_PNG, {
          headers: {
            "Content-Type": "image/png",
            "Cache-Control": "public, max-age=31536000, immutable",
          },
        });
        status = resp.status;
        return resp;
      }

      if (path === "/.well-known/assetlinks.json") {
        route = "assetlinks";
        const resp = new Response(ASSETLINKS, {
          headers: { "Content-Type": "application/json", "Cache-Control": "public, max-age=86400" }
        });
        status = resp.status;
        return resp;
      }

      if (path === "/get" || path === "/app" || path === "/download") {
        route = "get_app";
        // Server-side store redirect. Link-preview crawlers get the HTML so the rich
        // card still unfurls; real mobile browsers are 302'd straight to their store
        // (an HTTP redirect hands off to the store app more reliably than an in-page
        // link, which in-app browsers like Google Messages block). Desktop sees the
        // page. Vary + no-store so a per-UA response is never cached for another UA.
        var ua = request.headers.get("User-Agent") || "";
        var isBot = /(bot|crawler|spider|preview|facebookexternalhit|facebot|whatsapp|telegram|slack|discord|linkedin|embedly|pinterest|redditbot|applebot|skype|twitter|vkshare|googlebot|google-inspectiontool|bingbot|okhttp|iframely|snapchat)/i.test(ua);
        var goAndroid = !isBot && /android/i.test(ua);
        var goIOS = !isBot && /iphone|ipad|ipod/i.test(ua);
        if (goAndroid || goIOS) {
          var dest = goAndroid
            ? "https://play.google.com/store/apps/details?id=live.localapp.local&pcampaignid=web_share"
            : "https://apps.apple.com/us/app/local-app/id6761425705";
          const redir = new Response(null, {
            status: 302,
            headers: { "Location": dest, "Cache-Control": "no-store", "Vary": "User-Agent" }
          });
          status = redir.status;
          return redir;
        }
        const resp = new Response(GET_APP_HTML, {
          headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store", "Vary": "User-Agent" }
        });
        status = resp.status;
        return resp;
      }

      var planMatch = path.match(/^\/p\/([A-Za-z0-9_-]+)$/);
      if (planMatch) {
        route = "plan_share";
        const resp = await supabaseFetch(SUPABASE_FUNCTION_URL + "?type=plan&token=" + planMatch[1], request);
        status = resp.status;
        return resp;
      }

      var eventMatch = path.match(/^\/e\/([A-Za-z0-9-]+)$/);
      if (eventMatch) {
        route = "event_share";
        const resp = await supabaseFetch(SUPABASE_FUNCTION_URL + "?type=event&id=" + eventMatch[1], request);
        status = resp.status;
        return resp;
      }

      var venueMatch = path.match(/^\/v\/([A-Za-z0-9-]+)$/);
      if (venueMatch) {
        route = "venue_share";
        const resp = await supabaseFetch(SUPABASE_FUNCTION_URL + "?type=venue&id=" + venueMatch[1], request);
        status = resp.status;
        return resp;
      }

      const resp = await fetch(request);
      status = resp.status;
      return resp;
    } catch (err) {
      captureError(err, request, ctx);
      throw err;
    } finally {
      track("worker_request", { route: route, status: status, path: path }, null, ctx);
    }
  }
};
