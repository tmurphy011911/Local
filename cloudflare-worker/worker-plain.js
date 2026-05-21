import { captureError, track } from "./observability.js";

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

      if (path === "/.well-known/assetlinks.json") {
        route = "assetlinks";
        const resp = new Response(ASSETLINKS, {
          headers: { "Content-Type": "application/json", "Cache-Control": "public, max-age=86400" }
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
