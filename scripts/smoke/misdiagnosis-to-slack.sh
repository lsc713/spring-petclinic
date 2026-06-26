#!/usr/bin/env bash
# AC A13 (misdiagnosis trap): arm the dbDown infra fault. Show that the owner-details
# read returns a 5xx WITH a stack trace, but its signature is database connectivity
# (DataAccessResourceFailureException / Connection refused) — so InfraDbDown fires with
# category=infrastructure while the Class A app-localization alert stays silent. The
# discriminator a triage skill must use: route to infra, do NOT open an app code PR.
# Assumes the host app (chaos profile) and the observability stack are running.
set -euo pipefail

APP=http://localhost:8080
GRAFANA=http://admin:admin@localhost:3000

echo "==> arming dbDown"
curl -s -XPOST "$APP/chaos/dbDown/arm" | jq .

echo "==> A13: owner-details read returns a 5xx (with a stack trace)"
code=$(curl -s -o /dev/null -w '%{http_code}' "$APP/owners/1")
echo "    /owners/1 -> HTTP $code"
case "$code" in 5*) : ;; *) echo "expected a 5xx, got $code"; exit 1 ;; esac

echo "==> A13: waiting up to 120s for InfraDbDown to fire with category=infrastructure"
infra_state=""
for i in $(seq 1 24); do
  infra_state=$(curl -s "$GRAFANA/api/alertmanager/grafana/api/v2/alerts" \
    | jq -r 'first(.[] | select(.labels.alertname=="InfraDbDown" and .labels.category=="infrastructure") | .status.state) // ""')
  [ "$infra_state" = "active" ] && break
  sleep 5
done
[ "$infra_state" = "active" ] || { echo "InfraDbDown did NOT fire ❌ — check the Loki signature 'DataAccessResourceFailureException' and that the app runs under the chaos profile"; exit 1; }
echo "    InfraDbDown FIRING with category=infrastructure ✅"

echo "==> A13 discriminator: the Class A app-localization alert must NOT fire"
classa_state=$(curl -s "$GRAFANA/api/alertmanager/grafana/api/v2/alerts" \
  | jq -r 'first(.[] | select(.labels.alertname=="ClassAOwnerSearchNPE") | .status.state) // ""')
echo "    ClassAOwnerSearchNPE state = '${classa_state:-<absent>}' (expected absent/not active)"
[ "$classa_state" = "active" ] && { echo "FAIL: Class A alert fired for a DB-connectivity fault — the bench's signal is not discriminating"; exit 1; }

echo "MISDIAGNOSIS-TRAP DISCRIMINATOR HOLDS ✅"
echo "(A13: same 5xx-with-stack-trace surface as Class A, but the signature routes to infra."
echo " A triage skill that keys on '5xx + stack trace => localize => PR' would misdiagnose this;"
echo " the correct action is route-to-infra/DBA. The bench supplies the discriminating signal — whether the skill consumes it is the skill's own work.)"
