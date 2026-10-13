// ════════════════════════════════════════════════════════════════════════════
// images.ts — serve Supabase Storage photos at display size, not source size.
//
// WHY: the 2026-10-11 outage was an `exceed_egress_quota` restriction that took
// the whole Supabase org (prod AND dev) offline for ~2.5 days — REST, Storage,
// Edge Functions and Auth all 402'd, so live clients could not even sign in.
//
// Root cause is a cost-shift. The 2026-08 photo migration moved ~6.5k venue and
// ~75k event images off Google Places (to stop the Places Photo bill) and onto
// Supabase Storage — but re-hosted the ORIGINALS. Measured 2026-10-13 over a
// 25-photo sample: mean 277 KB, median 253 KB, max 875 KB. A single pass over
// the 7,539 Storage venue photos is ~2.14 GB, and those bytes are served to
// phone cards a few hundred px wide.
//
// Supabase's render endpoint resizes on the fly and is CDN-cached
// (`cf-cache-status: HIT`), so each variant is computed once, not per request.
// Measured on the same source image (258 KB original):
//
//     width   jpeg     webp
//     300     46 KB    39 KB
//     400     59 KB    49 KB      <- card/list default
//     600     83 KB    68 KB
//     800    108 KB    87 KB
//
// Scope is deliberately CARD/LIST ONLY. This module is applied in
// `jsonCompressed`, which is imported solely by carousels / explore-events /
// explore-places — the high-volume surfaces a user scrolls past dozens of at a
// time. Detail screens read the row straight from PostgREST
// (`EventRepositoryImpl`), so the full-resolution original still backs the
// 280dp full-width hero in EventDetailScreen and is untouched here.
//
// Server-side by design: every shipped client version benefits immediately,
// with no app update and no DB rewrite.
// ════════════════════════════════════════════════════════════════════════════

const STORAGE_OBJECT = "/storage/v1/object/public/";
const STORAGE_RENDER = "/storage/v1/render/image/public/";

// Response fields that carry a displayable PHOTO url.
//
// Compared case- and underscore-insensitively because the endpoints disagree on
// casing: carousels emits snake_case (image_url, venue_photo_url) while
// explore-events / explore-places emit camelCase (imageUrl, photoUrl). Matching
// only snake_case silently missed 220 images on a single explore-places
// response — caught on dev before this ever reached prod.
//
export const DEFAULT_THUMB_WIDTH = 400;
export const DEFAULT_THUMB_QUALITY = 70;

// `icon_url` WAS excluded here on the assumption that category icons are small.
// Measured on prod 2026-10-13, they are not: arts.png 1,200 KB, food.png 1,373 KB,
// comedy.png 675 KB — 12 distinct icons averaging ~740 KB, ~8.9 MB per home-feed
// load. They render at icon size, so they get a much smaller width than photos.
// Verified the render endpoint returns image/png for a PNG source, so alpha
// survives (a JPEG conversion would have put a black box behind every icon).
const ICON_WIDTH = 128;

/** image-bearing response field -> render width. Key compared case/underscore-insensitively. */
const IMAGE_KEYS = new Map<string, number>([
  ["imageurl", DEFAULT_THUMB_WIDTH],
  ["photourl", DEFAULT_THUMB_WIDTH],
  ["venuephotourl", DEFAULT_THUMB_WIDTH],
  ["iconurl", ICON_WIDTH],
]);

function imageKeyWidth(key: string): number | undefined {
  return IMAGE_KEYS.get(key.replace(/_/g, "").toLowerCase());
}


/**
 * Rewrite one Supabase Storage public-object URL to its render-endpoint
 * equivalent. Anything else is returned untouched — notably the ~52k venue rows
 * still on `places.googleapis.com`, which must keep their embedded key intact.
 */
export function thumbUrl(
  url: string,
  width = DEFAULT_THUMB_WIDTH,
  quality = DEFAULT_THUMB_QUALITY,
): string {
  if (!url.includes(STORAGE_OBJECT)) return url;   // not Supabase Storage
  if (url.includes(STORAGE_RENDER)) return url;    // already transformed
  const rendered = url.replace(STORAGE_OBJECT, STORAGE_RENDER);
  const sep = rendered.includes("?") ? "&" : "?";
  return `${rendered}${sep}width=${width}&quality=${quality}`;
}

/**
 * Deep-walk a response payload, rewriting every Storage image URL found under a
 * known image key. Pure — returns a new structure and never mutates the input,
 * so a cached payload object stays canonical. Idempotent.
 */
export function thumbnailStorageImages<T>(
  value: T,
  width = DEFAULT_THUMB_WIDTH,
  quality = DEFAULT_THUMB_QUALITY,
): T {
  const walk = (v: unknown): unknown => {
    if (Array.isArray(v)) return v.map(walk);
    if (v !== null && typeof v === "object") {
      const out: Record<string, unknown> = {};
      for (const [k, val] of Object.entries(v as Record<string, unknown>)) {
        const keyWidth = typeof val === "string" ? imageKeyWidth(k) : undefined;
        out[k] = keyWidth !== undefined
          ? thumbUrl(val as string, Math.min(keyWidth, width), quality)
          : walk(val);
      }
      return out;
    }
    return v;
  };
  return walk(value) as T;
}
