#!/usr/bin/env bash
# Checks every board in src/main/resources/companies.yml against the live ATS API and prints its status and
# posting count. Boards move between ATSes; run this before a benchmark. One request per second.
set -euo pipefail
cd "$(dirname "$0")/.."

grep -E '^\s*- \{ name:' src/main/resources/companies.yml | while read -r line; do
  ats=$(sed -E 's/.*ats: *([A-Z]+).*/\1/' <<<"$line")
  token=$(sed -E 's/.*board-token: *([^ }]+).*/\1/' <<<"$line")
  case "$ats" in
    GREENHOUSE) code=$(curl -s -o /tmp/jr-verify.json -w '%{http_code}' "https://boards-api.greenhouse.io/v1/boards/$token/jobs");
                count=$(jq '.jobs | length' /tmp/jr-verify.json 2>/dev/null || echo -) ;;
    LEVER)      code=$(curl -s -o /tmp/jr-verify.json -w '%{http_code}' "https://api.lever.co/v0/postings/$token?mode=json");
                count=$(jq 'length' /tmp/jr-verify.json 2>/dev/null || echo -) ;;
    ASHBY)      code=$(curl -s -o /tmp/jr-verify.json -w '%{http_code}' "https://api.ashbyhq.com/posting-api/job-board/$token");
                count=$(jq '.jobs | length' /tmp/jr-verify.json 2>/dev/null || echo -) ;;
    WORKDAY)    IFS=/ read -r tenant wd site <<<"$token"
                code=$(curl -s -o /tmp/jr-verify.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
                  -d '{"appliedFacets":{},"limit":20,"offset":0,"searchText":""}' \
                  "https://$tenant.$wd.myworkdayjobs.com/wday/cxs/$tenant/$site/jobs");
                count=$(jq '.total' /tmp/jr-verify.json 2>/dev/null || echo -) ;;
    SMARTRECRUITERS) code=$(curl -s -o /tmp/jr-verify.json -w '%{http_code}' "https://api.smartrecruiters.com/v1/companies/$token/postings?limit=1");
                count=$(jq '.totalFound' /tmp/jr-verify.json 2>/dev/null || echo -) ;;
    *) code="?"; count="-" ;;
  esac
  flag=""
  if [[ "$code" != "200" || "$count" == "0" ]]; then flag="  <-- check"; fi
  printf '%-16s %-40s %s %6s%s\n' "$ats" "$token" "$code" "$count" "$flag"
  sleep 1
done
