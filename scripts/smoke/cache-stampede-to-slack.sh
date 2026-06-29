#!/usr/bin/env bash
# W12 (cache stampede): with the chaos app up, arm cacheStampede so every owner search runs a
# self-contained single-entry cache with a short TTL and NO single-flight guard. Under sustained
# concurrent load, when the entry expires the whole herd recomputes it at once — petclinic_cache_
# recompute_inflight spikes to the concurrency level. Latency alone (or a cache-miss count) cannot
# name this; the in-flight concurrency does: N requests recomputing the SAME value at once. Requires
# the observability stack up and the app under the chaos profile. Start the stack from the repo root
# with the env file so Grafana's Slack contact point validates (else alerting provisioning aborts):
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

echo "==> arming cacheStampede"
curl -s -XPOST "$APP/chaos/cacheStampede/arm" | jq .

echo "==> driving sustained concurrent owner searches (background load, ~130s)"
( end=$((SECONDS + 130)); while [ "$SECONDS" -lt "$end" ]; do
    for _ in $(seq 1 12); do curl -s -o /dev/null "$APP/owners?page=1" & done; wait
  done ) &
LOAD_PID=$!
trap 'kill "$LOAD_PID" 2>/dev/null || true' EXIT

echo "==> waiting up to 150s for CacheStampede to fire (peak concurrent recomputes must exceed 3)"
wait_alert CacheStampede

echo "==> augmentation: many threads recompute the SAME value at once (a herd), not one slow load"
peak=$(prom 'max_over_time(petclinic_cache_recompute_inflight{service="petclinic"}[2m])')
total=$(prom 'petclinic_cache_recompute_total{service="petclinic"}')
echo "    petclinic_cache_recompute_inflight peak = $peak (>3),  petclinic_cache_recompute_total = $total"

echo "==> disarming cacheStampede"
curl -s -XPOST "$APP/chaos/cacheStampede/disarm" | jq .

echo
echo "CACHE-STAMPEDE AUGMENTATION HOLDS ✅"
echo "(W12: the request is slow, but latency cannot tell a herd from one slow load. The in-flight"
echo " recompute count names it: when the cached entry expires, N requests recompute the SAME value"
echo " concurrently because the cache has no single-flight guard — distinct from N+1's N different"
echo " queries within one request. The bench supplies the signal; whether the skill consumes it is"
echo " the skill's own work.)"
echo "(disarm: curl -s -XPOST $APP/chaos/cacheStampede/disarm)"
echo "SMOKE EXIT=0"
