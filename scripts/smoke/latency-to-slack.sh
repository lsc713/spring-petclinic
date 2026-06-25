#!/usr/bin/env bash
# AC A6: arm the N+1 latency fault, drive load, verify the LatencySaturation
# alert reaches Firing. Assumes the host app (chaos profile) and the
# observability stack (docker compose --env-file ../.env up -d) are running.
set -euo pipefail

APP=http://localhost:8080
GRAFANA=http://admin:admin@localhost:3000
ROOT=$(cd "$(dirname "$0")/../.." && pwd)

echo "==> arming ownerListLatency"
curl -s -XPOST "$APP/chaos/ownerListLatency/arm" | jq .

echo "==> driving load (k6, ~60s)"
docker run --rm -i --add-host=host.docker.internal:host-gateway grafana/k6 run - \
  < "$ROOT/scripts/load/latency.js" | grep -E 'http_reqs' || true

echo "==> waiting up to 120s for LatencySaturation to fire"
for i in $(seq 1 24); do
  state=$(curl -s "$GRAFANA/api/alertmanager/grafana/api/v2/alerts" \
    | jq -r '.[] | select(.labels.alertname=="LatencySaturation") | .status.state' | head -1)
  if [ "$state" = "active" ]; then
    echo "LATENCY ALERT FIRING ✅ (check Slack)"; exit 0
  fi
  sleep 5
done

echo "LATENCY ALERT DID NOT FIRE ❌ — check Prometheus p99 for uri=/owners; the p99 threshold may need tuning for this machine (rules.yml chaos-latency params)."
exit 1
