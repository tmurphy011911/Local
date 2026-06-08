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

if ! command -v sentry-cli >/dev/null 2>&1; then
  echo "warning: sentry-cli not installed — skipping dSYM upload. Install: brew install getsentry/tools/sentry-cli" >&2
  exit 0
fi

: "${SENTRY_ORG:?set SENTRY_ORG}"
: "${SENTRY_PROJECT:?set SENTRY_PROJECT}"
: "${SENTRY_AUTH_TOKEN:?set SENTRY_AUTH_TOKEN}"

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
sentry-cli debug-files upload \
  --org "$SENTRY_ORG" \
  --project "$SENTRY_PROJECT" \
  --include-sources \
  "$SEARCH_PATH"
