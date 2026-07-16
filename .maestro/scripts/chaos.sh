#!/usr/bin/env bash
# Chaos / monkey harness for the Local app — hunts CRASHES and HANGS, not
# assertion failures.
#
#   .maestro/scripts/chaos.sh [android|ios] [--passes N] [--monkey N] [--seed S]
#
# --passes N : how many times to cycle the Maestro chaos battery (default 1).
#              Each pass = 40 nav + 30 lifecycle + 25 random-tap inner rounds.
#              (Intensity is scaled here, not via Maestro env — the flow's env
#              default wins over CLI -e in Maestro 2.6, so we outer-loop instead.)
#
# What it does (each phase stresses a different fault class):
#   1. adb monkey            — true random fuzz            (Android only)
#   2. nav_churn   (Maestro) — tab + chat-sheet thrash     (both)
#   3. lifecycle_churn       — background/foreground churn  (both)
#   4. random_taps (Maestro) — pseudo-random taps/swipes    (both)
#   5. network flaps         — wifi/data drop mid-navigation (Android only)
#   6. rotation              — orientation thrash mid-nav    (Android only)
#   7. deep-link cycling     — cold + warm intent delivery   (both)
#
# Throughout, the device log is captured. The VERDICT comes from scraping that
# log + the OS crash-report dirs for process death / ANR / Fatal App Hang —
# NOT from any Maestro flow's pass/fail. A flaky missed tap is not a finding;
# a SIGSEGV, an ANR, a watchdog hang, or a new .ips crash report is.
#
# Exit 0 = survived clean.  Exit 1 = crash/hang/ANR detected (signature printed).
set -uo pipefail

PLATFORM="${1:-ios}"; shift || true
PASSES=1             # how many times to cycle the Maestro chaos battery
MONKEY=4000          # adb monkey event count (Android)
SEED=1337            # monkey seed (deterministic repro)
while [ $# -gt 0 ]; do
  case "$1" in
    --passes|--rounds) PASSES="$2"; shift 2;;   # --rounds kept as an alias
    --monkey) MONKEY="$2"; shift 2;;
    --seed)   SEED="$2";   shift 2;;
    *) echo "unknown arg: $1"; exit 2;;
  esac
done

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CHAOS_DIR="$ROOT/.maestro/flows-chaos"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
ANDROID_DEV="${ANDROID_DEV:-emulator-5554}"
IOS_SIM="${IOS_SIM:-}"
APP_ID="${APP_ID:-live.localapp.local}"
RUN_TS="$(date +%s)"
LOG="$(mktemp -t chaos_devlog).log"
SUMMARY="$(mktemp -t chaos_summary).txt"
LOG_PID=""

rm -f /tmp/chaos_nav_churn.log /tmp/chaos_lifecycle_churn.log /tmp/chaos_random_taps.log
log()  { echo; echo "=== $* ==="; }
note() { echo "    $*"; }
fail() { echo "CRASH  $*" | tee -a "$SUMMARY"; }
clean_exit() { [ -n "$LOG_PID" ] && kill "$LOG_PID" 2>/dev/null || true; }
trap clean_exit EXIT

mflow() { # mflow <flow.yaml>
  local f="$1" name; name="$(basename "$f" .yaml)"
  note "maestro: $name"
  maestro --device "$DEVICE" test "$f" \
    >>"/tmp/chaos_${name}.log" 2>&1 || true   # flow pass/fail is NOT the verdict
}
battery() { # run the cross-platform Maestro chaos flows once
  mflow "$CHAOS_DIR/nav_churn.yaml"
  mflow "$CHAOS_DIR/lifecycle_churn.yaml"
  mflow "$CHAOS_DIR/random_taps.yaml"
}

