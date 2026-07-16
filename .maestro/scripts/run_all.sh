#!/usr/bin/env bash
# Hands-off full regression runner for the Local app.
#
#   .maestro/scripts/run_all.sh [android|ios|both]   (default: android)
#
# Does EVERYTHING so you can walk away: preflight, build+install (with the
# bypass build flag toggled on for the run and restored after), run every flow
# in flows/, the two-device collab E2E, and the paywall gating+bypass suite,
# then print a single PASS/FAIL summary. Exit code is non-zero if any flow fails.
#
# Requirements (preflight checks them):
#   - maestro >= 2.6 on PATH        (iOS driver needs it under Xcode 26+)
#   - adb + a booted Android emulator (two for the collab E2E)
#   - SUPABASE_SERVICE_KEY in env   (collab + paywall trial toggles)
#   - iOS only: xcodebuild, applesimutils, a booted simulator
#
# It NEVER leaves Config.kt's TEST_AUTH_BYPASS flipped: the original value is
# saved and restored on exit (the installed test binary keeps the bypass; the
# source tree is returned to its prior state so commits stay clean).
set -uo pipefail

PLATFORM="${1:-android}"; shift || true
# Optional add-on sections (off by default; the standard run is unchanged):
#   --chaos        run the crash/hang chaos harness after the suite
#   --soak N       run the flows/ bundle N extra times to surface leaks/races
RUN_CHAOS=0; SOAK=0
while [ $# -gt 0 ]; do
  case "$1" in
    --chaos) RUN_CHAOS=1; shift;;
    --soak)  SOAK="${2:-3}"; shift 2;;
    *) shift;;
  esac
done
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MAESTRO_DIR="$ROOT/.maestro"
CONFIG="$ROOT/composeApp/src/commonMain/kotlin/live/localapp/local/Config.kt"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
ANDROID_DEV="${ANDROID_DEV:-emulator-5554}"
ANDROID_DEV_B="${ANDROID_DEV_B:-emulator-5556}"   # second device for collab
IOS_SIM="${IOS_SIM:-}"                              # udid; auto-detected if empty
APP_ID="live.localapp.local"
PASS=0; FAIL=0; SKIP=0
RESULTS="$(mktemp)"

log()  { echo; echo "=== $* ==="; }
ok()   { echo "PASS  $1"; PASS=$((PASS+1)); echo "PASS  $1" >>"$RESULTS"; }
bad()  { echo "FAIL  $1 :: ${2:-}"; FAIL=$((FAIL+1)); echo "FAIL  $1 :: ${2:-}" >>"$RESULTS"; }
skip() { echo "SKIP  $1 :: ${2:-}"; SKIP=$((SKIP+1)); echo "SKIP  $1 :: ${2:-}" >>"$RESULTS"; }

# ── Restore Config flag on any exit ──────────────────────────────────────────
ORIG_BYPASS="$(grep -oE 'TEST_AUTH_BYPASS = (true|false)' "$CONFIG" | grep -oE '(true|false)')"
restore_config() {
  sed -i '' "s/const val TEST_AUTH_BYPASS = true/const val TEST_AUTH_BYPASS = $ORIG_BYPASS/" "$CONFIG" 2>/dev/null || true
  [ "$ORIG_BYPASS" = "false" ] && echo "(restored TEST_AUTH_BYPASS=false in source)"
}
trap restore_config EXIT

# ── Preflight ────────────────────────────────────────────────────────────────
log "Preflight"
command -v maestro >/dev/null || { echo "ERROR: maestro not on PATH"; exit 2; }
MV="$(maestro --version 2>/dev/null | tail -1)"; echo "maestro $MV"
case "$MV" in 2.*|3.*) ;; *) echo "WARN: maestro < 2.6 — iOS driver may fail under Xcode 26+";; esac
[ -n "${SUPABASE_SERVICE_KEY:-}" ] || echo "WARN: SUPABASE_SERVICE_KEY unset — collab + paywall suites will be skipped"
if [ "$PLATFORM" != "ios" ]; then
  "$ADB" devices | grep -q "$ANDROID_DEV" || { echo "ERROR: $ANDROID_DEV not booted"; exit 2; }
fi
if [ "$PLATFORM" = "ios" ] || [ "$PLATFORM" = "both" ]; then
  command -v applesimutils >/dev/null || { echo "ERROR: applesimutils not installed (brew install wix/brew/applesimutils)"; exit 2; }
  [ -n "$IOS_SIM" ] || IOS_SIM="$(xcrun simctl list devices booted | grep -oE '[0-9A-F-]{36}' | head -1)"
  [ -n "$IOS_SIM" ] || { echo "ERROR: no booted iOS simulator"; exit 2; }
  echo "iOS sim $IOS_SIM"
fi

# ── Flip bypass ON for the build (restored on exit) ──────────────────────────
sed -i '' "s/const val TEST_AUTH_BYPASS = false/const val TEST_AUTH_BYPASS = true/" "$CONFIG"

