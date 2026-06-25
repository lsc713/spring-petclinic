#!/usr/bin/env bash
# AC A8: arm the opaque-5xx error-ratio fault, drive load, verify the
# ErrorRatioNoTrace alert reaches Firing. Assumes host app (chaos) + stack up.
set -euo pipefail

APP=http://localhost:8080
GRAFANA=http://admin:admin@localhost:3000
ROOT=$(cd "$(dirname "$0")/../.." && pwd)

echo "==> arming vetListError"
curl -s -XPOST "$APP/chaos/vetListError/arm" | jq .

echo "==> driving load (k6, ~45s)"
docker run --rm -i --add-host=host.docker.internal:host-gateway grafana/k6 run - \
  < "$ROOT/scripts/load/error-ratio.js" | grep -E 'http_reqs' || true

echo "==> waiting up to 120s for ErrorRatioNoTrace to fire"
for i in $(seq 1 24); do
  state=$(curl -s "$GRAFANA/api/alertmanager/grafana/api/v2/alerts" \
    | jq -r '.[] | select(.labels.alertname=="ErrorRatioNoTrace") | .status.state' | head -1)
  if [ "$state" = "active" ]; then
    echo "ERROR-RATIO ALERT FIRING ✅ (check Slack)"; exit 0
  fi
  sleep 5
done

echo "ERROR-RATIO ALERT DID NOT FIRE ❌ — check Prometheus SERVER_ERROR ratio; confirm /vets.html returns 5xx while armed."
exit 1
