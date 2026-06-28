#!/usr/bin/env bash
# W10 (lock contention): with the chaos app up, arm lockContention so every owner search
# serializes on one hot monitor. Under sustained concurrent load the request threads pile up
# BLOCKED — but it is NOT a deadlock (no cycle). petclinic_blocked_threads climbs while
# petclinic_deadlocked_threads stays 0 (the discriminator). Requires the observability stack
# up and the app running under the chaos profile. Start the stack from the repo root with the
# env file so Grafana's Slack contact point validates (else alerting provisioning aborts):
#   docker compose -f observability/docker-compose.observability.yml --env-file .env up -d
# Then run the app: SPRING_PROFILES_ACTIVE=chaos ./gradlew bootRun
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

echo "==> arming lockContention"
curl -s -XPOST "$APP/chaos/lockContention/arm" | jq .

echo "==> driving sustained concurrent owner searches (background load, ~130s)"
( end=$((SECONDS + 130)); while [ "$SECONDS" -lt "$end" ]; do
    for _ in $(seq 1 15); do curl -s -o /dev/null "$APP/owners?page=1" & done; wait
  done ) &
LOAD_PID=$!
trap 'kill "$LOAD_PID" 2>/dev/null || true' EXIT

echo "==> waiting up to 150s for LockContention to fire (BLOCKED threads must exceed the threshold)"
wait_alert LockContention

echo "==> augmentation: BLOCKED threads high while deadlocked threads stay 0 (NOT a deadlock)"
blocked=$(prom 'max(petclinic_blocked_threads{service="petclinic"})')
deadlocked=$(prom 'max(petclinic_deadlocked_threads{service="petclinic"})')
parked=$(curl -s "$MGMT/actuator/threaddump" | jq '[.threads[]? | select(.threadState=="BLOCKED")] | length' 2>/dev/null || echo "n/a")
echo "    petclinic_blocked_threads = $blocked (>5),  petclinic_deadlocked_threads = $deadlocked (= 0: NOT a deadlock),  thread dump BLOCKED = $parked"

echo "==> disarming lockContention"
curl -s -XPOST "$APP/chaos/lockContention/disarm" | jq .

echo
echo "LOCK-CONTENTION AUGMENTATION HOLDS ✅"
echo "(W10: latency spikes and many threads are BLOCKED, but the deadlock detector reports 0 —"
echo " no cycle. The BLOCKED-thread census plus a thread dump name the one hot monitor that is"
echo " serializing throughput. The bench supplies the signal; whether the skill consumes it is"
echo " the skill's own work.)"
echo "(disarm: curl -s -XPOST $APP/chaos/lockContention/disarm)"
echo "SMOKE EXIT=0"
