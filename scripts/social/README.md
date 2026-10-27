# Local — automated Instagram posts

Three scheduled Instagram carousels, built from live event data and published
**after you approve each one**:

| Post | Schedule (ET) | Content |
|------|---------------|---------|
| Happening today | every day, ~11am | 4–8 Boston events happening today |
| This weekend | Thursdays, ~11am | up to 8 Fri–Sun events |
| This month | 1st of the month, ~11am | top events this month |

Each carousel is: a **hero** slide → up to **8 event cards** → a **download**
card (Instagram caps a carousel at 10 images). The caption lists the lineup and
links to `localapp.live`, with hashtags.

## How it runs

```
cron ─▶ build job ──────────────────────────────▶ publish job (gated)
        query events (prod, Boston metro)          create IG carousel container
        render 1080×1350 PNGs (Playwright)         wait FINISHED, media_publish
        upload PNGs to public Storage              (runs only after you approve
        write manifest.json + email a preview       the `instagram-publish` env)
```

The **build** job never posts. It stages everything and emails you a preview
with a link to the run. The **publish** job sits behind a GitHub *Environment*
protection rule, so GitHub pauses and waits for your one-tap approval before it
calls Instagram. If too few events qualify (`MIN_EVENTS` in `lib/config.mjs`),
the run skips and just emails you.

## One-time setup

### 1. Instagram / Meta

The posts go out through the Instagram Graph API, which needs:

- An **Instagram Business or Creator** account (Instagram app → Settings →
  Account type).
- That account **linked to a Facebook Page** (Page → Settings → Linked accounts).
- Instagram added to the existing Meta app (`1127186463331769`) at
  <https://developers.facebook.com> → the app → Products → add **Instagram
  Graph API**.
- A **long-lived access token** with scopes `instagram_basic`,
  `instagram_content_publish`, `pages_show_list`, `pages_read_engagement`.
  Generate a user token in the Graph API Explorer, then exchange it for a
  long-lived token (~60 days) and refresh it before expiry. (A System User
  token from Business Settings doesn't expire — preferred for automation.)
- Your **IG user id**: `GET /me/accounts` → your Page → `GET /{page-id}?fields=instagram_business_account`.

### 2. GitHub secrets (repo → Settings → Secrets and variables → Actions)

| Secret | What |
|--------|------|
| `IG_USER_ID` | Instagram Business account id |
| `IG_ACCESS_TOKEN` | long-lived / system-user token |
| `SUPABASE_SERVICE_KEY` | already used by the other workflows |
| `RESEND_API_KEY` | already used by the other workflows |
| `SOCIAL_ALERT_EMAIL` | where previews/alerts go (comma-separate for several) |

`SUPABASE_URL` is optional — it defaults to prod in `lib/config.mjs`.

### 3. The review gate (repo → Settings → Environments)

Create an environment named **`instagram-publish`** and add yourself as a
**Required reviewer**. That's what turns every run into "review-first": the
publish job waits on your approval in the Actions UI (works from the GitHub
mobile app too).

### 4. Scheduling

GitHub only runs `schedule:` triggers from the **default branch**, so these
workflows must be merged to the default branch to fire automatically. Until
then, run any of them manually via **Actions → (workflow) → Run workflow**.

## Testing locally

```bash
cd scripts/social
npm install
npx playwright install chromium
DRY_RUN=1 SUPABASE_SERVICE_KEY=ey... node build.mjs daily
# → renders PNGs to scripts/social/out/daily/ and writes manifest.json,
#   skipping Storage upload, email, and publishing.
```

`DRY_RUN=1 node publish.mjs daily` prints the carousel plan without posting.

## Files

| File | Role |
|------|------|
| `query.mjs` | time windows + Boston bbox + ranking/diversity |
| `caption.mjs` | hero/eyebrow/label + caption + hashtags |
| `template.mjs` | the card HTML/CSS (the approved design) |
| `render.mjs` | HTML → PNG via Playwright (embeds fonts/Bo/icon, builds QR) |
| `build.mjs` | orchestrates the build job (stage + preview email) |
| `publish.mjs` | the gated publish job (Graph API) |
| `lib/` | config, supabase, images (thumbUrl port), storage, ig, resend |
| `assets/` | app icon, Bo SVG, Fraunces/Nunito fonts (copied from the app) |

Design is the approved `ig-cards` preview. Event photos use each event's real
`image_url` through the Storage render endpoint (`lib/images.mjs`) so this never
pulls full-size originals (the 2026-10 egress lesson).
