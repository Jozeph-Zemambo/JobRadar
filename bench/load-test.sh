#!/usr/bin/env bash
# API latency under load with ApacheBench (ships with macOS). Requires JobRadar running on $BASE with data
# from a real crawl. Warms up each endpoint, then measures at two concurrency levels.
# Output: bench/results/load-<date>.csv and the raw ab output in bench/results/load-<date>/
set -euo pipefail
cd "$(dirname "$0")/.."
BASE="${BASE:-http://localhost:8080}"
REQUESTS="${REQUESTS:-5000}"
WARMUP="${WARMUP:-2000}"
DATE=$(date +%F)
OUT="bench/results/load-$DATE.csv"
RAW="bench/results/load-$DATE"
mkdir -p "$RAW"

id=$(curl -sf "$BASE/api/postings?size=1" | jq '.items[0].id')
endpoints=(
  "postings_ranked|/api/postings?minScore=0.5&size=50"
  "postings_filtered|/api/postings?ats=GREENHOUSE&q=engineer&size=50"
  "posting_detail|/api/postings/$id"
  "stats|/api/stats"
)

echo "endpoint,concurrency,requests,failed,rps,p50_ms,p95_ms,p99_ms,max_ms" > "$OUT"
for entry in "${endpoints[@]}"; do
  name="${entry%%|*}"; path="${entry#*|}"
  ab -q -k -n "$WARMUP" -c 10 "$BASE$path" > /dev/null
  for c in 10 50; do
    file="$RAW/$name-c$c.txt"
    ab -q -k -n "$REQUESTS" -c "$c" "$BASE$path" > "$file"
    rps=$(awk '/Requests per second/ {print $4}' "$file")
    failed=$(awk '/Failed requests/ {print $3}' "$file")
    p50=$(awk '$1 == "50%" {print $2}' "$file")
    p95=$(awk '$1 == "95%" {print $2}' "$file")
    p99=$(awk '$1 == "99%" {print $2}' "$file")
    max=$(awk '$1 == "100%" {print $2}' "$file")
    echo "$name,$c,$REQUESTS,$failed,$rps,$p50,$p95,$p99,$max" >> "$OUT"
    echo "$name c=$c: p50=${p50}ms p95=${p95}ms p99=${p99}ms rps=$rps failed=$failed"
  done
done
echo "Wrote $OUT"
