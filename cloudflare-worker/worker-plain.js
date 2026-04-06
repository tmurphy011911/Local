var SUPABASE_FUNCTION_URL = "https://blifubxslaucshmlphdc.supabase.co/functions/v1/share-preview";
var SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImJsaWZ1YnhzbGF1Y3NobWxwaGRjIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzI3MjgyNjEsImV4cCI6MjA4ODMwNDI2MX0.xx-iy7kKhHviAjtjfXdxNHp4pmKU-in3efr2SkvWTf0";
var AASA_OBJ = { applinks: { apps: [], details: [{ appID: "2BGU22C396.live.localapp.local", paths: ["/p/*", "/e/*", "/v/*"] }] } };
var AASA = JSON.stringify(AASA_OBJ);

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
  async fetch(request) {
    var url = new URL(request.url);
    var path = url.pathname;

    if (path === "/.well-known/apple-app-site-association" || path === "/apple-app-site-association") {
      return new Response(AASA, {
        headers: { "Content-Type": "application/json", "Cache-Control": "public, max-age=86400" }
      });
    }

    var planMatch = path.match(/^\/p\/([A-Za-z0-9_-]+)$/);
    if (planMatch) {
      return supabaseFetch(SUPABASE_FUNCTION_URL + "?type=plan&token=" + planMatch[1], request);
    }

    var eventMatch = path.match(/^\/e\/([A-Za-z0-9-]+)$/);
    if (eventMatch) {
      return supabaseFetch(SUPABASE_FUNCTION_URL + "?type=event&id=" + eventMatch[1], request);
    }

    var venueMatch = path.match(/^\/v\/([A-Za-z0-9-]+)$/);
    if (venueMatch) {
      return supabaseFetch(SUPABASE_FUNCTION_URL + "?type=venue&id=" + venueMatch[1], request);
    }

    return fetch(request);
  }
};