# ─────────────────────────── ANDROID ───────────────────────────
if [ "$PLATFORM" = "android" ]; then
  DEVICE="$ANDROID_DEV"
  "$ADB" devices | grep -q "$DEVICE" || { echo "ERROR: $DEVICE not booted"; exit 2; }
  "$ADB" -s "$DEVICE" shell pm list packages | grep -q "$APP_ID" || { echo "ERROR: $APP_ID not installed"; exit 2; }

  log "Baseline memory"
  MEM0="$("$ADB" -s "$DEVICE" shell dumpsys meminfo "$APP_ID" 2>/dev/null | awk '/TOTAL/{print $2; exit}')"
  note "TOTAL PSS start: ${MEM0:-?} kB"

  log "Capturing logcat"
  "$ADB" -s "$DEVICE" logcat -c
  "$ADB" -s "$DEVICE" logcat > "$LOG" 2>&1 & LOG_PID=$!

  log "Phase 1 — adb monkey ($MONKEY events, seed $SEED)"
  # No --ignore-* flags: we WANT monkey to stop and report on crash/ANR.
  "$ADB" -s "$DEVICE" shell monkey -p "$APP_ID" -s "$SEED" --throttle 50 \
      --pct-syskeys 0 --monitor-native-crashes -v "$MONKEY" \
      > /tmp/chaos_monkey.log 2>&1 || true
  grep -qiE 'CRASH|ANR|Monkey aborted|// NOT RESPONDING' /tmp/chaos_monkey.log \
      && fail "monkey reported crash/ANR (see /tmp/chaos_monkey.log)"

  log "Phase 2-4 — Maestro chaos battery ($PASSES pass(es))"
  for _ in $(seq 1 "$PASSES"); do battery; done

  log "Phase 5 — network flaps during nav_churn"
  mflow "$CHAOS_DIR/nav_churn.yaml" &  MFLOW_PID=$!
  for _ in $(seq 1 6); do
    "$ADB" -s "$DEVICE" shell svc wifi disable; "$ADB" -s "$DEVICE" shell svc data disable; sleep 1
    "$ADB" -s "$DEVICE" shell svc wifi enable;  "$ADB" -s "$DEVICE" shell svc data enable;  sleep 1
  done
  wait "$MFLOW_PID" 2>/dev/null || true

  log "Phase 6 — rotation thrash during nav_churn"
  "$ADB" -s "$DEVICE" shell settings put system accelerometer_rotation 0
  mflow "$CHAOS_DIR/nav_churn.yaml" &  MFLOW_PID=$!
  for o in 1 2 3 0 1 3 0 2; do "$ADB" -s "$DEVICE" shell settings put system user_rotation "$o"; sleep 0.7; done
  "$ADB" -s "$DEVICE" shell settings put system user_rotation 0
  wait "$MFLOW_PID" 2>/dev/null || true

  log "Phase 7 — deep-link cycling (cold + warm)"
  for id in p/chaos1 e/chaos2 v/chaos3 p/INVALID/x e/; do
    # warm
    "$ADB" -s "$DEVICE" shell am start -a android.intent.action.VIEW \
        -d "https://localapp.live/$id" "$APP_ID" >/dev/null 2>&1 || true
    sleep 0.5
    # cold
    "$ADB" -s "$DEVICE" shell am force-stop "$APP_ID"
    "$ADB" -s "$DEVICE" shell am start -a android.intent.action.VIEW \
        -d "https://localapp.live/$id" "$APP_ID" >/dev/null 2>&1 || true
    sleep 1
  done

  log "Memory delta"
  MEM1="$("$ADB" -s "$DEVICE" shell dumpsys meminfo "$APP_ID" 2>/dev/null | awk '/TOTAL/{print $2; exit}')"
  note "TOTAL PSS end: ${MEM1:-?} kB (start ${MEM0:-?} kB)"
  if [ -n "${MEM0:-}" ] && [ -n "${MEM1:-}" ] && [ "$MEM0" -gt 0 ] 2>/dev/null; then
    if [ "$((MEM1 * 100 / MEM0))" -gt 250 ]; then fail "memory grew >2.5x ($MEM0 -> $MEM1 kB) — probable leak"; fi
  fi

  sleep 2; kill "$LOG_PID" 2>/dev/null; LOG_PID=""
  log "Scanning logcat for crash signatures"
  grep -aiE 'FATAL EXCEPTION|E AndroidRuntime|ANR in '"$APP_ID"'|>>> /data/tombstones|signal [0-9]+ \(SIG|native crash|has died|Force finishing' "$LOG" \
      | grep -v 'Monkey' | sort -u | head -20 | while read -r l; do fail "logcat: $l"; done

