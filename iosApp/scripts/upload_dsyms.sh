#!/bin/sh
# Upload iOS dSYMs (app + Kotlin/Native framework) to Sentry so crash/hang
# stack traces symbolicate. Safe to run manually after a build/archive, or
# from an Xcode "Run Script" build phase (it auto-detects $DWARF_DSYM_FOLDER_PATH).
#
# Required env (set in CI secrets or your shell — never commit the token):
#   SENTRY_AUTH_TOKEN   Sentry auth token with project:write  (Settings → Auth Tokens)
#   SENTRY_ORG          org slug
#   SENTRY_PROJECT      project slug
#
# Manual use:
#   SENTRY_AUTH_TOKEN=... SENTRY_ORG=... SENTRY_PROJECT=... iosApp/scripts/upload_dsyms.sh
# Optional: pass an explicit search path as $1 (defaults to DerivedData if not in Xcode).

set -eu

# Xcode Run Script build phases run with a minimal PATH that omits Homebrew's
# bin dirs, so a `brew install`ed sentry-cli is invisible here even though it
# resolves fine in an interactive shell (/opt/homebrew/bin on Apple silicon,
# /usr/local/bin on Intel). Without this, the probe below fell through to the
# silent `exit 0` on EVERY archive — dSYMs never uploaded and shipped crashes
# came back unsymbolicated. Prepend both locations before probing.
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"

# dSYM upload only matters for symbolicating SHIPPED builds (Release / Staging
# archives). For a local Debug build it is irrelevant and must NEVER block the
# build. So on ANY problem below — missing CLI, missing creds, or a failed
# upload — hard-fail on non-Debug (a broken uploader can't ship silently again)
# but skip softly on Debug so local dev/RC runs aren't blocked.
soft_fail() {
  if [ "${CONFIGURATION:-}" != "Debug" ]; then
    echo "error: $1" >&2
    exit 1
  fi
  echo "warning: $1 — skipping (Debug build)" >&2
  exit 0
}

if ! command -v sentry-cli >/dev/null 2>&1; then
  soft_fail "sentry-cli not found on PATH — dSYMs will NOT upload and crashes won't symbolicate. Install: brew install getsentry/tools/sentry-cli"
fi

if [ -z "${SENTRY_ORG:-}" ] || [ -z "${SENTRY_PROJECT:-}" ] || [ -z "${SENTRY_AUTH_TOKEN:-}" ]; then
  soft_fail "Sentry credentials not set (SENTRY_ORG/SENTRY_PROJECT/SENTRY_AUTH_TOKEN) — dSYMs will NOT upload."
fi

# Pick where to look for dSYMs:
#  1) explicit arg, 2) Xcode's build var, 3) fall back to DerivedData.
if [ "${1:-}" != "" ]; then
  SEARCH_PATH="$1"
elif [ "${DWARF_DSYM_FOLDER_PATH:-}" != "" ]; then
  SEARCH_PATH="$DWARF_DSYM_FOLDER_PATH"
else
  SEARCH_PATH="$HOME/Library/Developer/Xcode/DerivedData"
fi

echo "Uploading dSYMs from: $SEARCH_PATH"
# Don't let a failed upload (stale token, no network, or no dSYMs produced for a
# Debug build) abort the Xcode build on Debug — soft_fail downgrades it there.
if ! sentry-cli debug-files upload \
  --org "$SENTRY_ORG" \
  --project "$SENTRY_PROJECT" \
  --include-sources \
  "$SEARCH_PATH"; then
  soft_fail "sentry-cli dSYM upload failed (bad/expired token, no network, or no dSYMs at $SEARCH_PATH)."
fi
