/// <reference types="https://esm.sh/@supabase/functions-js/src/edge-runtime.d.ts" />

const GOOGLE_PLACES_API_KEY = Deno.env.get("GOOGLE_PLACES_API_KEY");

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization",
};

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response(null, { headers: CORS_HEADERS });
  }

  if (!GOOGLE_PLACES_API_KEY) {
    return new Response(
      JSON.stringify({ error: "GOOGLE_PLACES_API_KEY not configured" }),
      { status: 500, headers: { "Content-Type": "application/json", ...CORS_HEADERS } },
    );
  }

  try {
    const body = await req.json();
    const { query, placeId } = body;

    // Resolve a place ID to lat/lng
    if (placeId) {
      const params = new URLSearchParams({
        place_id: placeId,
        fields: "geometry,formatted_address,address_components",
        key: GOOGLE_PLACES_API_KEY,
      });
      const detailRes = await fetch(
        `https://maps.googleapis.com/maps/api/place/details/json?${params}`,
      );
      if (!detailRes.ok) {
        return new Response(
          JSON.stringify({ error: "Failed to resolve place" }),
          { status: 200, headers: { "Content-Type": "application/json", ...CORS_HEADERS } },
        );
      }
      const detail = await detailRes.json();
      const result = detail.result;
      const lat = result?.geometry?.location?.lat;
      const lng = result?.geometry?.location?.lng;
      const address = result?.formatted_address || "";

      // Extract city from address components
      let city = "";
      for (const comp of result?.address_components || []) {
        if (comp.types?.includes("locality")) {
          city = comp.long_name || comp.short_name || "";
          break;
        }
        if (comp.types?.includes("sublocality_level_1") && !city) {
          city = comp.long_name || comp.short_name || "";
        }
      }

      return new Response(
        JSON.stringify({ lat, lng, address, city }),
        { status: 200, headers: { "Content-Type": "application/json", ...CORS_HEADERS } },
      );
    }

    // Autocomplete suggestions
    if (!query || query.length < 2) {
      return new Response(
        JSON.stringify({ suggestions: [] }),
        { status: 200, headers: { "Content-Type": "application/json", ...CORS_HEADERS } },
      );
    }

    // Use Google Places Autocomplete (legacy) API
    const params = new URLSearchParams({
      input: query,
      types: "geocode",
      components: "country:us",
      key: GOOGLE_PLACES_API_KEY,
    });
    const response = await fetch(
      `https://maps.googleapis.com/maps/api/place/autocomplete/json?${params}`,
    );

    if (!response.ok) {
      const errText = await response.text();
      console.error("Places Autocomplete error:", errText);
      return new Response(
        JSON.stringify({ suggestions: [], error: "Places API error" }),
        { status: 200, headers: { "Content-Type": "application/json", ...CORS_HEADERS } },
      );
    }

    const data = await response.json();
    if (data.status !== "OK" && data.status !== "ZERO_RESULTS") {
      console.error("Places Autocomplete status:", data.status, data.error_message);
      return new Response(
        JSON.stringify({ suggestions: [], error: data.error_message || data.status }),
        { status: 200, headers: { "Content-Type": "application/json", ...CORS_HEADERS } },
      );
    }

    const suggestions = (data.predictions || [])
      .slice(0, 5)
      .map((p: any) => ({
        placeId: p.place_id,
        description: p.description || "",
        mainText: p.structured_formatting?.main_text || "",
        secondaryText: p.structured_formatting?.secondary_text || "",
      }));

    return new Response(
      JSON.stringify({ suggestions }),
      { status: 200, headers: { "Content-Type": "application/json", ...CORS_HEADERS } },
    );
  } catch (error) {
    console.error("geocode error:", error);
    return new Response(
      JSON.stringify({ error: String(error) }),
      { status: 500, headers: { "Content-Type": "application/json", ...CORS_HEADERS } },
    );
  }
});
