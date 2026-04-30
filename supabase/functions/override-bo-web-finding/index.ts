import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

// ════════════════════════════════════════════════════════════════════════════
// override-bo-web-finding: user-side "I've verified this" override for a
// rejected bo_web_event. Bumps user_verified_count and lets the slot's
// warning disappear in the user's plan view. Does NOT promote the row to
// the public events table — rejected rows stay private to the user.
//
// Endpoint: POST / { user_id, id, kind: "event"|"venue" }
// ════════════════════════════════════════════════════════════════════════════

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "authorization, content-type",
};

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
  if (req.method !== "POST") return json({ error: "POST only" }, 405);

  const body = await req.json().catch(() => null);
  if (!body?.user_id || !body?.id) return json({ error: "user_id and id required" }, 400);
  const kind = body.kind === "venue" ? "venue" : "event";
  const table = kind === "event" ? "bo_web_events" : "bo_web_venues";

  const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY);

  // Read-modify-write since PostgREST doesn't support arithmetic update.
  const { data: row, error: getErr } = await supabase
    .from(table)
    .select("user_verified_count, created_by_user_id")
    .eq("id", body.id)
    .single();
  if (getErr || !row) return json({ error: "not found" }, 404);
  if (row.created_by_user_id !== body.user_id) {
    // A different user can't override another user's row — defense against
    // someone POSTing a uuid they happen to know.
    return json({ error: "forbidden" }, 403);
  }
  const next = (row.user_verified_count ?? 0) + 1;
  const { error: updErr } = await supabase
    .from(table)
    .update({ user_verified_count: next, updated_at: new Date().toISOString() })
    .eq("id", body.id);
  if (updErr) return json({ error: updErr.message }, 500);
  return json({ user_verified_count: next });
});

function json(body: any, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...CORS },
  });
}
