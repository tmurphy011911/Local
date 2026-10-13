#!/bin/bash
# Top up the PROD Daily Local bank via the generate-quiz-questions edge fn,
# one state per call (a single all-states call can exceed the fn wall clock).
#
#   scripts/topup_quiz_questions.sh            # MA dry run only
#   scripts/topup_quiz_questions.sh --apply    # all states, writes
# Secret: $QUIZ_GEN_SECRET if set, else QUIZ_GEN_SECRET= from ~/push-dev.env.
#
# Each cell is filled up to TARGET (default 40) unused questions, max 30 per call.
set -euo pipefail
if [ -z "${QUIZ_GEN_SECRET:-}" ]; then  # fall back to ~/push-dev.env
  QUIZ_GEN_SECRET=$(grep '^QUIZ_GEN_SECRET=' ~/push-dev.env 2>/dev/null | cut -d= -f2- || true)
fi
: "${QUIZ_GEN_SECRET:?set QUIZ_GEN_SECRET or add QUIZ_GEN_SECRET= to ~/push-dev.env}"
if [ "$QUIZ_GEN_SECRET" = "paste-secret-here" ]; then echo "Replace paste-secret-here with the real QUIZ_GEN_SECRET" >&2; exit 1; fi
TARGET="${TARGET:-40}"
ANON=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImJsaWZ1YnhzbGF1Y3NobWxwaGRjIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzI3MjgyNjEsImV4cCI6MjA4ODMwNDI2MX0.xx-iy7kKhHviAjtjfXdxNHp4pmKU-in3efr2SkvWTf0

call() {  # $1=state  $2=dryRun
  echo "== $1 (dryRun=$2) =="
  curl -sS --max-time 400 -X POST \
    "https://blifubxslaucshmlphdc.supabase.co/functions/v1/generate-quiz-questions" \
    -H "apikey: $ANON" -H "Authorization: Bearer $ANON" \
    -H "x-quiz-gen-secret: $QUIZ_GEN_SECRET" -H "Content-Type: application/json" \
    -d "{\"states\":[\"$1\"],\"targetUnused\":$TARGET,\"dryRun\":$2}" |
  python3 -c '
import json,sys
raw=sys.stdin.read()
try: d=json.loads(raw)
except ValueError: print("ERROR (non-JSON):", raw[:300]); sys.exit(1)
if not d.get("ok"): print("ERROR:", d); sys.exit(1)
for r in d["results"]:
    g=r.get
    print("  %-6s unused=%3s req=%2s gen=%2s ins=%s would=%s rejected=%s dup=%s invalid=%s %s" % (
        g("difficulty"), g("unusedBefore"), g("requested"), g("generated",0), g("inserted",0),
        g("wouldInsert","-"), g("rejected",0), g("skippedDuplicate",0), g("invalid",0), g("error","")))
print("  totalInserted:", d.get("totalInserted"))'
}

if [ "${1:-}" != "--apply" ]; then
  call MA true
  echo; echo "Dry run only. Check that rejected < gen and there is no 'verify:' error, then re-run with --apply."
  exit 0
fi
for s in MA PA RI NY NJ CA; do call "$s" false; done
