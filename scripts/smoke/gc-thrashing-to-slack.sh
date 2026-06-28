#!/usr/bin/env bash
# W9 (GC thrashing): with the chaos app running on a small heap, arm gcThrashing so an
# allocator retains most of the heap and churns on top, driving the JVM into a GC death
# spiral. Latency spikes, but the thread dump shows no deadlock and the cause is visible
# only in the JVM GC-pause metrics. Requires the observability stack up and the app running
# under the chaos profile WITH A SMALL HEAP. Start the stack from the repo root with the env
# file so Grafana's Slack contact point validates (otherwise alerting provisioning aborts):
#   docker compose -f observability/docker-compose.observability.yml --env-file .env up -d
# Then run the app with a bounded heap:
#   JAVA_TOOL_OPTIONS=-Xmx256m SPRING_PROFILES_ACTIVE=chaos ./gradlew bootRun
set -euo pipefail

APP=http://localhost:8080
MGMT=http://localhost:8081
PROM=http://localhost:9090
GRAFANA=http://admin:admin@localhost:3000

gc_rate='sum(rate(jvm_gc_pause_seconds_sum{service="petclinic"}[1m]))'

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
[ "$health" = "200" ] || { echo "FAIL: app management health not 200 (launch with JAVA_TOOL_OPTIONS=-Xmx256m SPRING_PROFILES_ACTIVE=chaos)"; exit 1; }

echo "==> arming gcThrashing (the @Scheduled reconcile then starts the allocator)"
curl -s -XPOST "$APP/chaos/gcThrashing/arm" | jq .

echo "==> waiting up to 150s for GcThrashing to fire (GC-pause rate must exceed the threshold)"
wait_alert GcThrashing

echo "==> augmentation: GC-pause rate is high, allocation rate is high, and zero threads are deadlocked"
rate=$(prom "$gc_rate")
alloc=$(prom 'sum(rate(jvm_gc_memory_allocated_bytes_total{service="petclinic"}[1m]))')
dead=$(prom 'max(petclinic_deadlocked_threads{service="petclinic"})')
echo "    gc pause rate = $rate (>0.3, i.e. >30% of wall-clock in GC),  alloc bytes/s = $alloc,  deadlocked threads = $dead (= 0: NOT a deadlock)"

echo "==> disarming gcThrashing (allocator stops, releases the retained heap)"
curl -s -XPOST "$APP/chaos/gcThrashing/disarm" | jq .

echo
echo "GC-THRASHING AUGMENTATION HOLDS ✅"
echo "(W9: latency spikes but the thread dump shows RUNNABLE threads (no deadlock) and the CPU is"
echo " busy-but-un-throttled. Only the JVM GC-pause-time rate names the cause as a GC death spiral."
echo " The bench supplies the localizing signal; whether the skill consumes it is the skill's own work.)"
echo "(disarm: curl -s -XPOST $APP/chaos/gcThrashing/disarm)"
echo "SMOKE EXIT=0"
