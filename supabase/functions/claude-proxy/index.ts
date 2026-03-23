import "@supabase/functions-js/edge-runtime.d.ts";

const CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";

Deno.serve(async (req) => {
  // CORS preflight
  if (req.method === "OPTIONS") {
    return new Response(null, {
      headers: {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Methods": "POST, OPTIONS",
        "Access-Control-Allow-Headers": "Content-Type, Authorization",
      },
    });
  }

  if (req.method !== "POST") {
    return new Response(JSON.stringify({ error: "Method not allowed" }), {
      status: 405,
      headers: { "Content-Type": "application/json" },
    });
  }

  // Verify the request has a valid Supabase anon/auth token
  const authHeader = req.headers.get("Authorization");
  if (!authHeader?.startsWith("Bearer ")) {
    return new Response(JSON.stringify({ error: "Missing authorization" }), {
      status: 401,
      headers: { "Content-Type": "application/json" },
    });
  }

  // Get the Claude API key from Supabase secrets
  const claudeApiKey = Deno.env.get("ANTHROPIC_API_KEY");
  if (!claudeApiKey) {
    return new Response(
      JSON.stringify({ error: "ANTHROPIC_API_KEY not configured in secrets" }),
      { status: 500, headers: { "Content-Type": "application/json" } },
    );
  }

  try {
    // Forward the request body directly to Claude
    const body = await req.json();

    const claudeResponse = await fetch(CLAUDE_API_URL, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-api-key": claudeApiKey,
        "anthropic-version": "2023-06-01",
      },
      body: JSON.stringify(body),
    });

    const claudeData = await claudeResponse.json();

    return new Response(JSON.stringify(claudeData), {
      status: claudeResponse.status,
      headers: {
        "Content-Type": "application/json",
        "Access-Control-Allow-Origin": "*",
      },
    });
  } catch (error) {
    console.error("Claude proxy error:", error);
    return new Response(
      JSON.stringify({ error: "Failed to proxy request to Claude" }),
      { status: 502, headers: { "Content-Type": "application/json" } },
    );
  }
});
