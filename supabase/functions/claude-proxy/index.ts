import "@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { verify } from "https://deno.land/x/djwt@v3.0.2/mod.ts";

// Bo's Claude proxy. The Anthropic key lives ONLY here (server secret), never
// in the client. Every call is gated on Local Pro entitlement:
//   entitled = is_pro = true OR now() < trial_ends_at
// The client also shows the paywall at the "Ask Bo" tap, but THIS is the real
// enforcement — a tampered client that skips the sheet still gets 402 here, so
// nobody spends our tokens for free.
//
// Identity travels in X-User-Token (the user's Supabase JWT), mirroring the
// carousels IDOR-fix convention; Authorization carries the anon key purely to
// satisfy the edge gateway. We verify the token server-side and trust it over
// anything client-supplied.
//
// LATENCY (why the client saw SocketTimeoutException at 30s — its socket read
// timeout is also the time-to-first-BYTE budget): the streaming path's first
// byte was gated on this proxy's serial pre-Claude work — auth getUser (a
// GoTrue network round-trip) + the profile read + Claude's response headers.
// Two fixes here:
//   1. resolveUserId() verifies the JWT signature LOCALLY (HMAC, no network)
//      when JWT_SECRET is configured, deleting the getUser round-trip in the
//      common case. Falls back to getUser() on any local-verify failure
//      (secret unset, asymmetric signing keys, expired), so it's safe + additive.
//   2. For streaming requests we return the SSE response immediately and emit a
//      `: keep-alive` comment before awaiting Claude, so the client's 30s
//      socket-read clock is reset and never trips on Claude's header latency.
//      The client's SSE parser ignores any line not starting with "data: ", so
//      the comment is invisible to it.

const CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization, X-User-Token",
};

const SSE_HEADERS = {
  "Content-Type": "text/event-stream",
  "Cache-Control": "no-cache",
  "Connection": "keep-alive",
  ...CORS,
};

const encoder = new TextEncoder();

// Imported once at module scope so the (cheap) HMAC key import isn't repeated
// per request. null when JWT_SECRET isn't configured → callers fall back to
// the GoTrue getUser() path.
const jwtKeyPromise: Promise<CryptoKey | null> = (async () => {
  const secret = Deno.env.get("JWT_SECRET");
  if (!secret) return null;
  try {
    return await crypto.subtle.importKey(
      "raw",
      encoder.encode(secret),
      { name: "HMAC", hash: "SHA-256" },
      false,
      ["verify"],
    );
  } catch (_e) {
    return null;
  }
})();

function deny(status: number, error: string): Response {
  return new Response(JSON.stringify({ error }), {
    status,
    headers: { "Content-Type": "application/json", ...CORS },
  });
}

/**
 * Resolve the caller's user id from their Supabase access token. Prefers a
 * LOCAL signature verification (no network) when JWT_SECRET is set; otherwise —
 * or on any verify failure (asymmetric signing keys, expired, malformed) —
 * falls back to GoTrue's getUser(). Returns null when the token is invalid by
 * both paths.
 */
