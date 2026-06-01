/// <reference types="https://esm.sh/@supabase/functions-js/src/edge-runtime.d.ts" />

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization",
};

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
// Anon key is enough — `feature_flags` has a public SELECT policy and no
// secrets live on the row. Avoids minting service-role access for a health
// probe endpoint.
const SUPABASE_ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY")!;

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response(null, { headers: CORS_HEADERS });
  }
  if (req.method !== "GET") {
    return new Response(
      JSON.stringify({ ok: false, error: "method not allowed" }),
      { status: 405, headers: { "Content-Type": "application/json", ...CORS_HEADERS } },
    );
  }

  try {
    const client = createClient(SUPABASE_URL, SUPABASE_ANON_KEY);
    const { data, error } = await client
      .from("feature_flags")
      .select("name, enabled");

    if (error) {
      console.error("healthz select error:", error);
      return new Response(
        JSON.stringify({ ok: false, error: error.message }),
        { status: 500, headers: { "Content-Type": "application/json", ...CORS_HEADERS } },
      );
    }

    const flags: Record<string, boolean> = {};
    for (const row of data || []) flags[row.name] = !!row.enabled;

    return new Response(
      JSON.stringify({ ok: true, flags }),
      { status: 200, headers: { "Content-Type": "application/json", ...CORS_HEADERS } },
    );
  } catch (error) {
    console.error("healthz error:", error);
    return new Response(
      JSON.stringify({ ok: false, error: String(error) }),
      { status: 500, headers: { "Content-Type": "application/json", ...CORS_HEADERS } },
    );
  }
});
