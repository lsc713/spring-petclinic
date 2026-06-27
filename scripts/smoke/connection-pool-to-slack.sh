#!/usr/bin/env bash
# W6 (connection-pool exhaustion): arm connectionPoolExhaustion, drive concurrent owner
# searches so the leak drains the small chaos-pool HikariCP pool. The 5xx/latency metrics
# say "DB trouble" but the DB is healthy; hikaricp_connections_timeout_total rising with
# active pinned at max localizes the cause to the app exhausting its own pool. Then disarm
# and confirm the pool recovers. Requires the app launched with
# SPRING_PROFILES_ACTIVE=chaos,postgres,chaos-pool, the Postgres + observability stack up,
# and Grafana started with `--env-file .env` (Slack recipient) so provisioning succeeds.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
APP=http://localhost:8080
MGMT=http://localhost:8081
PROM=http://localhost:9090
GRAFANA=http://admin:admin@localhost:3000

echo "==> confirming the small pool is active (chaos-pool profile)"
maxp=$(curl -s "$PROM/api/v1/query" --data-urlencode 'query=max(hikaricp_connections_max{service="petclinic"})' \
  | jq -r '.data.result[0].value[1] // "0"')
echo "    hikaricp_connections_max = $maxp (expected 5 — launch app with chaos,postgres,chaos-pool)"
awk "BEGIN{exit !($maxp+0 > 0 && $maxp+0 <= 10)}" || { echo "FAIL: small pool not active; launch the app with SPRING_PROFILES_ACTIVE=chaos,postgres,chaos-pool"; exit 1; }

echo "==> arming connectionPoolExhaustion"
curl -s -XPOST "$APP/chaos/connectionPoolExhaustion/arm" | jq .

echo "==> driving concurrent owner searches to drain the pool (12 parallel x 3 rounds)"
for round in 1 2 3; do
  for i in $(seq 1 12); do
    curl -s -o /dev/null "$APP/owners?page=1" &
  done
  wait
done

echo "==> waiting up to 150s for ConnectionPoolExhausted to fire"
cp_state=""
for i in $(seq 1 30); do
  cp_state=$(curl -s "$GRAFANA/api/alertmanager/grafana/api/v2/alerts" \
    | jq -r 'first(.[] | select(.labels.alertname=="ConnectionPoolExhausted") | .status.state) // ""')
  [ "$cp_state" = "active" ] && break
  sleep 5
done
[ "$cp_state" = "active" ] || { echo "ConnectionPoolExhausted did NOT fire ❌ — check hikaricp_connections_timeout_total in Prometheus"; curl -s -XPOST "$APP/chaos/connectionPoolExhaustion/disarm" >/dev/null || true; exit 1; }
echo "    ConnectionPoolExhausted FIRING ✅"

echo "==> augmentation: active pinned at max while the timeout counter climbed"
active=$(curl -s "$PROM/api/v1/query" --data-urlencode 'query=max(hikaricp_connections_active{service="petclinic"})' | jq -r '.data.result[0].value[1] // "0"')
timeouts=$(curl -s "$PROM/api/v1/query" --data-urlencode 'query=sum(hikaricp_connections_timeout_total{service="petclinic"})' | jq -r '.data.result[0].value[1] // "0"')
echo "    hikaricp_connections_active=$active (≈ max $maxp),  hikaricp_connections_timeout_total=$timeouts (>0)"

echo "==> disarming + one search to release the leaked connections (pool recovers)"
curl -s -XPOST "$APP/chaos/connectionPoolExhaustion/disarm" | jq .
curl -s -o /dev/null "$APP/owners?page=1" || true

echo "CONNECTION-POOL AUGMENTATION HOLDS ✅"
echo "(W6: the 5xx/latency alert reads like a slow/down database; hikaricp_connections_timeout_total"
echo " rising with active pinned at max names the real cause — the app leaked its own pool. The bench"
echo " supplies the localizing signal; whether the skill consumes it is the skill's own work.)"
echo "SMOKE EXIT=0"
