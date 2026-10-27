// Port of supabase/functions/_shared/images.ts thumbUrl() for Node.
//
// Rewrites a Supabase Storage public-object URL to the CDN-cached render
// endpoint at a given width, so we never pull the full-size original (the
// 2026-10 egress outage). Non-Storage URLs (e.g. places.googleapis.com, which
// carry an embedded key) are returned untouched. For IG cards we render wider
// than the 400px app cards — the photo fills a 1080px-wide slot.

const STORAGE_OBJECT = "/storage/v1/object/public/";
const STORAGE_RENDER = "/storage/v1/render/image/public/";

export function thumbUrl(url, width = 1080, quality = 80) {
  if (!url || typeof url !== "string") return url;
  if (!url.includes(STORAGE_OBJECT)) return url; // not Supabase Storage
  if (url.includes(STORAGE_RENDER)) return url; // already transformed
  const rendered = url.replace(STORAGE_OBJECT, STORAGE_RENDER);
  const sep = rendered.includes("?") ? "&" : "?";
  return `${rendered}${sep}width=${width}&quality=${quality}`;
}
