// Build job: assemble a post and STAGE it (never publishes).
//
//   node build.mjs <daily|weekend|monthly>
//
// Queries events, renders the carousel PNGs, uploads them to public Storage,
// writes out/<type>/manifest.json (image URLs + caption), and emails a preview
// with a link to approve. The gated publish job (publish.mjs) takes it from
// there. Set DRY_RUN=1 to render locally and skip Storage/email/GH wiring.

import { mkdir, writeFile, appendFile, rm } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { getPost } from "./query.mjs";
import { renderSlides } from "./render.mjs";
import { ensureBucket, uploadImage } from "./lib/storage.mjs";
import { sendEmail, previewHtml } from "./lib/resend.mjs";
import { heroLine, eyebrow, slideLabel, captionWithTags } from "./caption.mjs";
import { MIN_EVENTS, MAX_EVENT_CARDS, POST_TYPES, runUrl, cityPrefix } from "./lib/config.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DRY = process.env.DRY_RUN === "1";

function dateStamp(iso) {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "America/New_York", year: "numeric", month: "2-digit", day: "2-digit" })
    .format(new Date(iso));
}
async function ghOutput(obj) {
  const f = process.env.GITHUB_OUTPUT;
  if (!f) return;
  await appendFile(f, Object.entries(obj).map(([k, v]) => `${k}=${v}`).join("\n") + "\n");
}

async function main() {
  const postType = (process.argv[2] || process.env.POST_TYPE || "").trim();
  if (!POST_TYPES.includes(postType)) {
    throw new Error(`usage: node build.mjs <${POST_TYPES.join("|")}>`);
  }

  const post = await getPost(postType);
  const date = dateStamp(post.window.from);
  const outDir = path.join(__dirname, "out", postType);
  await rm(outDir, { recursive: true, force: true });
  await mkdir(outDir, { recursive: true });

  const { city } = post;
  console.log(`[social] ${city.key} ${postType} ${date}: ${post.events.length} events found (window ${post.window.from}…${post.window.to}).`);

  if (post.events.length < MIN_EVENTS[postType]) {
    const msg = `Only ${post.events.length} qualifying events for the ${postType} post (need ${MIN_EVENTS[postType]}). Skipping.`;
    console.log(`[social] ${msg}`);
    await writeFile(path.join(outDir, "manifest.json"),
      JSON.stringify({ postType, date, should_post: false, reason: msg, count: post.events.length }, null, 2));
    if (!DRY) {
      await sendEmail({
        subject: `⏭️ Local IG ${city.name} ${postType} skipped — ${date}`,
        html: `<div style="font-family:Arial;color:#1A1523"><h2>${postType} post skipped</h2><p>${msg}</p></div>`,
      });
    }
    await ghOutput({ should_post: "false", count: String(post.events.length) });
    return;
  }

  const cardEvents = post.events.slice(0, MAX_EVENT_CARDS);
  const content = {
    heroLine: heroLine(post),
    eyebrow: eyebrow(post),
    label: slideLabel(post),
    events: cardEvents,
    cityName: city.name,
  };
  const caption = captionWithTags(post);

  console.log(`[social] rendering ${cardEvents.length + 2} slides…`);
  const pngs = await renderSlides(content, outDir);

  let imageUrls = pngs.map((p) => `file://${p}`);
  if (!DRY) {
    await ensureBucket();
    imageUrls = [];
    for (const p of pngs) imageUrls.push(await uploadImage(p, `${cityPrefix(city)}${postType}/${date}`));
    console.log(`[social] uploaded ${imageUrls.length} images.`);
  }

  const manifest = {
    postType, city: city.key, date, should_post: true, count: post.events.length,
    heroLine: content.heroLine, caption, imageUrls,
    builtAt: new Date().toISOString(),
  };
  await writeFile(path.join(outDir, "manifest.json"), JSON.stringify(manifest, null, 2));

  if (!DRY) {
    await sendEmail({
      subject: `📸 Local IG ${city.name} ${postType} ready — ${date} (${post.events.length} events)`,
      html: previewHtml({ title: `${content.heroLine}`, caption, imageUrls, runUrl: runUrl() }),
    });
  }
  await ghOutput({ should_post: "true", count: String(post.events.length) });
  console.log(`[social] staged ${postType} ${date}. manifest: ${path.join(outDir, "manifest.json")}`);
}

main().catch((e) => { console.error(e); process.exit(1); });
