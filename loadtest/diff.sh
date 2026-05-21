#!/usr/bin/env bash
# Compare two k6 summary JSON exports side-by-side.
#
# Usage:
#   ./diff.sh results/rpc-search-stratified-1234.json results/rpc-search-stratified-5678.json

set -e

a="$1"; b="$2"
if [[ -z "$a" || -z "$b" ]]; then
  echo "Usage: $0 <before.json> <after.json>"
  exit 1
fi

# Pull the metrics we care about from each summary and print a side-by-side
# diff. k6 stores all metrics in `.metrics.<name>.values.<stat>`.
node -e '
const fs = require("fs");
const a = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
const b = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));

function pluck(j) {
  const m = j.metrics || {};
  return {
    p50:    m.http_req_duration?.values?.["p(50)"]    ?? null,
    p95:    m.http_req_duration?.values?.["p(95)"]    ?? null,
    p99:    m.http_req_duration?.values?.["p(99)"]    ?? null,
    avg:    m.http_req_duration?.values?.avg          ?? null,
    failed: m.http_req_failed?.values?.rate           ?? null,
    rps:    m.http_reqs?.values?.rate                 ?? null,
    iters:  m.iterations?.values?.count               ?? null,
  };
}

const A = pluck(a), B = pluck(b);

const fmt = (n, suffix = "ms") => n == null ? "—" : `${(typeof n === "number" ? n.toFixed(1) : n)}${suffix}`;
const pct = (n) => n == null ? "—" : `${(n * 100).toFixed(2)}%`;
const delta = (x, y) => {
  if (x == null || y == null) return "";
  const diff = y - x;
  const sign = diff >= 0 ? "+" : "";
  return ` (${sign}${diff.toFixed(1)})`;
};
const deltaPct = (x, y) => {
  if (x == null || y == null || x === 0) return "";
  const diff = ((y - x) / x) * 100;
  const sign = diff >= 0 ? "+" : "";
  return ` (${sign}${diff.toFixed(1)}%)`;
};

console.log("");
console.log(`A: ${process.argv[1]}`);
console.log(`B: ${process.argv[2]}`);
console.log("");
console.log("metric          A              B              Δ");
console.log("─".repeat(60));
console.log(`p50 latency     ${fmt(A.p50).padEnd(15)}${fmt(B.p50).padEnd(15)}${deltaPct(A.p50, B.p50)}`);
console.log(`p95 latency     ${fmt(A.p95).padEnd(15)}${fmt(B.p95).padEnd(15)}${deltaPct(A.p95, B.p95)}`);
console.log(`p99 latency     ${fmt(A.p99).padEnd(15)}${fmt(B.p99).padEnd(15)}${deltaPct(A.p99, B.p99)}`);
console.log(`avg latency     ${fmt(A.avg).padEnd(15)}${fmt(B.avg).padEnd(15)}${deltaPct(A.avg, B.avg)}`);
console.log(`error rate      ${pct(A.failed).padEnd(15)}${pct(B.failed).padEnd(15)}${delta(A.failed, B.failed)}`);
console.log(`throughput      ${fmt(A.rps, " RPS").padEnd(15)}${fmt(B.rps, " RPS").padEnd(15)}${deltaPct(A.rps, B.rps)}`);
console.log(`iterations      ${(A.iters ?? "—").toString().padEnd(15)}${(B.iters ?? "—").toString().padEnd(15)}${delta(A.iters, B.iters)}`);
console.log("");

// Scan thresholds — if any failed, list them.
const tA = a.root_group?.checks || a.checks || {};
const tB = b.root_group?.checks || b.checks || {};
console.log("Threshold checks:");
console.log(`  A: ${a.metrics ? Object.keys(a.metrics).filter(k => a.metrics[k].thresholds).length : 0} thresholds defined`);
console.log(`  B: ${b.metrics ? Object.keys(b.metrics).filter(k => b.metrics[k].thresholds).length : 0} thresholds defined`);
' "$a" "$b"
