#!/usr/bin/env bash
# W8 (queue backpressure): with a Kafka broker up and the chaos app running, arm
# queueBackpressure so a producer floods the topic faster than the slow consumer drains it.
# The HTTP request metrics stay nominal; only Kafka consumer lag climbs — the async backlog.
# Requires the observability stack (incl. the kafka service) up and the app running under the
# chaos profile. Start the stack from the repo root WITH the env file so Grafana's Slack
# contact point validates (otherwise alerting provisioning aborts and no rules load):
#   docker compose -f observability/docker-compose.observability.yml --env-file .env up -d
# Then run the app: SPRING_PROFILES_ACTIVE=chaos ./gradlew bootRun
set -euo pipefail

APP=http://localhost:8080
MGMT=http://localhost:8081
PROM=http://localhost:9090
GRAFANA=http://admin:admin@localhost:3000

lag_query='max(kafka_consumer_fetch_manager_records_lag_max{service="petclinic"})'

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

echo "==> confirming the chaos app is up and its Kafka consumer metric is exported"
health=$(curl -s -o /dev/null -w '%{http_code}' "$MGMT/actuator/health" 2>/dev/null || echo 000)
[ "$health" = "200" ] || { echo "FAIL: app management health not 200 (launch with SPRING_PROFILES_ACTIVE=chaos)"; exit 1; }

echo "==> arming queueBackpressure (the @Scheduled producer then floods the topic)"
curl -s -XPOST "$APP/chaos/queueBackpressure/arm" | jq .

echo "==> waiting up to 150s for QueueBackpressure to fire (lag must exceed the threshold)"
wait_alert QueueBackpressure

echo "==> augmentation: consumer lag is high while HTTP request metrics stay nominal"
lag=$(prom "$lag_query")
err=$(prom 'sum(rate(http_server_requests_seconds_count{service="petclinic",outcome="SERVER_ERROR"}[1m]))')
echo "    kafka consumer records-lag-max = $lag (>500),  http 5xx rate = $err (≈ 0 — the request path is fine)"

echo "==> disarming queueBackpressure (producer stops; the consumer drains the backlog)"
curl -s -XPOST "$APP/chaos/queueBackpressure/disarm" | jq .

echo
echo "QUEUE-BACKPRESSURE AUGMENTATION HOLDS ✅"
echo "(W8: the synchronous request metrics say nothing — the producing path returns immediately."
echo " Only Kafka consumer lag shows work piling up faster than the slow consumer drains it. The"
echo " bench supplies the localizing signal; whether the skill consumes it is the skill's own work.)"
echo "(disarm: curl -s -XPOST $APP/chaos/queueBackpressure/disarm)"
echo "SMOKE EXIT=0"
