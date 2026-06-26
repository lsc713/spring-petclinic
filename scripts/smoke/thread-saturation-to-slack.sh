#!/usr/bin/env bash
# AC W5c-1 + W5c-2 (thread-pool saturation): arm threadStarvation, drive concurrent load so
# the small chaos-threads pool saturates. The metric says "saturated" but not why; the thread
# dump localizes it (N threads parked in ActiveChaosFaults.maybeBlockWorker). Requires the app
# launched with SPRING_PROFILES_ACTIVE=chaos,chaos-threads and the observability stack up.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
APP=http://localhost:8080
PROM=http://localhost:9090
GRAFANA=http://admin:admin@localhost:3000

echo "==> confirming the small pool is active (chaos-threads profile)"
maxt=$(curl -s "$PROM/api/v1/query" --data-urlencode 'query=max(tomcat_threads_config_max_threads{service="petclinic"})' \
  | jq -r '.data.result[0].value[1] // "0"')
echo "    tomcat_threads_config_max_threads = $maxt (expected 10 — launch app with chaos,chaos-threads)"
awk "BEGIN{exit !($maxt+0 > 0 && $maxt+0 <= 20)}" || { echo "FAIL: small pool not active; launch the app with SPRING_PROFILES_ACTIVE=chaos,chaos-threads"; exit 1; }

echo "==> arming threadStarvation"
curl -s -XPOST "$APP/chaos/threadStarvation/arm" | jq .

echo "==> driving concurrent load (k6, 30 VUs / 90s — outlasts the 60s alert-poll window)"
docker run --rm -i --add-host=host.docker.internal:host-gateway \
  grafana/k6 run - < "$ROOT/scripts/load/thread-saturation.js" >/dev/null 2>&1 &
K6_PID=$!

echo "==> W5c-1: waiting up to 60s for ThreadPoolSaturation to fire"
sat_state=""
for i in $(seq 1 12); do
  sat_state=$(curl -s "$GRAFANA/api/alertmanager/grafana/api/v2/alerts" \
    | jq -r 'first(.[] | select(.labels.alertname=="ThreadPoolSaturation") | .status.state) // ""')
  [ "$sat_state" = "active" ] && break
  sleep 5
done
[ "$sat_state" = "active" ] || { echo "ThreadPoolSaturation did NOT fire ❌ — check tomcat_threads_busy_threads in Prometheus"; kill $K6_PID 2>/dev/null || true; exit 1; }
echo "    ThreadPoolSaturation FIRING ✅"

echo "==> W5c-2: thread dump localizes the cause (threads parked in maybeBlockWorker)"
parked=$(curl -s "$APP/actuator/threaddump" \
  | jq '[.threads[] | select(any(.stackTrace[]?; .methodName=="maybeBlockWorker"))] | length')
echo "    threads parked in maybeBlockWorker = $parked (expected >= 5)"
kill $K6_PID 2>/dev/null || true
awk "BEGIN{exit !($parked+0 >= 5)}" || { echo "FAIL: thread dump did not localize the saturation (parked=$parked)"; exit 1; }

echo "THREAD-SATURATION AUGMENTATION HOLDS ✅"
echo "(W5c: the metric alert says 'saturated' with no cause; the thread dump names the exact"
echo " frame (ActiveChaosFaults.maybeBlockWorker) where the pool is stuck. The bench supplies"
echo " the localizing signal — whether the skill consumes it is the skill's own work.)"
echo "(disarm: curl -s -XPOST $APP/chaos/threadStarvation/disarm)"
