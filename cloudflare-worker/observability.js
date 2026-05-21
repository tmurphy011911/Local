// Lightweight, dependency-free Sentry + PostHog clients for the Cloudflare
// Worker. Both APIs are plain HTTPS — no npm packages or bundler needed.
//
// Sentry: posts a single-event "envelope" to /api/<projectId>/envelope/
// PostHog: posts to /capture/
//
// Errors here never throw — observability MUST NOT take down the request path.

const SENTRY_DSN  = "https://578c009c2008567bc3dbdfc6390a6ceb@o4511425445298177.ingest.us.sentry.io/4511425459912704";
const POSTHOG_KEY = "phc_uD9aHMEmF7PKqEhdypd2CwNJCq2mR2RsAjDZjzAgviyS";
const POSTHOG_HOST = "https://us.i.posthog.com";

// ----- DSN parsing (one-time at module load) -----
let SENTRY_INGEST = null;
let SENTRY_PROJECT_ID = null;
let SENTRY_PUBLIC_KEY = null;
try {
  // DSN form: https://<publicKey>@<host>/<projectId>
  const parsed = new URL(SENTRY_DSN);
  SENTRY_PUBLIC_KEY = parsed.username;
  SENTRY_PROJECT_ID = parsed.pathname.replace(/^\//, "");
  SENTRY_INGEST = `${parsed.protocol}//${parsed.host}/api/${SENTRY_PROJECT_ID}/envelope/`;
} catch (_) { /* leave nulls -> sendError no-ops */ }

function isoNow() { return new Date().toISOString(); }

function sentryAuthHeader() {
  return [
    "Sentry sentry_version=7",
    "sentry_client=local-worker/0.1",
    `sentry_key=${SENTRY_PUBLIC_KEY}`,
  ].join(", ");
}

// Capture an Error into Sentry. Optional `ctx` carries route/method/status etc.
// Uses ctx.waitUntil if provided so the fetch resolves without blocking.
export function captureError(err, request, ctx) {
  if (!SENTRY_INGEST || !err) return;
  const event = {
    event_id: crypto.randomUUID().replace(/-/g, ""),
    timestamp: isoNow(),
    platform: "javascript",
    level: "error",
    environment: "prod",
    server_name: "localapp-router",
    request: request ? {
      url: request.url,
      method: request.method,
      headers: { "user-agent": request.headers.get("User-Agent") || "" },
    } : undefined,
    exception: {
      values: [{
        type: err.name || "Error",
        value: String(err.message || err),
        stacktrace: err.stack ? { frames: parseStack(err.stack) } : undefined,
      }],
    },
  };
  const envelope =
    JSON.stringify({ event_id: event.event_id, sent_at: isoNow() }) + "\n" +
    JSON.stringify({ type: "event" }) + "\n" +
    JSON.stringify(event);
  const p = fetch(SENTRY_INGEST, {
    method: "POST",
    headers: {
      "Content-Type": "application/x-sentry-envelope",
      "X-Sentry-Auth": sentryAuthHeader(),
    },
    body: envelope,
  }).catch(() => {});
  if (ctx && ctx.waitUntil) ctx.waitUntil(p);
}

function parseStack(stack) {
  // Best-effort line splitter; Sentry will accept whatever shape we send.
  return String(stack).split("\n").slice(0, 30).map((line) => ({ filename: line.trim() }));
}

// Send a PostHog event. Fire-and-forget; uses waitUntil when available.
export function track(eventName, properties, distinctId, ctx) {
  if (!POSTHOG_KEY) return;
  const body = JSON.stringify({
    api_key: POSTHOG_KEY,
    event: eventName,
    distinct_id: distinctId || "worker-anonymous",
    properties: {
      platform: "worker",
      ...(properties || {}),
    },
    timestamp: isoNow(),
  });
  const p = fetch(`${POSTHOG_HOST}/capture/`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body,
  }).catch(() => {});
  if (ctx && ctx.waitUntil) ctx.waitUntil(p);
}
