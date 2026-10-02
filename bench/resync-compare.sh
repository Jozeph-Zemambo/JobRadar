#!/usr/bin/env bash
# Measures the re-sync path (a daily crawl over boards already in the database) for one or more jars.
# Each run starts the jar on a fresh copy of the same database snapshot, ingests the 45 Greenhouse/Lever/Ashby
# boards live with virtual threads, records the report, and stops the jar. Jars are alternated run by run so
# network and time-of-day drift affect them equally.
# Usage: bench/resync-compare.sh <snapshot.mv.db> <runs> <label>=<jar> [<label>=<jar> ...]
# Output: bench/results/resync-<date>.csv
set -euo pipefail
cd "$(dirname "$0")/.."
SNAPSHOT="$1"; RUNS="$2"; shift 2
OUT="bench/results/resync-$(date +%F).csv"
PORT=18181
mkdir -p bench/results
[[ -f "$OUT" ]] || echo "label,run,wall_ms,fetch_ms,persist_ms,postings,updated,closed" > "$OUT"

for run in $(seq 1 "$RUNS"); do
  for spec in "$@"; do
    label="${spec%%=*}"; jar="${spec#*=}"
    work=$(mktemp -d)
    cp "$SNAPSHOT" "$work/jobradar.mv.db"
    java -jar "$jar" --server.port=$PORT \
      --spring.datasource.url="jdbc:h2:file:$work/jobradar;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE" > "$work/server.log" 2>&1 &
    pid=$!
    for _ in $(seq 1 60); do curl -sf "localhost:$PORT/actuator/health" > /dev/null && break; sleep 1; done
    companies=$(curl -sf "localhost:$PORT/api/companies" | jq -c '[.[] | select(.ats == "GREENHOUSE" or .ats == "LEVER" or .ats == "ASHBY") | .boardToken]')
    report=$(curl -sf -X POST "localhost:$PORT/api/ingest" -H 'Content-Type: application/json' \
      -d "{\"companies\":$companies,\"mode\":\"VIRTUAL\"}")
    kill "$pid"; wait "$pid" 2>/dev/null || true
    rm -rf "$work"
    jq -r --arg label "$label" --arg run "$run" \
      '[$label, $run, .wallMillis, .fetchMillis, .persistMillis, .postingsFetched, .sync.updated, .sync.closed] | @csv' \
      <<<"$report" | tr -d '"' >> "$OUT"
    echo "$label run $run: persist $(jq '.persistMillis' <<<"$report") ms, wall $(jq '.wallMillis' <<<"$report") ms"
    sleep 10
  done
done
echo "Wrote $OUT"