async function resolveUserId(
  token: string,
  admin: ReturnType<typeof createClient>,
): Promise<string | null> {
  const key = await jwtKeyPromise;
  if (key) {
    try {
      const payload = await verify(token, key);
      const sub = payload?.sub;
      if (typeof sub === "string" && sub.length > 0) return sub;
    } catch (_e) {
      // Signature mismatch / expired / asymmetric keys — fall through to GoTrue,
      // which is authoritative and returns a clean null for genuinely dead tokens.
    }
  }
  const { data, error } = await admin.auth.getUser(token);
  if (error || !data?.user) return null;
  return data.user.id;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
  if (req.method !== "POST") return deny(405, "Method not allowed");

  // Identify the caller from their Supabase JWT.
  const userToken = req.headers.get("X-User-Token");
  if (!userToken) return deny(401, "Missing user token");

  const admin = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY);

  const userId = await resolveUserId(userToken, admin);
  if (!userId) return deny(401, "Invalid user token");

  // Entitlement check — derived from the server-owned columns only. Kept BEFORE
  // any streaming commit so a real 402 still reaches the client and drives the
  // paywall (e.g. trial-expired-mid-session), and so we never spend Claude
  // tokens for an unentitled caller.
  const { data: profile, error: profErr } = await admin
    .from("user_profiles")
    .select("is_pro, trial_ends_at")
    .eq("id", userId)
    .maybeSingle();

  if (profErr) {
    console.error("claude-proxy: profile read error:", profErr);
    return deny(500, "Entitlement lookup failed");
  }

  const inTrial = profile?.trial_ends_at != null &&
    new Date(profile.trial_ends_at).getTime() > Date.now();
  const entitled = profile?.is_pro === true || inTrial;

  if (!entitled) {
    console.log(`claude-proxy: 402 for ${userId} (is_pro=${profile?.is_pro} trial_ends_at=${profile?.trial_ends_at})`);
    return deny(402, "Local Pro required");
  }

  // Forward to Claude with the server-held key. Pass the upstream body straight
  // through so streaming (SSE, stream:true) and non-streaming JSON both work.
  const claudeApiKey = Deno.env.get("ANTHROPIC_API_KEY");
  if (!claudeApiKey) return deny(500, "ANTHROPIC_API_KEY not configured");

  let parsed: Record<string, unknown>;
  try {
    parsed = await req.json();
  } catch (_e) {
    return deny(400, "Invalid JSON body");
  }
  const body = JSON.stringify(parsed);
  const isStream = parsed?.stream === true;

  const claudeInit: RequestInit = {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "x-api-key": claudeApiKey,
      "anthropic-version": "2023-06-01",
    },
    body,
  };

  // ── Streaming path ──
  // Return the SSE response immediately and emit a keep-alive comment BEFORE
  // awaiting Claude, so the client's 30s socket-read timeout never trips on
  // Claude's header latency. Anthropic's own `ping` events cover the gaps once
  // the stream is flowing.
  if (isStream) {
    const stream = new ReadableStream<Uint8Array>({
      async start(controller) {
        // Resets the client's socket-read clock right away.
        controller.enqueue(encoder.encode(": keep-alive\n\n"));
        try {
          const claudeResponse = await fetch(CLAUDE_API_URL, claudeInit);

          if (!claudeResponse.ok || !claudeResponse.body) {
            const errText = await claudeResponse.text().catch(() => "");
            console.error(`claude-proxy: upstream ${claudeResponse.status}: ${errText.slice(0, 500)}`);
            // We've already committed a 200 SSE response, so surface the failure
            // as an SSE `error` event. The client's stream never sees a
            // message_stop terminator → treats the round as interrupted →
            // retries (same recovery path as the old non-2xx throw).
            controller.enqueue(encoder.encode(
              `event: error\ndata: ${JSON.stringify({ type: "error", error: { type: "upstream_error", status: claudeResponse.status } })}\n\n`,
            ));
            controller.close();
            return;
          }

          const reader = claudeResponse.body.getReader();
          while (true) {
            const { value, done } = await reader.read();
            if (done) break;
            if (value) controller.enqueue(value);
          }
          controller.close();
        } catch (error) {
          console.error("claude-proxy stream error:", error);
          controller.enqueue(encoder.encode(
            `event: error\ndata: ${JSON.stringify({ type: "error", error: { type: "proxy_error" } })}\n\n`,
          ));
          controller.close();
        }
      },
    });

    return new Response(stream, { status: 200, headers: SSE_HEADERS });
  }

  // ── Non-streaming path ──
  // Anthropic sends headers+body together at completion here, so there's no
  // first-byte to keep alive; pass the response straight through with its real
  // status. (These callers are background/non-critical and already guarded.)
  try {
    const claudeResponse = await fetch(CLAUDE_API_URL, claudeInit);
    return new Response(claudeResponse.body, {
      status: claudeResponse.status,
      headers: {
        "Content-Type": claudeResponse.headers.get("content-type") ?? "application/json",
        ...CORS,
      },
    });
  } catch (error) {
    console.error("claude-proxy error:", error);
    return deny(502, "Failed to proxy request to Claude");
  }
});
