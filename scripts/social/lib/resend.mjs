// Preview email via Resend (already used by the repo's Actions for run alerts).
// Sends the caption + the rendered slide images (by their public Storage URLs)
// and a link to approve the publish. No-op if RESEND_API_KEY/SOCIAL_ALERT_EMAIL
// are unset.

import { resendEnv } from "./config.mjs";

export async function sendEmail({ subject, html }) {
  const { key, to, from } = resendEnv();
  if (!key || !to) {
    console.log("[social] Resend not configured (RESEND_API_KEY/SOCIAL_ALERT_EMAIL) — skipping email.");
    return false;
  }
  const res = await fetch("https://api.resend.com/emails", {
    method: "POST",
    headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
    body: JSON.stringify({ from, to: to.split(",").map((s) => s.trim()), subject, html }),
  });
  if (!res.ok) {
    console.error(`[social] Resend ${res.status}: ${await res.text()}`);
    return false;
  }
  return true;
}

export function previewHtml({ title, caption, imageUrls, runUrl }) {
  const imgs = imageUrls.map((u, i) =>
    `<img src="${u}" alt="slide ${i + 1}" width="270" style="border-radius:12px;border:1px solid #eee;margin:0 8px 8px 0">`
  ).join("");
  const approve = runUrl
    ? `<p style="margin:20px 0"><a href="${runUrl}" style="background:#FF6B6B;color:#fff;text-decoration:none;font-weight:800;padding:12px 20px;border-radius:12px;display:inline-block;font-family:Arial,sans-serif">Review & approve publish →</a></p>
       <p style="color:#8A849A;font-size:13px;font-family:Arial,sans-serif">Opens the GitHub Actions run. Approve the <b>instagram-publish</b> environment to post, or let it expire to skip.</p>`
    : "";
  return `<div style="max-width:620px;margin:0 auto;font-family:Arial,sans-serif;color:#1A1523">
    <h2 style="font-size:20px">${title}</h2>
    ${approve}
    <div style="margin:16px 0">${imgs}</div>
    <h3 style="font-size:15px;margin-bottom:6px">Caption</h3>
    <pre style="white-space:pre-wrap;background:#FAF8FC;border:1px solid #eee;border-radius:12px;padding:14px;font-family:Arial,sans-serif;font-size:14px;line-height:1.5">${caption.replace(/</g, "&lt;")}</pre>
  </div>`;
}
