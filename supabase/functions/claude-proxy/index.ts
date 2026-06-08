import "@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

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

const CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization, X-User-Token",
};

function deny(status: number, error: string): Response {
  return new Response(JSON.stringify({ error }), {
    status,
    headers: { "Content-Type": "application/json", ...CORS },
  });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
  if (req.method !== "POST") return deny(405, "Method not allowed");

  // Identify the caller from their Supabase JWT.
  const userToken = req.headers.get("X-User-Token");
  if (!userToken) return deny(401, "Missing user token");

  const admin = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY);

  const { data: userData, error: userErr } = await admin.auth.getUser(userToken);
  if (userErr || !userData?.user) return deny(401, "Invalid user token");
  const userId = userData.user.id;

  // Entitlement check — derived from the server-owned columns only.
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

  let body: string;
  try {
    body = JSON.stringify(await req.json());
  } catch (_e) {
    return deny(400, "Invalid JSON body");
  }

  try {
    const claudeResponse = await fetch(CLAUDE_API_URL, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-api-key": claudeApiKey,
        "anthropic-version": "2023-06-01",
      },
      body,
    });

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
