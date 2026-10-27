// Instagram Graph API — carousel publishing.
//
// Flow (all after human approval, in publish.mjs):
//   1. one child container per image URL  (is_carousel_item=true)
//   2. one CAROUSEL container listing the children, with the caption
//   3. poll the carousel container until status_code=FINISHED
//   4. media_publish the carousel container
//
// Requires an Instagram Business/Creator account linked to a Facebook Page and
// a long-lived token with instagram_basic + instagram_content_publish +
// pages_read_engagement. The image URLs must be publicly reachable (we host
// them in a public Supabase Storage bucket).

import { igEnv, IG_API_VERSION } from "./config.mjs";

const BASE = `https://graph.facebook.com/${IG_API_VERSION}`;

async function post(pathPart, params) {
  const { token } = igEnv();
  const body = new URLSearchParams({ ...params, access_token: token });
  const res = await fetch(`${BASE}/${pathPart}`, { method: "POST", body });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(`IG ${res.status} ${pathPart}: ${JSON.stringify(json.error || json)}`);
  return json;
}

export async function createChild(imageUrl) {
  const { userId } = igEnv();
  const { id } = await post(`${userId}/media`, { image_url: imageUrl, is_carousel_item: "true" });
  return id;
}

export async function createCarousel(childIds, caption) {
  const { userId } = igEnv();
  const { id } = await post(`${userId}/media`, {
    media_type: "CAROUSEL", children: childIds.join(","), caption,
  });
  return id;
}

export async function waitFinished(containerId, { tries = 20, delayMs = 3000 } = {}) {
  const { token } = igEnv();
  for (let i = 0; i < tries; i++) {
    const res = await fetch(`${BASE}/${containerId}?fields=status_code,status&access_token=${token}`);
    const json = await res.json().catch(() => ({}));
    const code = json.status_code;
    if (code === "FINISHED") return;
    if (code === "ERROR" || code === "EXPIRED") throw new Error(`IG container ${containerId} ${code}: ${json.status || ""}`);
    await new Promise((r) => setTimeout(r, delayMs));
  }
  throw new Error(`IG container ${containerId} not FINISHED after ${tries} polls`);
}

export async function publishCarousel(creationId) {
  const { userId } = igEnv();
  const { id } = await post(`${userId}/media_publish`, { creation_id: creationId });
  return id;
}
