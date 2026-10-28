// Supabase Storage — host the rendered JPEGs at a public URL so the Instagram
// Graph API can fetch them. Bucket is created on first run if missing.

import { readFile } from "node:fs/promises";
import path from "node:path";
import { supabaseEnv, STORAGE_BUCKET } from "./config.mjs";

export async function ensureBucket() {
  const { url, key } = supabaseEnv();
  const res = await fetch(`${url}/storage/v1/bucket`, {
    method: "POST",
    headers: { apikey: key, Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
    body: JSON.stringify({ id: STORAGE_BUCKET, name: STORAGE_BUCKET, public: true }),
  });
  // 400 "already exists" is fine; anything else is a real error.
  if (!res.ok) {
    const txt = await res.text();
    if (!/exist/i.test(txt)) throw new Error(`create bucket ${res.status}: ${txt}`);
  }
}

// Uploads a rendered slide to {prefix}/{basename} and returns its public URL.
export async function uploadImage(localPath, prefix) {
  const { url, key } = supabaseEnv();
  const objectPath = `${prefix}/${path.basename(localPath)}`;
  const bytes = await readFile(localPath);
  const res = await fetch(`${url}/storage/v1/object/${STORAGE_BUCKET}/${objectPath}`, {
    method: "POST",
    headers: {
      apikey: key, Authorization: `Bearer ${key}`,
      "Content-Type": localPath.endsWith(".png") ? "image/png" : "image/jpeg", "x-upsert": "true", "cache-control": "max-age=86400",
    },
    body: bytes,
  });
  if (!res.ok) throw new Error(`upload ${objectPath} ${res.status}: ${await res.text()}`);
  return `${url}/storage/v1/object/public/${STORAGE_BUCKET}/${objectPath}`;
}

// Per-day "already published" marker, so a manual run plus the scheduled run
// (or a rerun) can't post the same day twice.
function markerPath(postType, date) { return `${postType}/${date}/published.json`; }

export async function wasPublished(postType, date) {
  const { url } = supabaseEnv();
  const res = await fetch(`${url}/storage/v1/object/public/${STORAGE_BUCKET}/${markerPath(postType, date)}`, { method: "HEAD" });
  return res.ok;
}

export async function markPublished(postType, date, mediaId) {
  const { url, key } = supabaseEnv();
  const res = await fetch(`${url}/storage/v1/object/${STORAGE_BUCKET}/${markerPath(postType, date)}`, {
    method: "POST",
    headers: { apikey: key, Authorization: `Bearer ${key}`, "Content-Type": "application/json", "x-upsert": "true" },
    body: JSON.stringify({ mediaId, publishedAt: new Date().toISOString() }),
  });
  if (!res.ok) throw new Error(`marker ${res.status}: ${await res.text()}`);
}
