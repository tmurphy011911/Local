// Publish job: takes a staged manifest and posts it to Instagram.
//
//   node publish.mjs <daily|weekend|monthly>
//
// Runs ONLY after the instagram-publish environment is approved (the review
// gate), so reaching this point is the human "yes". Reads out/<type>/manifest
// .json, builds the carousel from the already-uploaded image URLs, and
// publishes. DRY_RUN=1 logs the plan without calling the Graph API.

import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createChild, createCarousel, waitFinished, publishCarousel } from "./lib/ig.mjs";
import { sendEmail } from "./lib/resend.mjs";
import { POST_TYPES } from "./lib/config.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DRY = process.env.DRY_RUN === "1";

async function main() {
  const postType = (process.argv[2] || process.env.POST_TYPE || "").trim();
  if (!POST_TYPES.includes(postType)) throw new Error(`usage: node publish.mjs <${POST_TYPES.join("|")}>`);

  const manifest = JSON.parse(await readFile(path.join(__dirname, "out", postType, "manifest.json"), "utf8"));
  if (!manifest.should_post) {
    console.log(`[social] manifest says should_post=false — nothing to publish.`);
    return;
  }
  const { imageUrls, caption, date } = manifest;
  if (!imageUrls?.length) throw new Error("manifest has no imageUrls");

  if (DRY) {
    console.log(`[social] DRY_RUN — would publish ${imageUrls.length}-slide carousel:\n${imageUrls.join("\n")}\n---\n${caption}`);
    return;
  }

  console.log(`[social] creating ${imageUrls.length} child containers…`);
  const children = [];
  for (const u of imageUrls) {
    const id = await createChild(u);
    await waitFinished(id);
    children.push(id);
  }

  console.log(`[social] creating carousel container…`);
  const carousel = await createCarousel(children, caption);
  await waitFinished(carousel);

  console.log(`[social] publishing…`);
  const mediaId = await publishCarousel(carousel);
  console.log(`[social] ✅ published ${postType} ${date} — media id ${mediaId}`);

  await sendEmail({
    subject: `✅ Local IG ${postType} published — ${date}`,
    html: `<div style="font-family:Arial;color:#1A1523"><h2>Published to Instagram</h2><p>${postType} · ${date} · media id <code>${mediaId}</code></p></div>`,
  });
}

main().catch((e) => { console.error(e); process.exit(1); });
