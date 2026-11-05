// Caption + hashtag copy. Voice follows LOCAL_DESIGN_BRIEF.md: direct, dry,
// specific; em dashes, no earned-less exclamation marks. Headline/eyebrow/label
// strings here feed both the rendered cards and the IG caption, so a post reads
// the same on the image and in the text.

import { STORE } from "./lib/config.mjs";

const TZ = "America/New_York";
const fmt = (iso, o) => new Intl.DateTimeFormat("en-US", { timeZone: TZ, ...o }).format(new Date(iso));

const EMOJI = [
  [/theat|broadway|opera|dance|musical/i, "🎭"], [/music|concert|dj|band|\btour\b|rock|pop|indie|jazz|hip.?hop|rap|r&b|country|folk|latin|reggae|punk|metal|electronic|soul|blues|singer/i, "🎸"], [/comedy|stand/i, "🎤"],
  [/museum/i, "🏛️"], [/art|galler|exhibit/i, "🖼️"], [/food|dining|restaurant|brunch|beer|wine/i, "🍴"],
  [/market|farmers/i, "🛒"], [/outdoor|hike|park|nature|walk/i, "🌳"], [/sport|game|hockey|basket|base|foot/i, "🏟️"],
  [/night|club|bar/i, "🍸"], [/kid|family|child/i, "🎠"], [/film|movie|cinema/i, "🎬"], [/book|librar|read/i, "📚"],
];
function emojiFor(ev) {
  const hay = `${ev.category} ${ev.title}`;
  for (const [re, e] of EMOJI) if (re.test(hay)) return e;
  return "📍";
}

// The hero's date block: a small coral kicker over a big date.
function eyebrow(post) {
  const { from, to } = post.window;
  if (post.postType === "daily") {
    return { kicker: fmt(from, { weekday: "long" }), big: fmt(from, { month: "short", day: "numeric" }) };
  }
  if (post.postType === "weekend") {
    const fM = fmt(from, { month: "short" }), fD = fmt(from, { day: "numeric" });
    const tM = fmt(to, { month: "short" }), tD = fmt(to, { day: "numeric" });
    return { kicker: "This weekend", big: fM === tM ? `${fM} ${fD}–${tD}` : `${fM} ${fD}–${tM} ${tD}` };
  }
  return { kicker: `This month in ${post.city.name}`, big: fmt(from, { month: "long" }) }; // monthly
}

export function heroLine(post) {
  const n = post.events.length;
  const c = post.city.name;
  if (post.postType === "daily") return `${n} things happening in ${c} tomorrow.`;
  if (post.postType === "weekend") return `Your ${c} weekend, planned.`;
  return `The ${n} best things in ${c} this month.`;
}

export function slideLabel(post) {
  if (post.postType === "daily") return `${fmt(post.window.from, { weekday: "short" })} · ${fmt(post.window.from, { month: "short", day: "numeric" })}`;
  if (post.postType === "weekend") return "This weekend";
  return "This month · ranked";
}

function hook(post) {
  const { from } = post.window;
  if (post.postType === "daily") {
    return `Tomorrow in ${post.city.name} — ${fmt(from, { weekday: "long", month: "long", day: "numeric" })}. Here's what's actually worth leaving the house for 👇`;
  }
  if (post.postType === "weekend") {
    return `Three days, one city, zero group-chat debates. Here's your ${post.city.name} weekend 👇`;
  }
  return `${fmt(from, { month: "long" })} in ${post.city.name}, ranked. The things we'd clear our calendar for 👇`;
}

function hoodTag(hood, city) {
  const t = (hood || "").toLowerCase().replace(/[^a-z0-9]+/g, "");
  return t && !city.tags.includes("#" + t) ? "#" + t : null;
}

function hashtags(post) {
  const { city } = post;
  const base = city.tags;
  const month = fmt(post.window.from, { month: "long" }).toLowerCase();
  const t = city.typeTags[post.postType];
  const extra = typeof t === "function" ? t(month) : t;
  const hoods = [];
  for (const ev of post.events) {
    const h = hoodTag(ev.hood, city);
    if (h && !hoods.includes(h) && hoods.length < 3) hoods.push(h);
  }
  return [...base, ...extra, ...hoods].join(" ");
}

export function buildCaption(post) {
  const lines = [hook(post), ""];
  post.events.forEach((ev, i) => {
    const lead = post.postType === "monthly" ? `${i + 1}. ` : `${emojiFor(ev)} `;
    lines.push(`${lead}${ev.title} — ${ev.hood}`);
  });
  lines.push("…and hundreds more in the app.");
  lines.push("");
  if (post.postType === "weekend") lines.push("Save them, share them, show up. The whole weekend, handled.");
  else if (post.postType === "monthly") lines.push("Bookmark the month. Plan as you go.");
  else lines.push("Save the ones you want. Plan the night in a tap.");
  lines.push(`Get Local — free on iOS + Android: ${STORE.web}`);
  return lines.join("\n");
}

export function captionWithTags(post) {
  return `${buildCaption(post)}\n\n${hashtags(post)}`;
}

export { eyebrow };
