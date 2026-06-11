#!/usr/bin/env bash
# Paywall gating E2E — two phases against the testbypass@ account:
#   1. FREE (trial expired)    -> every gate shows the paywall   (paywall_gates.yaml)
#   2. PREMIUM (active trial)  -> every gate is bypassed         (paywall_premium_bypass.yaml)
# Always restores premium on exit (trap) so the rest of the suite (premium) works.
#
# The gate is a single `premiumGate.isPremium` check (isTrialActive ||
# hasPaidSubscription); the active-trial phase exercises the same bypass branch a
# paid Local Pro upgrade hits, so it validates "no gate for trial OR upgraded".
set -euo pipefail
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
DEV="${DEV:-emulator-5554}"
APP_ID="live.localapp.local"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
[ -n "${SUPABASE_SERVICE_KEY:-}" ] || { echo "ERROR: export SUPABASE_SERVICE_KEY"; exit 2; }

restore() { echo "--- restoring premium ---"; node "$HERE/scripts/set_trial.mjs" premium || true; }
trap restore EXIT

step() { echo; echo "=== $* ==="; }

step "Phase 1/2 — FREE: every gate must show the paywall"
node "$HERE/scripts/set_trial.mjs" free
node "$HERE/scripts/set_trial.mjs" seed-plan
"$ADB" -s "$DEV" shell pm clear "$APP_ID" >/dev/null
maestro --device "$DEV" test "$HERE/flows-paywall/paywall_gates.yaml"

step "Phase 2/2 — PREMIUM (active trial): every gate must be bypassed"
node "$HERE/scripts/set_trial.mjs" premium
node "$HERE/scripts/set_trial.mjs" seed-plan
"$ADB" -s "$DEV" shell pm clear "$APP_ID" >/dev/null
maestro --device "$DEV" test "$HERE/flows-paywall/paywall_premium_bypass.yaml"

echo; echo "PAYWALL GATING + BYPASS PASSED"
