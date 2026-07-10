/**
 * onesignal.js — send push notifications through the OneSignal REST API.
 *
 * Users are targeted by external_id (= Supabase auth uid; the client sets this
 * via OneSignal.login(uid)). The REST key comes from the Supabase `secrets`
 * table (ONESIGNAL_REST_API_KEY) — never the client. The app id is public.
 *
 * OneSignal new-API contract (verified 2026-07-10):
 *   POST https://api.onesignal.com/notifications
 *   Authorization: Key <os_v2_app_...>
 *   include_aliases.external_id targets up to 20k users per call; using aliases
 *   requires target_channel: "push". Title=headings, body=contents, deep-link
 *   path rides in data.path (read by PushClickListener / Android click listener).
 */

const ONESIGNAL_API = "https://api.onesignal.com/notifications";
const APP_ID = "bdbdc7ed-00db-4961-9ad7-67ec3a5cba9f"; // public; mirrors Config.ONESIGNAL_APP_ID
const MAX_ALIASES_PER_CALL = 20000;

/**
 * Send one notification to a batch of users by external_id (uid).
 *
 * @param {object}   opts
 * @param {string}   opts.restKey       OneSignal REST API key.
 * @param {string[]} opts.externalIds   Supabase uids to target (≤20k/call).
 * @param {string}   opts.title         Notification heading.
 * @param {string}   opts.body          Notification content.
 * @param {string}  [opts.path]         Deep-link path ("/e/..", "/p/..", "/v/.."); routed on tap.
 * @param {object}  [opts.data]         Extra custom data.
 * @param {boolean} [opts.dryRun=false] If true, don't call OneSignal — return a preview.
 * @returns {Promise<object>} OneSignal response (or a dry-run preview).
 */
async function sendPush({ restKey, externalIds, title, body, path, data, dryRun = false }) {
  const ids = (externalIds || []).filter(Boolean);
  if (ids.length === 0) return { skipped: "no recipients" };
  if (ids.length > MAX_ALIASES_PER_CALL) {
    throw new Error(`sendPush: ${ids.length} external_ids exceeds the ${MAX_ALIASES_PER_CALL}/call cap — batch upstream.`);
  }

  const payload = {
    app_id: APP_ID,
    target_channel: "push",
    include_aliases: { external_id: ids },
    headings: { en: title },
    contents: { en: body },
    data: { ...(data || {}), ...(path ? { path } : {}) },
  };

  if (dryRun) {
    return { dryRun: true, recipients: ids.length, headings: payload.headings, contents: payload.contents, data: payload.data };
  }

  if (!restKey) throw new Error("sendPush: missing ONESIGNAL_REST_API_KEY");

  const res = await fetch(ONESIGNAL_API, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Key ${restKey}` },
    body: JSON.stringify(payload),
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(`OneSignal ${res.status}: ${JSON.stringify(json).slice(0, 300)}`);
  }
  return json;
}

module.exports = { sendPush, APP_ID, MAX_ALIASES_PER_CALL };
