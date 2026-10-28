// The card HTML/CSS. This is the approved preview design (ig-cards artifact)
// adapted for fixed 1080×1350 output: each slide is 1080px wide with
// container-type:inline-size, so the cqw units produce the exact proportions
// that were signed off. Fonts are embedded (Fraunces/Nunito from the app),
// event photos are real, Bo + the app icon come from the iOS assets.

const GRAD = {
  sage: ["#EDFAF5", "#D5F5E8"], plum: ["#F5F0FF", "#EDE0FF"], coral: ["#FFF0EE", "#FFE0DC"],
  sky: ["#EEF5FF", "#DCEEFF"], sun: ["#FFF8EC", "#FFF0CC"],
};

function esc(s) {
  return String(s == null ? "" : s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

const PIN = '<svg viewBox="0 0 24 24" fill="#4A4458"><path d="M12 2a7 7 0 00-7 7c0 5 7 13 7 13s7-8 7-13a7 7 0 00-7-7zm0 9.5A2.5 2.5 0 1112 6a2.5 2.5 0 010 5.5z"/></svg>';
const APPLE = '<svg viewBox="0 0 384 512" fill="#fff"><path d="M318.7 268.7c-.2-36.7 16.4-64.4 50-84.8-18.8-26.9-47.2-41.7-84.7-44.6-35.5-2.8-74.3 20.7-88.5 20.7-15 0-49.4-19.7-76.4-19.7C33.4 141.9-.1 184.6-.1 268.5c0 30.3 5.5 61.6 16.6 93.9 14.8 42.4 68.2 146.4 123.9 144.7 29.1-.7 49.7-20.6 87.6-20.6 36.8 0 55.9 20.6 88.5 20.6 56.2-.8 104.6-95.3 118.7-137.8-75.4-35.5-72.6-103.9-72.6-105.9zM260.3 100.4c28.2-33.5 25.6-64 24.8-74.9-24.9 1.4-53.7 16.9-70.1 36-18.1 20.4-28.7 45.6-26.4 73.9 26.9 2.1 51.5-11.7 71.7-35z"/></svg>';
const PLAY = '<svg viewBox="0 0 512 512"><path fill="#00D4FF" d="M47 24C38 29 33 39 33 53v406c0 14 5 24 14 29l238-232L47 24z"/><path fill="#00F076" d="M47 24l238 232 66-64L82 14C68 6 56 12 47 24z"/><path fill="#FFC900" d="M351 192l-66 64 66 64 92-53c17-10 17-32 0-42l-92-33z"/><path fill="#FF3A44" d="M47 488c9 12 21 18 35 10l269-154-66-64L47 488z"/></svg>';

function css(fontCss) {
  return `${fontCss}
  *{ box-sizing:border-box; margin:0; padding:0; }
  html,body{ background:#fff; }
  :root{ --coral:#FF6B6B; --sage:#4ECBA0; --ink:#1A1523; --ink2:#4A4458; --ink3:#8A849A; --pebble:#C8C3D4; --snow:#FAF8FC;
    --sans:"Nunito",system-ui,sans-serif; --serif:"Fraunces","Georgia",serif; }
  .slide{ width:1080px; height:1350px; container-type:inline-size; position:relative; overflow:hidden; font-family:var(--sans); }

  .wordmark{ font-family:var(--serif); font-weight:900; font-size:6cqw; color:var(--ink); letter-spacing:-.01em; line-height:1; }
  .wordmark b{ color:var(--coral); }

  /* HERO */
  .s-hero{ background:
      radial-gradient(120% 90% at 100% 0%, #FFE8E8 0%, rgba(255,232,232,0) 55%),
      radial-gradient(110% 80% at 0% 100%, #E6F9F2 0%, rgba(230,249,242,0) 50%),
      var(--snow); padding:9cqw; display:flex; flex-direction:column; }
  .s-hero .bo{ flex:1; min-height:0; display:flex; align-items:center; justify-content:flex-end; margin:3cqw 0 1cqw; }
  .s-hero .bo svg{ height:100%; width:auto; max-height:46cqw; max-width:46cqw; filter:drop-shadow(0 3cqw 5cqw rgba(26,21,35,.18)); }
  .s-hero .eyebrow{ font-weight:800; letter-spacing:.3em; text-transform:uppercase; font-size:3.3cqw; color:var(--ink3); margin-top:7cqw; }
  .s-hero h2{ font-family:var(--serif); font-weight:900; font-size:12.2cqw; line-height:1.0; color:var(--ink); margin:3cqw 0 0; letter-spacing:-.02em; }
  .s-hero .rule{ height:1.4cqw; width:20cqw; background:var(--coral); border-radius:2cqw; margin-top:5cqw; }
  .s-hero .foot{ display:flex; align-items:center; justify-content:space-between; }
  .s-hero .foot .swipe{ font-weight:800; font-size:4cqw; color:var(--ink2); display:flex; align-items:center; gap:2cqw; }
  .s-hero .foot .url{ font-weight:800; font-size:3.4cqw; color:var(--ink3); }

  /* EVENT */
  .s-event{ background: radial-gradient(130% 70% at 0% 0%, #FFF0EE 0%, rgba(255,240,238,0) 60%), var(--snow); padding:6.5cqw; display:flex; flex-direction:column; }
  .ev-top{ display:flex; align-items:center; justify-content:space-between; }
  .ev-top .label{ font-weight:800; letter-spacing:.2em; text-transform:uppercase; font-size:3.2cqw; color:var(--ink3); }
  .ev-top .count{ font-family:var(--serif); font-weight:900; font-size:3.4cqw; color:var(--coral); background:#fff; border:1px solid var(--pebble); border-radius:10cqw; padding:1.2cqw 3cqw; }
  .ev-card{ margin:5cqw 0; background:#fff; border-radius:6cqw; overflow:hidden; flex:1; display:flex; flex-direction:column; box-shadow:0 4cqw 10cqw rgba(26,21,35,.10); }
  .ev-photo{ position:relative; height:52cqw; overflow:hidden; }
  .ev-photo .img{ width:100%; height:100%; object-fit:cover; display:block; }
  .ev-pill{ position:absolute; top:4cqw; left:4cqw; font-weight:800; font-size:2.9cqw; letter-spacing:.06em; color:var(--ink); background:rgba(255,255,255,.92); border-radius:6cqw; padding:1.4cqw 3cqw; }
  .ev-free{ position:absolute; top:4cqw; right:4cqw; font-weight:900; font-size:2.9cqw; color:#2E7D32; background:#E8F5E9; border:1px solid #A5D6A7; border-radius:6cqw; padding:1.2cqw 3cqw; }
  .ev-body{ padding:5.5cqw; display:flex; flex-direction:column; gap:2.2cqw; flex:1; justify-content:center; }
  .ev-when{ font-weight:800; letter-spacing:.08em; text-transform:uppercase; font-size:3.4cqw; color:var(--coral); }
  .ev-title{ font-family:var(--serif); font-weight:900; font-size:7.4cqw; line-height:1.04; color:var(--ink); letter-spacing:-.01em; display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden; }
  .ev-venue{ display:flex; align-items:center; gap:1.6cqw; font-weight:700; font-size:4cqw; color:var(--ink2); }
  .ev-venue svg{ width:4cqw; height:4cqw; flex:0 0 auto; }
  .ev-desc{ font-weight:600; font-size:3.5cqw; line-height:1.42; color:var(--ink2); margin-top:1.4cqw;
    display:-webkit-box; -webkit-line-clamp:3; -webkit-box-orient:vertical; overflow:hidden; }

  /* DOWNLOAD */
  .s-dl{ background:radial-gradient(120% 80% at 50% 0%, #2A2235 0%, #1A1523 60%); padding:9cqw; display:flex; flex-direction:column; align-items:center; text-align:center; color:#fff; }
  .s-dl .mark{ width:25cqw; height:25cqw; border-radius:5.6cqw; display:block; margin-top:5cqw; box-shadow:0 4cqw 14cqw rgba(0,0,0,.38); }
  .s-dl h2{ font-family:var(--serif); font-weight:900; font-size:10.5cqw; line-height:1.04; margin:6cqw 0 0; letter-spacing:-.02em; }
  .s-dl .sub{ font-weight:700; font-size:4.2cqw; color:#C8C3D4; margin-top:3.5cqw; }
  .s-dl .badges{ display:flex; gap:3cqw; margin-top:6cqw; }
  .badge{ display:flex; align-items:center; gap:2.4cqw; background:#000; border:1px solid #3a3346; border-radius:4cqw; padding:2.6cqw 4cqw; }
  .badge svg{ width:6cqw; height:6cqw; flex:0 0 auto; }
  .badge .t{ text-align:left; line-height:1.1; }
  .badge .t small{ display:block; font-size:2.4cqw; font-weight:700; color:#C8C3D4; letter-spacing:.02em; }
  .badge .t b{ display:block; font-size:4cqw; font-weight:800; color:#fff; }
  .s-dl .qr{ margin-top:auto; display:flex; flex-direction:column; align-items:center; gap:2cqw; }
  .s-dl .qr .tile{ background:#fff; border-radius:4cqw; padding:3cqw; width:26cqw; height:26cqw; display:flex; align-items:center; justify-content:center; }
  .s-dl .qr .tile svg{ width:100%; height:100%; display:block; }
  .s-dl .qr .cap{ font-weight:800; letter-spacing:.14em; text-transform:uppercase; font-size:3cqw; color:#C8C3D4; }`;
}

function heroSlide(id, { eyebrow, heroLine, boSvg }) {
  return `<div class="slide s-hero" id="${id}">
    <div class="wordmark"><b>l</b>oca<b>l</b></div>
    <div class="eyebrow">${esc(eyebrow)}</div>
    <h2>${esc(heroLine)}</h2>
    <div class="rule"></div>
    <div class="bo">${boSvg}</div>
    <div class="foot"><span class="swipe">Swipe for the lineup
      <svg viewBox="0 0 24 24" fill="none" stroke="#4A4458" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" style="width:4cqw;height:4cqw"><path d="M5 12h14M13 6l6 6-6 6"/></svg>
      </span><span class="url">localapp.live</span></div>
  </div>`;
}

function eventSlide(id, ev, idx, total, label) {
  const g = GRAD[ev.accent] || GRAD.coral;
  const free = ev.free ? '<span class="ev-free">FREE</span>' : "";
  return `<div class="slide s-event" id="${id}">
    <div class="ev-top"><span class="label">${esc(label)}</span><span class="count">${idx + 1} / ${total}</span></div>
    <div class="ev-card">
      <div class="ev-photo" style="background:linear-gradient(135deg,${g[0]},${g[1]})">
        <img class="img" src="${esc(ev.photo)}" alt="" referrerpolicy="no-referrer">
        <span class="ev-pill">${esc(ev.category)}</span>${free}
      </div>
      <div class="ev-body">
        <div class="ev-when">${esc(ev.when)}</div>
        <div class="ev-title">${esc(ev.title)}</div>
        <div class="ev-venue">${PIN}${esc(ev.venueName + " · " + ev.hood)}</div>
        ${ev.desc ? `<div class="ev-desc">${esc(ev.desc)}</div>` : ""}
      </div>
    </div>
  </div>`;
}

function downloadSlide(id, { appiconDataUri, qrSvg }) {
  return `<div class="slide s-dl" id="${id}">
    <img class="mark" src="${appiconDataUri}" alt="Local app icon">
    <h2>The whole city. One app.</h2>
    <div class="sub">Every event in Boston, in one place. Free.</div>
    <div class="badges">
      <span class="badge">${APPLE}<span class="t"><small>Download on the</small><b>App Store</b></span></span>
      <span class="badge">${PLAY}<span class="t"><small>Get it on</small><b>Google Play</b></span></span>
    </div>
    <div class="qr"><div class="tile">${qrSvg}</div><span class="cap">Scan to download</span></div>
  </div>`;
}

// Returns { html, slideIds } — one hero, one card per event, one download card.
export function renderHtml({ heroLine, eyebrow, label, events, fontCss, boSvg, appiconDataUri, qrSvg }) {
  const ids = [];
  let body = "";
  ids.push("slide-hero");
  body += heroSlide("slide-hero", { eyebrow, heroLine, boSvg });
  events.forEach((ev, i) => {
    const id = `slide-ev-${i}`;
    ids.push(id);
    body += eventSlide(id, ev, i, events.length, label);
  });
  ids.push("slide-dl");
  body += downloadSlide("slide-dl", { appiconDataUri, qrSvg });

  const html = `<!doctype html><html><head><meta charset="utf-8"><style>${css(fontCss)}</style></head><body>${body}</body></html>`;
  return { html, slideIds: ids };
}
