#!/usr/bin/env bash
# Live ingest comparison against the real ATS APIs: SEQUENTIAL vs PLATFORM_POOL vs VIRTUAL on the 45
# Greenhouse/Lever/Ashby boards, RUNS times each, with the production rate limiter (2 req/s per host).
# Requires JobRadar running on $BASE (default http://localhost:8080). Waits 15 s between runs.
# Output: bench/results/live-ingest-<date>.csv
set -euo pipefail
cd "$(dirname "$0")/.."
BASE="${BASE:-http://localhost:8080}"
RUNS="${RUNS:-3}"
OUT="bench/results/live-ingest-$(date +%F).csv"
mkdir -p bench/results

companies=$(curl -sf "$BASE/api/companies" | jq -c '[.[] | select(.ats == "GREENHOUSE" or .ats == "LEVER" or .ats == "ASHBY") | .boardToken]')
n=$(jq length <<<"$companies")
echo "mode,n,run,wall_ms,fetch_ms,persist_ms,postings,failures" > "$OUT"
for run in $(seq 1 "$RUNS"); do
  # Rotate the order each run so no mode always goes first (warm caches, time-of-day effects).
  case $((run % 3)) in
    1) modes="SEQUENTIAL PLATFORM_POOL VIRTUAL" ;;
    2) modes="PLATFORM_POOL VIRTUAL SEQUENTIAL" ;;
    0) modes="VIRTUAL SEQUENTIAL PLATFORM_POOL" ;;
  esac
  for mode in $modes; do
    report=$(curl -sf -X POST "$BASE/api/ingest" -H 'Content-Type: application/json' \
      -d "{\"companies\":$companies,\"mode\":\"$mode\"}")
    jq -r --arg mode "$mode" --arg n "$n" --arg run "$run" \
      '[$mode, $n, $run, .wallMillis, .fetchMillis, .persistMillis, .postingsFetched, (.failures | length)] | @csv' \
      <<<"$report" | tr -d '"' >> "$OUT"
    echo "$mode run $run: $(jq '.wallMillis' <<<"$report") ms, $(jq '.postingsFetched' <<<"$report") postings"
    sleep 15
  done
done
echo "Wrote $OUT"
