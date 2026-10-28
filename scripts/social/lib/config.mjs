// Central config + env for the social pipeline. All secrets come from the
// environment (GitHub Actions secrets); nothing is hard-coded here.

export const POST_TYPES = ["daily", "weekend", "monthly"];

// Boston metro center + local radius. Matches the carousels edge function's
// centroid (supabase/functions/carousels/index.ts) and its bbox math
// (lat delta = miles/69, lng delta = miles/50 at Boston's latitude).
export const BOSTON = { lat: 42.3601, lng: -71.0589 };
export const RADIUS_MILES = 25;

// How many event cards each post shows in the carousel. Instagram caps a
// carousel at 10 images, and every post keeps a hero + a download card, so the
// event cards are capped at 8. The caption lists any events beyond that.
export const MAX_EVENT_CARDS = 8;

// Minimum events required to post. Below this the run is skipped and an email
// is sent instead — a 2-event "lineup" is worse than no post.
export const MIN_EVENTS = { daily: 4, weekend: 5, monthly: 6 };

// The "headline count" shown on the hero / in the caption. Capped per type so
// the number reads naturally even when the DB returns more.
export const HEADLINE_CAP = { daily: 9, weekend: 12, monthly: 10 };

export const STORAGE_BUCKET = "social-posts";
export const IG_API_VERSION = "v21.0";

export const STORE = {
  ios: "https://apps.apple.com/app/id6761425705",
  android: "https://play.google.com/store/apps/details?id=live.localapp.local",
  web: "https://localapp.live",
};

function required(name) {
  const v = process.env[name];
  if (!v) throw new Error(`Missing required env var: ${name}`);
  return v;
}

export function supabaseEnv() {
  return {
    url: process.env.SUPABASE_URL || "https://blifubxslaucshmlphdc.supabase.co",
    key: required("SUPABASE_SERVICE_KEY"),
  };
}

export function igEnv() {
  return { userId: required("IG_USER_ID"), token: required("IG_ACCESS_TOKEN") };
}

export function resendEnv() {
  // Optional: if unset, the preview email is skipped (the run still stages).
  return {
    key: process.env.RESEND_API_KEY || "",
    to: process.env.SOCIAL_ALERT_EMAIL || "",
    // Same sender as the existing alert emails — localapp.live isn't verified in Resend.
    from: process.env.SOCIAL_FROM_EMAIL || "Local Social <onboarding@resend.dev>",
  };
}

// The GitHub Actions run URL, for the "approve to publish" link in the email.
export function runUrl() {
  const s = process.env.GITHUB_SERVER_URL, r = process.env.GITHUB_REPOSITORY, id = process.env.GITHUB_RUN_ID;
  return s && r && id ? `${s}/${r}/actions/runs/${id}` : "";
}
