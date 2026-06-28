#!/usr/bin/env bash
# W11 (N+1 query count): the N+1 fault (ownerListLatency) already exists — owner search
# issues one extra findById per owner in the page. Its only standard signal is p99 latency,
# which cannot tell N+1 (N cheap queries) from a query-plan regression (1 slow query). This
# smoke arms the fault under load and reads the AUGMENTATION: mean queries-per-request. A high
# count names it N+1. Requires the observability stack up and the app under the chaos profile.
# Start the stack from the repo root with the env file so Grafana's Slack contact point
# validates (else alerting provisioning aborts):
#   docker compose -f observability/docker-compose.observability.yml --env-file .env up -d
# Then run the app on H2: SPRING_PROFILES_ACTIVE=chaos ./gradlew bootRun
set -euo pipefail

APP=http://localhost:8080
MGMT=http://localhost:8081
PROM=http://localhost:9090
GRAFANA=http://admin:admin@localhost:3000

prom() { # $1 = PromQL; echoes the scalar value (0 on any error)
  curl -s -G "$PROM/api/v1/query" --data-urlencode "query=$1" \
    | jq -r '.data.result[0].value[1] // "0"' 2>/dev/null || echo 0
}

alert_state() { # $1 = alertname; echoes the alert state ("" if absent)
  curl -s "$GRAFANA/api/alertmanager/grafana/api/v2/alerts" \
    | jq -r --arg n "$1" 'first(.[] | select(.labels.alertname==$n) | .status.state) // ""'
}

wait_alert() { # $1 = alertname; polls up to 150s for state=active
  local s=""
  for _ in $(seq 1 30); do
    s=$(alert_state "$1"); [ "$s" = "active" ] && { echo "    $1 FIRING ✅"; return 0; }
    sleep 5
  done
  echo "    $1 did NOT fire ❌ (state='$s')"; return 1
}

echo "==> confirming the chaos app is up"
health=$(curl -s -o /dev/null -w '%{http_code}' "$MGMT/actuator/health" 2>/dev/null || echo 000)
[ "$health" = "200" ] || { echo "FAIL: app management health not 200 (launch with SPRING_PROFILES_ACTIVE=chaos)"; exit 1; }

echo "==> arming ownerListLatency (the N+1 fault)"
curl -s -XPOST "$APP/chaos/ownerListLatency/arm" | jq .

echo "==> driving sustained concurrent owner searches (background load, ~130s)"
( end=$((SECONDS + 130)); while [ "$SECONDS" -lt "$end" ]; do
    for _ in $(seq 1 10); do curl -s -o /dev/null "$APP/owners?page=1" & done; wait
  done ) &
LOAD_PID=$!
trap 'kill "$LOAD_PID" 2>/dev/null || true' EXIT

echo "==> waiting up to 150s for NPlusOneQueries to fire (mean queries/request must exceed 3)"
wait_alert NPlusOneQueries

echo "==> augmentation: mean queries-per-request is high while latency alone could not name N+1"
mean=$(prom 'rate(petclinic_owner_search_queries_sum{uri="/owners"}[2m]) / rate(petclinic_owner_search_queries_count{uri="/owners"}[2m])')
echo "    petclinic_owner_search_queries mean = $mean per request (>3)"

echo "==> disarming ownerListLatency"
curl -s -XPOST "$APP/chaos/ownerListLatency/disarm" | jq .

echo
echo "N+1 QUERY-COUNT AUGMENTATION HOLDS ✅"
echo "(W11: the request is slow, but latency alone cannot tell N cheap queries from one slow"
echo " query. The per-request query count names it: a single logical owner read fanned out into"
echo " many physical queries — N+1. Same latency symptom as a query-plan regression, opposite"
echo " query count. The bench supplies the signal; whether the skill consumes it is its own work.)"
echo "(disarm: curl -s -XPOST $APP/chaos/ownerListLatency/disarm)"
echo "SMOKE EXIT=0"
