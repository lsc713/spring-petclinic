#!/usr/bin/env bash
# AC W5c-3 + W5c-4 (deadlock): arm the deadlock fault and trigger it with one owner search.
# Standard latency/error metrics are blind to a deadlock; the DeadlockProbe gauge surfaces its
# existence and DeadlockDetected fires; the thread dump localizes the BLOCKED thread cycle.
# Requires the host app (chaos profile) and the observability stack up.
set -euo pipefail

APP=http://localhost:8080
GRAFANA=http://admin:admin@localhost:3000

echo "==> arming deadlock"
curl -s -XPOST "$APP/chaos/deadlock/arm" | jq .

echo "==> triggering it with one owner search (spawns the deadlock pair)"
curl -s -o /dev/null -w '    /owners?lastName=Davis -> HTTP %{http_code}\n' "$APP/owners?lastName=Davis"

echo "==> W5c-3: waiting up to 60s for DeadlockDetected to fire"
dl_state=""
for i in $(seq 1 12); do
  dl_state=$(curl -s "$GRAFANA/api/alertmanager/grafana/api/v2/alerts" \
    | jq -r 'first(.[] | select(.labels.alertname=="DeadlockDetected") | .status.state) // ""')
  [ "$dl_state" = "active" ] && break
  sleep 5
done
[ "$dl_state" = "active" ] || { echo "DeadlockDetected did NOT fire ❌ — check petclinic_deadlocked_threads in Prometheus"; exit 1; }
echo "    DeadlockDetected FIRING ✅"

echo "==> W5c-4: thread dump localizes the deadlock (BLOCKED chaos-deadlock threads)"
blocked=$(curl -s "$APP/actuator/threaddump" \
  | jq '[.threads[] | select(.threadName | startswith("chaos-deadlock")) | select(.threadState=="BLOCKED")] | length')
echo "    BLOCKED chaos-deadlock threads = $blocked (expected >= 2)"
awk "BEGIN{exit !($blocked+0 >= 2)}" || { echo "FAIL: thread dump did not localize the deadlock (blocked=$blocked)"; exit 1; }

echo "DEADLOCK AUGMENTATION HOLDS ✅"
echo "(W5c: latency/error metrics never see a deadlock; the probe surfaces its existence and"
echo " the thread dump names the two BLOCKED threads stuck on each other's monitor. The bench"
echo " supplies the localizing signal — whether the skill consumes it is the skill's own work.)"
echo "(disarm: curl -s -XPOST $APP/chaos/deadlock/disarm)"