# ─────────────────────────── iOS ───────────────────────────
elif [ "$PLATFORM" = "ios" ]; then
  [ -n "$IOS_SIM" ] || IOS_SIM="$(xcrun simctl list devices booted | grep -oE '[0-9A-F-]{36}' | head -1)"
  [ -n "$IOS_SIM" ] || { echo "ERROR: no booted iOS simulator"; exit 2; }
  DEVICE="$IOS_SIM"
  xcrun simctl get_app_container "$IOS_SIM" "$APP_ID" >/dev/null 2>&1 || { echo "ERROR: $APP_ID not installed on $IOS_SIM"; exit 2; }

  CRASH_GLOBAL="$HOME/Library/Logs/DiagnosticReports"
  CRASH_SIM="$HOME/Library/Developer/CoreSimulator/Devices/$IOS_SIM/data/Library/Logs/CrashReporter"
  before_crashes() { ls -1 "$CRASH_GLOBAL"/Local-* "$CRASH_SIM"/Local-* 2>/dev/null | sort -u; }
  CRASHES_BEFORE="$(before_crashes)"

  log "Capturing simulator log (Fatal App Hang + signals)"
  xcrun simctl spawn "$IOS_SIM" log stream --level=default \
      --predicate 'eventMessage CONTAINS[c] "Local" OR senderImagePath CONTAINS "Local.app" OR eventMessage CONTAINS "Hang"' \
      > "$LOG" 2>&1 & LOG_PID=$!

  log "Phase 2-4 — Maestro chaos battery ($PASSES pass(es))"
  for _ in $(seq 1 "$PASSES"); do battery; done

  log "Phase 5/6 — network flaps + rotation"
  note "SKIP: simulator shares host network & has no reliable rotation hook (real-device only)"

  log "Phase 7 — deep-link cycling (cold + warm)"
  for id in p/chaos1 e/chaos2 v/chaos3 p/INVALID; do
    xcrun simctl openurl "$IOS_SIM" "https://localapp.live/$id" >/dev/null 2>&1 || true   # warm
    sleep 0.5
    xcrun simctl terminate "$IOS_SIM" "$APP_ID" >/dev/null 2>&1 || true                    # cold
    xcrun simctl openurl "$IOS_SIM" "https://localapp.live/$id" >/dev/null 2>&1 || true
    sleep 1
  done

  log "Liveness + crash-report check"
  if ! xcrun simctl spawn "$IOS_SIM" launchctl list 2>/dev/null | grep -q "$APP_ID"; then
    note "app not currently running — relaunching to confirm it still launches"
    xcrun simctl launch "$IOS_SIM" "$APP_ID" >/dev/null 2>&1 || fail "app failed to relaunch after chaos"
  fi
  CRASHES_AFTER="$(before_crashes)"
  NEW="$(comm -13 <(echo "$CRASHES_BEFORE") <(echo "$CRASHES_AFTER") | grep -v '^$' || true)"
  [ -n "$NEW" ] && echo "$NEW" | while read -r c; do fail "new crash report: $c"; done

  sleep 2; kill "$LOG_PID" 2>/dev/null; LOG_PID=""
  log "Scanning simulator log for hang/signal signatures"
  grep -aiE 'Fatal App Hang|EXC_BAD_ACCESS|EXC_CRASH|SIGABRT|SIGSEGV|semaphore_wait_trap|was terminated|watchdog' "$LOG" \
      | sort -u | head -20 | while read -r l; do fail "syslog: $l"; done

else
  echo "ERROR: platform must be android|ios"; exit 2
fi

# ─────────────────────────── Verdict ───────────────────────────
log "CHAOS VERDICT ($PLATFORM, passes=$PASSES)"
echo "device log: $LOG"
[ "$PLATFORM" = "android" ] && echo "monkey log: /tmp/chaos_monkey.log"
if [ -s "$SUMMARY" ]; then
  echo "🔴 FINDINGS:"; cat "$SUMMARY"; rm -f "$SUMMARY"; exit 1
else
  echo "🟢 No crashes, hangs, ANRs, or new crash reports detected."; rm -f "$SUMMARY"; exit 0
fi
