// Shared Sentry + PostHog helpers for Supabase edge functions.
// Dependency-free — uses Deno's built-in fetch. Same approach as the
// cloudflare-worker, mirrored for the Deno runtime.
//
// To upgrade to env-var-based config later, replace each constant with
// `Deno.env.get("NAME") ?? ""` and run:
//   supabase secrets set SENTRY_DSN_EDGE=... POSTHOG_KEY=... --project-ref blifubxslaucshmlphdc

const SENTRY_DSN  = Deno.env.get("SENTRY_DSN_EDGE") ??
  "https://8fd3c600558d23b1afdd7db33b77accd@o4511425445298177.ingest.us.sentry.io/4511425463844864";
const POSTHOG_KEY = Deno.env.get("POSTHOG_KEY") ??
  "phc_uD9aHMEmF7PKqEhdypd2CwNJCq2mR2RsAjDZjzAgviyS";
const POSTHOG_HOST = Deno.env.get("POSTHOG_HOST") ?? "https://us.i.posthog.com";

// ---- Sentry DSN parsing ----
let SENTRY_INGEST: string | null = null;
let SENTRY_PUBLIC_KEY: string | null = null;
try {
  const parsed = new URL(SENTRY_DSN);
  SENTRY_PUBLIC_KEY = parsed.username;
  const projectId = parsed.pathname.replace(/^\//, "");
  SENTRY_INGEST = `${parsed.protocol}//${parsed.host}/api/${projectId}/envelope/`;
} catch { /* leave nulls -> captureError is a no-op */ }

const isoNow = () => new Date().toISOString();

const sentryAuthHeader = () => [
  "Sentry sentry_version=7",
  "sentry_client=supabase-edge/0.1",
  `sentry_key=${SENTRY_PUBLIC_KEY}`,
].join(", ");

export type EdgeCtx = {
  functionName: string;
  userId?: string;
  request?: Request;
};

/**
 * Send a non-blocking error to Sentry. Wraps stack-parsing in try/catch
 * so a malformed exception can never bring down the edge function.
 */
export function captureError(err: unknown, ctx: EdgeCtx): Promise<void> {
  if (!SENTRY_INGEST) return Promise.resolve();
  try {
    const e = err instanceof Error ? err : new Error(String(err));
    const event = {
      event_id: crypto.randomUUID().replace(/-/g, ""),
      timestamp: isoNow(),
      platform: "javascript",
      level: "error",
      environment: "prod",
      server_name: ctx.functionName,
      tags: { function: ctx.functionName },
      user: ctx.userId ? { id: ctx.userId } : undefined,
      request: ctx.request ? {
        url: ctx.request.url,
        method: ctx.request.method,
        headers: { "user-agent": ctx.request.headers.get("user-agent") ?? "" },
      } : undefined,
      exception: {
        values: [{
          type: e.name,
          value: e.message,
          stacktrace: e.stack
            ? { frames: String(e.stack).split("\n").slice(0, 30).map((line) => ({ filename: line.trim() })) }
            : undefined,
        }],
      },
    };
    const envelope =
      JSON.stringify({ event_id: event.event_id, sent_at: isoNow() }) + "\n" +
      JSON.stringify({ type: "event" }) + "\n" +
      JSON.stringify(event);
    return fetch(SENTRY_INGEST, {
      method: "POST",
      headers: {
        "Content-Type": "application/x-sentry-envelope",
        "X-Sentry-Auth": sentryAuthHeader(),
      },
      body: envelope,
    }).then(() => undefined).catch(() => undefined);
  } catch {
    return Promise.resolve();
  }
}

/**
 * Fire a PostHog event. Returns a Promise so callers can `await Promise.all`
 * before responding, or call `EdgeRuntime.waitUntil(track(...))` for
 * fire-and-forget on Supabase's Deno runtime.
 */
export function track(
  eventName: string,
  properties: Record<string, unknown> = {},
  ctx: EdgeCtx,
): Promise<void> {
  if (!POSTHOG_KEY) return Promise.resolve();
  const body = JSON.stringify({
    api_key: POSTHOG_KEY,
    event: eventName,
    distinct_id: ctx.userId ?? "edge-anonymous",
    properties: {
      platform: "edge",
      function: ctx.functionName,
      ...properties,
    },
    timestamp: isoNow(),
  });
  return fetch(`${POSTHOG_HOST}/capture/`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body,
  }).then(() => undefined).catch(() => undefined);
}

/**
 * Convenience wrapper: run a handler, capture any throw to Sentry, and
 * emit a `edge_function_invoked` event regardless of outcome.
 */
export async function withObservability<T>(
  ctx: EdgeCtx,
  handler: () => Promise<T>,
): Promise<T> {
  const start = Date.now();
  try {
    const result = await handler();
    void track("edge_function_invoked", { duration_ms: Date.now() - start, ok: true }, ctx);
    return result;
  } catch (err) {
    void captureError(err, ctx);
    void track("edge_function_failed", {
      duration_ms: Date.now() - start,
      error_class: err instanceof Error ? err.name : "Unknown",
    }, ctx);
    throw err;
  }
}
