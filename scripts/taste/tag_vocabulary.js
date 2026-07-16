// Controlled, faceted tag vocabulary for the taste graph.
//
// WHY CONTROLLED (not free-form): the tags are machine features that power
// collaborative filtering. Free-form tagging fragments ("small" vs "intimate"
// vs "cozy" become three tokens) and destroys the feature co-occurrence signal.
// The classifier LLM must map every event into THIS vocabulary; it may not
// invent tokens. The vocabulary grows only through a reviewed edit here + a
// bumped TAG_VOCAB_VERSION.
//
// WHY FACETED: grouping forces the model to describe each event along several
// independent axes instead of emitting five near-synonyms from one axis. The
// classifier is asked to fill ~1 tag per applicable facet, up to TAG_MAX total.
//
// WHAT'S NOT HERE: entities (artist names, venue names, brands/series). Those
// are high-cardinality and extracted into events.performers / events.brands /
// events.team_name — NEVER into this vocab. See 20260716140000_event_taste_features.sql.

const TAG_VOCAB_VERSION = "v1";

// Target 10+ tags per event, capped so the model stays deliberate.
const TAG_MIN = 6;
const TAG_MAX = 15;

// facet -> allowed tokens. Order = rough priority for the prompt.
const TAG_FACETS = {
  // WHO it's for.
  audience: [
    "family-friendly", "kids", "teens", "adults-only", "21+", "seniors",
    "date-night", "solo-friendly", "group-activity", "girls-night",
    "dog-friendly", "beginner-friendly", "lgbtq",
  ],

  // WHERE / physical setting.
  setting: [
    "indoor", "outdoor", "rooftop", "waterfront", "park", "beach",
    "brewery-taproom", "winery", "distillery", "bar-club", "restaurant",
    "gallery", "theater-venue", "arena-stadium", "studio", "warehouse",
    "historic-site", "campus", "library-branch", "market-hall", "virtual",
  ],

  // The FORM of the event.
  format: [
    "live-performance", "concert", "dj-set", "standup-set", "screening",
    "exhibit", "class-workshop", "guided-tour", "festival", "fair",
    "market", "tasting", "competition", "game", "meetup", "talk-lecture",
    "party", "reading", "open-mic", "pop-up", "seasonal-event", "watch-party",
  ],

  // The FEEL.
  vibe: [
    "chill", "high-energy", "intimate", "rowdy", "upscale", "dive-y",
    "artsy", "romantic", "quirky", "nostalgic", "spooky", "meditative",
    "immersive", "hands-on", "educational", "competitive", "wholesome",
    "underground", "trendy",
  ],

  // Cost & commitment.
  commitment: [
    "free", "cheap", "moderate-price", "splurge", "ticketed", "rsvp",
    "drop-in", "reservations", "all-day", "quick",
  ],

  // Draw / rarity — feeds "worth the drive" & travel propensity.
  scale: [
    "niche", "small-crowd", "big-crowd", "marquee", "one-time", "recurring",
    "hidden-gem", "boston-essential", "must-see", "buzzworthy",
  ],

  // WHAT it's about — the topical interests.
  interest: [
    "live-music", "local-artist", "touring-act", "electronic-music",
    "hip-hop", "rock", "jazz-blues", "country-folk", "classical-music",
    "comedy", "improv", "drag", "theater", "musical-theater", "dance",
    "film", "visual-art", "photography", "literary", "history", "science",
    "nature", "wellness", "fitness", "yoga", "running", "cycling",
    "pro-sports", "college-sports", "minor-league", "motorsports",
    "foodie", "craft-beer", "wine", "cocktails", "coffee", "bbq",
    "vegan", "farmers-market", "crafts", "gaming", "tech", "fashion",
    "nightlife", "trivia", "karaoke", "holiday", "charity", "community",
    "networking", "kids-learning", "outdoors", "adventure",
  ],
};

// Flat list for validation / prompt rendering.
const ALL_TAGS = Object.values(TAG_FACETS).flat();

// Sanity: no token appears in two facets (would confuse co-occurrence).
(function assertNoDuplicates() {
  const seen = new Set();
  for (const t of ALL_TAGS) {
    if (seen.has(t)) throw new Error(`Duplicate tag token in vocabulary: ${t}`);
    seen.add(t);
  }
})();

module.exports = { TAG_VOCAB_VERSION, TAG_MIN, TAG_MAX, TAG_FACETS, ALL_TAGS };
