import "@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const GOOGLE_PLACES_API_KEY = Deno.env.get("GOOGLE_PLACES_API_KEY");

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization",
};

Deno.serve(async (req) => {
  // CORS preflight
  if (req.method === "OPTIONS") {
    return new Response(null, { headers: CORS_HEADERS });
  }

  if (req.method !== "POST") {
    return new Response(JSON.stringify({ error: "Method not allowed" }), {
      status: 405,
      headers: { "Content-Type": "application/json", ...CORS_HEADERS },
    });
  }

  // Verify auth
  const authHeader = req.headers.get("Authorization");
  if (!authHeader?.startsWith("Bearer ")) {
    return new Response(JSON.stringify({ error: "Missing authorization" }), {
      status: 401,
      headers: { "Content-Type": "application/json", ...CORS_HEADERS },
    });
  }

  if (!GOOGLE_PLACES_API_KEY) {
    return new Response(
      JSON.stringify({ error: "GOOGLE_PLACES_API_KEY not configured in secrets" }),
      { status: 500, headers: { "Content-Type": "application/json", ...CORS_HEADERS } },
    );
  }

  try {
    const { name, city, cuisine_or_type } = await req.json();

    if (!name || !city) {
      return new Response(
        JSON.stringify({ error: "name and city are required" }),
        { status: 400, headers: { "Content-Type": "application/json", ...CORS_HEADERS } },
      );
    }

    const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY);

    // 1. Check if venue already exists in DB
    const { data: existing } = await supabase
      .from("venues")
      .select("*")
      .ilike("name", name)
      .ilike("city", city)
      .limit(1);

    if (existing && existing.length > 0) {
      const venue = existing[0];
      return new Response(
        JSON.stringify({
          status: "existing",
          venue: {
            id: venue.id,
            name: venue.name,
            address: venue.address,
            city: venue.city,
            state: venue.state,
            lat: venue.lat,
            lng: venue.lng,
            category: venue.category,
            google_rating: venue.google_rating,
            website: venue.website,
            photo_url: venue.photo_url,
            reservation_url: venue.reservation_url,
          },
        }),
        { status: 200, headers: { "Content-Type": "application/json", ...CORS_HEADERS } },
      );
    }

    // 2. Validate via Google Places Text Search API
    const textQuery = `${name} ${cuisine_or_type || ""} in ${city}`.trim();
    const placesResponse = await fetch(
      "https://places.googleapis.com/v1/places:searchText",
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Goog-Api-Key": GOOGLE_PLACES_API_KEY,
          "X-Goog-FieldMask":
            "places.id,places.displayName,places.formattedAddress,places.location,places.rating,places.websiteUri,places.photos,places.types,places.editorialSummary,places.priceLevel,places.currentOpeningHours,places.userRatingCount",
        },
        body: JSON.stringify({ textQuery }),
      },
    );

    if (!placesResponse.ok) {
      const errText = await placesResponse.text();
      console.error("Google Places error:", errText);
      return new Response(
        JSON.stringify({ error: "Google Places API error", details: errText }),
        { status: 502, headers: { "Content-Type": "application/json", ...CORS_HEADERS } },
      );
    }

    const placesData = await placesResponse.json();
    const places = placesData.places;

    if (!places || places.length === 0) {
      return new Response(
        JSON.stringify({
          error: "Venue not found",
          message: `Could not find "${name}" in ${city} via Google Places.`,
        }),
        { status: 200, headers: { "Content-Type": "application/json", ...CORS_HEADERS } },
      );
    }

    // Take the first (best) match
    const place = places[0];
    const displayName = place.displayName?.text || name;
    const formattedAddress = place.formattedAddress || "";
    const lat = place.location?.latitude;
    const lng = place.location?.longitude;
    const rating = place.rating;
    const ratingCount = place.userRatingCount || 0;
    const website = place.websiteUri;
    const description = place.editorialSummary?.text || null;
    const priceLevel = place.priceLevel || null; // PRICE_LEVEL_FREE, PRICE_LEVEL_INEXPENSIVE, etc.
    const hours = place.currentOpeningHours?.weekdayDescriptions || null;

    // Extract photo URL if available
    let photoUrl: string | null = null;
    if (place.photos && place.photos.length > 0) {
      const photoRef = place.photos[0].name;
      photoUrl = `https://places.googleapis.com/v1/${photoRef}/media?maxHeightPx=400&maxWidthPx=400&key=${GOOGLE_PLACES_API_KEY}`;
    }

    // Parse state from formatted address (e.g. "123 Main St, Boston, MA 02101, USA")
    const addressParts = formattedAddress.split(",").map((s: string) => s.trim());
    let state: string | null = null;
    if (addressParts.length >= 3) {
      // Second-to-last part often contains "MA 02101" or similar
      const stateZip = addressParts[addressParts.length - 2];
      const stateMatch = stateZip.match(/^([A-Z]{2})\s/);
      if (stateMatch) {
        state = stateMatch[1];
      }
    }

    // Determine category from types or cuisine_or_type input
    let category = cuisine_or_type || null;
    if (!category && place.types) {
      if (place.types.includes("restaurant")) category = "restaurant";
      else if (place.types.includes("bar")) category = "bar";
      else if (place.types.includes("cafe")) category = "cafe";
      else if (place.types.includes("night_club")) category = "bar";
      else if (place.types.includes("bakery")) category = "cafe";
    }

    // 4. Check for reservation platform (Resy / OpenTable)
    let reservationUrl: string | null = null;
    const isDining = category === "restaurant" || category === "bar" || category === "cafe" ||
      (place.types && (place.types.includes("restaurant") || place.types.includes("bar")));

    if (isDining) {
      const slug = displayName.toLowerCase().replace(/[^a-z0-9\s-]/g, "").replace(/\s+/g, "-");
      const citySlug = city.toLowerCase().replace(/[^a-z0-9\s-]/g, "").replace(/\s+/g, "-");
      const stateSlug = state ? state.toLowerCase() : "ma";

      // Check OpenTable: /r/{name-city}
      const otUrl = `https://www.opentable.com/r/${slug}-${citySlug}`;
      try {
        const otRes = await fetch(otUrl, { method: "HEAD", redirect: "follow" });
        if (otRes.ok && !otRes.url.includes("/s?")) {
          reservationUrl = otUrl;
        }
      } catch (_) { /* ignore */ }

      // Resy is a React SPA — returns 200 for all URLs, can't distinguish valid
      // from invalid server-side. Skipping until we have Resy API access.

      if (reservationUrl) {
        console.log(`Found reservation URL for ${displayName}: ${reservationUrl}`);
      }
    }

    // 5. Insert into venues table
    const newVenue = {
      name: displayName,
      city: city,
      state: state,
      lat: lat,
      lng: lng,
      google_rating: rating,
      website: website,
      photo_url: photoUrl,
      description: description,
      category: category,
      place_type: category,
      is_active: true,
      metro_area: city?.toLowerCase() || null,
      reservation_url: reservationUrl,
    };

    const { data: inserted, error: insertError } = await supabase
      .from("venues")
      .insert(newVenue)
      .select()
      .single();

    if (insertError) {
      console.error("Insert error:", insertError);
      // Still return the venue data even if insert fails
      return new Response(
        JSON.stringify({
          status: "validated_not_saved",
          venue: newVenue,
          error: insertError.message,
        }),
        { status: 200, headers: { "Content-Type": "application/json", ...CORS_HEADERS } },
      );
    }

    return new Response(
      JSON.stringify({
        status: "created",
        venue: {
          id: inserted.id,
          name: inserted.name,
          address: inserted.address,
          city: inserted.city,
          state: inserted.state,
          lat: inserted.lat,
          lng: inserted.lng,
          category: inserted.category,
          google_rating: inserted.google_rating,
          website: inserted.website,
          photo_url: inserted.photo_url,
          description: inserted.description,
          reservation_url: inserted.reservation_url,
        },
      }),
      { status: 200, headers: { "Content-Type": "application/json", ...CORS_HEADERS } },
    );
  } catch (error) {
    console.error("validate-venue error:", error);
    return new Response(
      JSON.stringify({ error: "Internal server error", message: String(error) }),
      { status: 500, headers: { "Content-Type": "application/json", ...CORS_HEADERS } },
    );
  }
});
