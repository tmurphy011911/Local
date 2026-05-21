#!/usr/bin/env bash
# Convenience wrapper for running loadtest scenarios.
#
# Usage:
#   ./run.sh smoke              # sanity-check every endpoint at low concurrency
#   ./run.sh reads              # all read scenarios sequentially
#   ./run.sh writes             # all write scenarios (requires WRITE=1 + dev URL)
#   ./run.sh bo-dry             # Bo full-turn in dry-run mode
#   ./run.sh mixed              # composite realistic workload
#   ./run.sh all                # everything (long; ~45 min wall time)
#   ./run.sh <scenario_name>    # single scenario by filename (no .js)
#
# Reads .env from cwd if it exists. Writes per-scenario JSON summaries
# to results/<scenario>-<timestamp>.json.

set -e
cd "$(dirname "$0")"

if [[ -f .env ]]; then
  set -a; source .env; set +a
fi

mkdir -p results

run() {
  local s="$1"
  local out="results/${s}-$(date +%s).json"
  echo ""
  echo "═══ ${s} ═══"
  k6 run --summary-export "${out}" "scenarios/${s}.js"
  echo "Summary written to ${out}"
}

case "${1:-help}" in
  smoke)
    # Each scenario for 30s @ 5 VUs — fast sanity check.
    for s in rpc-search-stratified carousels yocal-prompts bo-web-gate event-detail venue-search; do
      echo ""
      echo "═══ smoke: ${s} ═══"
      k6 run --vus 5 --duration 30s "scenarios/${s}.js" || true
    done
    ;;
  reads)
    for s in rpc-search-stratified carousels yocal-prompts bo-web-gate event-detail venue-search; do
      run "$s"
    done
    ;;
  writes)
    for s in plans-crud interactions validate-venue; do
      run "$s"
    done
    ;;
  bo-dry)
    DRY_RUN=1 run bo-full-turn
    ;;
  bo-real)
    echo "WARNING: bo-real will spend Anthropic tokens. Press Ctrl+C in 5s to abort."
    sleep 5
    DRY_RUN=0 run bo-full-turn
    ;;
  auth)
    run auth-otp
    ;;
  mixed)
    run mixed-realistic
    ;;
  all)
    for s in rpc-search-stratified carousels yocal-prompts bo-web-gate event-detail venue-search auth-otp mixed-realistic; do
      run "$s"
    done
    DRY_RUN=1 run bo-full-turn
    if [[ "${WRITE:-0}" == "1" ]]; then
      for s in plans-crud interactions validate-venue; do
        run "$s"
      done
    fi
    ;;
  help|--help|-h|"")
    sed -n '2,15p' "$0"
    ;;
  *)
    run "$1"
    ;;
esac
