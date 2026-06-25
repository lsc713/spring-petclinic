#!/usr/bin/env bash
# AC A10 + A11: arm the silent owner-search corruption. Show that standard
# signals stay blind (HTTP 200, zero 5xx, no ERROR logs) while the correctness
# oracle surfaces the fault and fires the SilentCorruption alert. Assumes the
# host app (chaos profile) and the observability stack (docker compose
# --env-file ../.env up -d) are running.
set -euo pipefail

APP=http://localhost:8080
PROM=http://localhost:9090
GRAFANA=http://admin:admin@localhost:3000

echo "==> arming ownerSearchCorruption"
curl -s -XPOST "$APP/chaos/ownerSearchCorruption/arm" | jq .

echo "==> A10: a valid search returns HTTP 200 (no error surfaced)"
code=$(curl -s -o /dev/null -w '%{http_code}' "$APP/owners?lastName=Davis")
echo "    /owners?lastName=Davis -> HTTP $code"
[ "$code" = "200" ] || { echo "expected 200 (silent), got $code"; exit 1; }

echo "==> A10: Prometheus shows zero 5xx for /owners (standard signal blind)"
sleep 8
err=$(curl -s "$PROM/api/v1/query" --data-urlencode \
  'query=sum(increase(http_server_requests_seconds_count{uri="/owners",outcome="SERVER_ERROR"}[2m]))' \
  | jq -r '.data.result[0].value[1] // "0"')
echo "    /owners SERVER_ERROR increase = $err (expected 0 — no 5xx)"
awk "BEGIN{exit !($err+0 < 1)}" || { echo "FAIL: /owners 5xx increase=$err, expected ~0 — A10 standard signals must be blind"; exit 1; }

echo "==> A11: waiting up to 120s for the correctness oracle to fire SilentCorruption"
for i in $(seq 1 24); do
  state=$(curl -s "$GRAFANA/api/alertmanager/grafana/api/v2/alerts" \
    | jq -r '.[] | select(.labels.alertname=="SilentCorruption") | .status.state' | head -1)
  if [ "$state" = "active" ]; then
    echo "SILENT-CORRUPTION ALERT FIRING ✅ — only the oracle caught it (check Slack)"
    echo "(A12: this alert carries no stack trace / no app frame — the triage skill cannot localize it; it needs spec-level intent.)"
    exit 0
  fi
  sleep 5
done

echo "SILENT-CORRUPTION ALERT DID NOT FIRE ❌ — check petclinic_correctness_violations_total in Prometheus; confirm the oracle @Scheduled probe is running under the chaos profile."
exit 1