# ── Build + install ──────────────────────────────────────────────────────────
build_android() {
  log "Build + install Android"
  ( cd "$ROOT" && ./gradlew :composeApp:assembleDebug -q ) || { echo "Android build FAILED"; exit 3; }
  local apk="$ROOT/composeApp/build/outputs/apk/debug/composeApp-debug.apk"
  "$ADB" -s "$ANDROID_DEV" install -r "$apk" >/dev/null && echo "installed on $ANDROID_DEV"
  if "$ADB" devices | grep -q "$ANDROID_DEV_B"; then
    "$ADB" -s "$ANDROID_DEV_B" install -r "$apk" >/dev/null && echo "installed on $ANDROID_DEV_B"
  fi
}
build_ios() {
  log "Build + install iOS"
  ( cd "$ROOT" && xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
      -configuration Debug -sdk iphonesimulator -destination "id=$IOS_SIM" \
      -derivedDataPath /tmp/ios_dd build ) >/tmp/ios_build.log 2>&1 || { echo "iOS build FAILED (see /tmp/ios_build.log)"; exit 3; }
  local app="/tmp/ios_dd/Build/Products/Debug-iphonesimulator/Local.app"
  xcrun simctl install "$IOS_SIM" "$app" && echo "installed on $IOS_SIM"
}

# ── Run the flows/ bundle on one device ──────────────────────────────────────
run_bundle() {
  local target="$1" label="$2"
  log "flows/ bundle — $label"
  for f in "$MAESTRO_DIR"/flows/*.yaml; do
    local name; name="$(basename "$f" .yaml)"
    # 01_onboarding needs TEST_FORCE_ONBOARDING=true; skip in the standard run.
    if [ "$name" = "01_onboarding" ]; then skip "$label/$name" "needs TEST_FORCE_ONBOARDING"; continue; fi
    if maestro --device "$target" test "$f" >"/tmp/run_${label}_${name}.log" 2>&1; then
      ok "$label/$name"
    else
      bad "$label/$name" "$(grep -iE 'Assertion is false|Element not found' "/tmp/run_${label}_${name}.log" | grep -viE 'Run flow' | head -1)"
    fi
  done
}

case "$PLATFORM" in
  android) build_android; run_bundle "$ANDROID_DEV" "android" ;;
  ios)     build_ios;     run_bundle "$IOS_SIM"     "ios" ;;
  both)    build_android; build_ios; run_bundle "$ANDROID_DEV" "android"; run_bundle "$IOS_SIM" "ios" ;;
  *) echo "ERROR: platform must be android|ios|both"; exit 2 ;;
esac

# ── E2E suites (Android, need the service key) ───────────────────────────────
if [ -n "${SUPABASE_SERVICE_KEY:-}" ] && [ "$PLATFORM" != "ios" ]; then
  log "Collab E2E (two devices)"
  if bash "$MAESTRO_DIR/scripts/collab_e2e.sh" >/tmp/run_collab.log 2>&1; then ok "collab_e2e"; else bad "collab_e2e" "$(tail -1 /tmp/run_collab.log)"; fi
  log "Paywall gating + bypass"
  if bash "$MAESTRO_DIR/scripts/paywall_gating.sh" >/tmp/run_paywall.log 2>&1; then ok "paywall_gating"; else bad "paywall_gating" "$(tail -1 /tmp/run_paywall.log)"; fi
else
  skip "collab_e2e + paywall_gating" "no SUPABASE_SERVICE_KEY or ios-only run"
fi

# ── Soak (optional): re-run the bundle N times to surface leaks/races ────────
if [ "$SOAK" -gt 0 ] 2>/dev/null; then
  TARGET="$ANDROID_DEV"; LABEL="android"
  [ "$PLATFORM" = "ios" ] && { TARGET="$IOS_SIM"; LABEL="ios"; }
  for i in $(seq 1 "$SOAK"); do
    log "Soak pass $i/$SOAK"
    run_bundle "$TARGET" "soak${i}_${LABEL}"
  done
fi

# ── Chaos (optional): crash/hang/monkey harness ──────────────────────────────
if [ "$RUN_CHAOS" -eq 1 ]; then
  CHAOS_PLATS="$PLATFORM"; [ "$PLATFORM" = "both" ] && CHAOS_PLATS="android ios"
  for p in $CHAOS_PLATS; do
    log "Chaos harness — $p"
    if bash "$MAESTRO_DIR/scripts/chaos.sh" "$p" >/tmp/run_chaos_${p}.log 2>&1; then
      ok "chaos_$p"
    else
      bad "chaos_$p" "$(grep -E '^CRASH' /tmp/run_chaos_${p}.log | head -1) (see /tmp/run_chaos_${p}.log)"
    fi
  done
fi

# ── Summary ──────────────────────────────────────────────────────────────────
log "SUMMARY"
sort "$RESULTS"
echo
echo "PASS=$PASS  FAIL=$FAIL  SKIP=$SKIP"
rm -f "$RESULTS"
[ "$FAIL" -eq 0 ]
