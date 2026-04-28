-- Flag venues whose pages require a headless / stealth browser to scrape
-- (e.g. Cloudflare Turnstile, Vercel Bot Protection, Datadome, JS-rendered
-- SPAs). The crawler routes these through the Scrapfly API when the
-- SCRAPFLY_API_KEY env var is set; otherwise it skips them.

ALTER TABLE venues ADD COLUMN IF NOT EXISTS needs_stealth boolean NOT NULL DEFAULT false;

CREATE INDEX IF NOT EXISTS idx_venues_needs_stealth ON venues(needs_stealth) WHERE needs_stealth = true;
