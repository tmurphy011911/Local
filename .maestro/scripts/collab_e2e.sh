#!/usr/bin/env bash
# Two-device collaboration E2E: A shares a plan -> B accepts via deep link ->
# A reopens and earns 2,500 points. Drives two emulators in lockstep.
#
# Prereqs:
#   - SUPABASE_SERVICE_KEY exported (service_role; flagged for rotation).
#   - Two emulators booted: emulator-5554 (device A), emulator-5556 (device B),
#     both with the bypass-enabled debug APK installed (Config.TEST_AUTH_BYPASS=true).
#   - maestro on PATH.
#
# Usage:  SUPABASE_SERVICE_KEY=ey... .maestro/scripts/collab_e2e.sh
set -euo pipefail

ADB="$HOME/Library/Android/sdk/platform-tools/adb"
DEV_A="emulator-5554"
DEV_B="emulator-5556"
APP_ID="live.localapp.local"
HERE="$(cd "$(dirname "$0")/.." && pwd)"   # .maestro/
FLOWS="$HERE/flows-collab"

if [[ -z "${SUPABASE_SERVICE_KEY:-}" ]]; then
  echo "ERROR: export SUPABASE_SERVICE_KEY before running." >&2
  exit 2
fi

step() { echo; echo "=== $* ==="; }

step "1/7 Reset award state + seed a fresh plan for A"
SEED_OUT="$(node "$HERE/scripts/collab_reset_seed.mjs" reset-seed)"
echo "$SEED_OUT"
PLAN_ID="$(echo "$SEED_OUT" | sed -n 's/^PLAN_ID=//p')"
[[ -n "$PLAN_ID" ]] || { echo "ERROR: no PLAN_ID from seed step." >&2; exit 3; }

step "2/7 Device A: sign in, open plan, Share (logs the token)"
"$ADB" -s "$DEV_A" logcat -c
maestro --device "$DEV_A" test "$FLOWS/A1_share.yaml"

step "3/7 Scrape MAESTRO_SHARE_TOKEN from device A logcat"
TOKEN="$("$ADB" -s "$DEV_A" logcat -d | sed -n 's/.*MAESTRO_SHARE_TOKEN=\([A-Za-z0-9_-]*\).*/\1/p' | tail -1)"
[[ -n "$TOKEN" ]] || { echo "ERROR: share token not found in logcat." >&2; exit 4; }
echo "TOKEN=$TOKEN"

step "4/7 Device B: sign in as testbypass+b@"
maestro --device "$DEV_B" test "$FLOWS/B_signin.yaml"

step "5/7 Device B: open the invite deep link"
# B is already signed in and foregrounded on Home from B_signin. Firing the
# VIEW intent into the running activity hits onNewIntent, and DeepLinkHandler
# now delivers warm links straight to the live callback, so the shared-plan
# preview opens deterministically (no cold-start splash race).
"$ADB" -s "$DEV_B" shell am start -a android.intent.action.VIEW \
  -d "https://localapp.live/p/$TOKEN" "$APP_ID/.MainActivity"

step "6/7 Device B: Accept Invite"
maestro --device "$DEV_B" test "$FLOWS/B_accept.yaml"

step "7/7 Device A: reopen plan -> assert 2,500-point toast"
maestro --device "$DEV_A" test "$FLOWS/A2_award.yaml"

step "DB verify: A earned INVITE_ACCEPTED milestone 1 (+2500)"
node "$HERE/scripts/collab_reset_seed.mjs" verify

echo; echo "COLLAB E2E PASSED (plan $PLAN_ID, token $TOKEN)."
