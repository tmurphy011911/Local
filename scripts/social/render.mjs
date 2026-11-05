// HTML → PNG renderer. Embeds the app's Fraunces/Nunito fonts, Bo, and the real
// app icon, generates the download QR server-side, then screenshots each
// 1080×1350 slide with headless Chromium. One browser, one page, N shots.

import { chromium } from "playwright";
import QRCode from "qrcode";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { renderHtml } from "./template.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ASSETS = path.join(__dirname, "assets");

async function fontFace(file, family, weight) {
  const b64 = (await readFile(path.join(ASSETS, file))).toString("base64");
  return `@font-face{font-family:'${family}';font-style:normal;font-weight:${weight};` +
    `src:url(data:font/ttf;base64,${b64}) format('truetype');}`;
}

export async function buildAssets() {
  const faces = await Promise.all([
    fontFace("fraunces_700.ttf", "Fraunces", 700),
    fontFace("fraunces_900.ttf", "Fraunces", 900),
    fontFace("nunito_600.ttf", "Nunito", 600),
    fontFace("nunito_700.ttf", "Nunito", 700),
    fontFace("nunito_800.ttf", "Nunito", 800),
    fontFace("nunito_900.ttf", "Nunito", 900),
  ]);
  const boSvg = await readFile(path.join(ASSETS, "bo.svg"), "utf8");
  const appiconDataUri = "data:image/png;base64," +
    (await readFile(path.join(ASSETS, "appicon.png"))).toString("base64");
  const qrSvg = await QRCode.toString("https://localapp.live", {
    type: "svg", margin: 0, errorCorrectionLevel: "M",
    color: { dark: "#1A1523FF", light: "#00000000" },
  });
  return { fontCss: faces.join("\n"), boSvg, appiconDataUri, qrSvg };
}

// Returns an ordered array of JPEG paths (hero, events…, download).
export async function renderSlides({ heroLine, eyebrow, label, events, cityName }, outDir) {
  const assets = await buildAssets();
  const { html, slideIds } = renderHtml({ heroLine, eyebrow, label, events, cityName, ...assets });

  const browser = await chromium.launch({ args: ["--no-sandbox", "--disable-dev-shm-usage"] });
  try {
    const page = await browser.newPage({ viewport: { width: 1080, height: 1350 }, deviceScaleFactor: 1 });
    await page.setContent(html, { waitUntil: "load", timeout: 60000 });
    await page.waitForLoadState("networkidle", { timeout: 20000 }).catch(() => {});
    await page.evaluate(() => (document.fonts ? document.fonts.ready : null)).catch(() => {});

    // Fit text before capture: the hero date shrinks to one line; each event
    // title shrinks until it's ≤3 lines, then if the card body still overflows,
    // the blurb loses lines (3→2→1→gone) before the title shrinks further.
    // Nothing is clipped or ellipsized.
    await page.evaluate(() => {
      const px = (el) => parseFloat(getComputedStyle(el).fontSize);
      for (const big of document.querySelectorAll(".datebig")) {
        let s = px(big);
        while (big.scrollWidth > big.clientWidth + 1 && s > 40) { s -= 4; big.style.fontSize = s + "px"; }
      }
      for (const slide of document.querySelectorAll(".s-event")) {
        const W = slide.clientWidth, title = slide.querySelector(".ev-title"), body = slide.querySelector(".ev-body");
        const desc = slide.querySelector(".ev-desc");
        const min = 0.045 * W, step = 0.002 * W;
        let s = px(title);
        const lines = () => Math.round(title.getBoundingClientRect().height / parseFloat(getComputedStyle(title).lineHeight));
        const over = () => body.scrollHeight > body.clientHeight + 1;
        while (lines() > 3 && s > min) { s -= step; title.style.fontSize = s + "px"; }
        let clamp = 3;
        while (over() && desc && desc.isConnected && clamp > 0) {
          clamp -= 1;
          if (clamp === 0) desc.remove(); else desc.style.webkitLineClamp = String(clamp);
        }
        while (over() && s > min) { s -= step; title.style.fontSize = s + "px"; }
      }
    });

    const out = [];
    for (let i = 0; i < slideIds.length; i++) {
      const el = await page.$("#" + slideIds[i]);
      if (!el) throw new Error(`slide element missing: ${slideIds[i]}`);
      const p = path.join(outDir, String(i).padStart(2, "0") + ".jpg");
      // Instagram publishing only accepts JPEG.
      await el.screenshot({ path: p, type: "jpeg", quality: 92 });
      out.push(p);
    }
    return out;
  } finally {
    await browser.close();
  }
}
