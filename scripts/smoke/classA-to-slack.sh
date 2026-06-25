#!/usr/bin/env bash
# End-to-end smoke for AC A4: arm the Class A chaos fault, trigger it, and
# verify the Grafana alert transitions to Firing (Slack delivery confirmed
# by the human in the channel). Assumes:
#   - the host app is running under the chaos profile (writing logs/petclinic.json)
#   - the observability stack is up (docker compose --env-file ../.env up -d)
set -euo pipefail

APP=http://localhost:8080
GRAFANA=http://admin:admin@localhost:3000

echo "==> arming Class A (ownerSearchNpe)"
curl -s -XPOST "$APP/chaos/ownerSearchNpe/arm" | jq .

echo "==> triggering the seeded NPE (owner search with no last name)"
curl -s -o /dev/null -w 'search HTTP %{http_code}\n' "$APP/owners?page=1" || true

echo "==> waiting up to 90s for the Grafana alert to fire"
for i in $(seq 1 18); do
  state=$(curl -s "$GRAFANA/api/alertmanager/grafana/api/v2/alerts" \
    | jq -r '.[] | select(.labels.alertname=="ClassAOwnerSearchNPE") | .status.state' | head -1)
  if [ "$state" = "active" ]; then
    echo "ALERT FIRING ✅  (check the Slack channel for the message)"
    exit 0
  fi
  sleep 5
done

echo "ALERT DID NOT FIRE ❌ — check: app armed? NPE in logs/petclinic.json? Loki has the line? Grafana rule healthy (GET /api/v1/provisioning/alert-rules)?"
exit 1
