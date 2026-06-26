#!/usr/bin/env bash
# AC W5b-1 + W5b-2 (query-plan pathology): arm queryPlanRegression so the owner search uses a
# leading-wildcard LIKE '%term%' that Seq-Scans the 100k-row owners table. The latency metric only
# says "slow"; the EXPLAIN plan (logged to Loki) localizes the cause as a Seq Scan. Requires the app
# on Postgres (SPRING_PROFILES_ACTIVE=chaos,postgres, 100k seeded) and the observability stack up.
set -euo pipefail

APP=http://localhost:8080
GRAFANA=http://admin:admin@localhost:3000

echo "==> confirming the app is on Postgres + seeded (large owners table)"
acode=$(curl -s -o /dev/null -w '%{http_code}' "$APP/chaos/status"); echo "    app /chaos/status -> $acode"
[ "$acode" = "200" ] || { echo "app not reachable (chaos profile)"; exit 1; }

echo "==> arming queryPlanRegression"
curl -s -XPOST "$APP/chaos/queryPlanRegression/arm" | jq .

echo "==> W5b-1: the regressed owner search is slow (Seq Scan over 100k rows)"
t=$(curl -s -o /dev/null -w '%{time_total}' "$APP/owners?lastName=Davis")
echo "    /owners?lastName=Davis took ${t}s"

echo "==> W5b-1: waiting up to 60s for QueryPlanRegression to fire (seq-scan gauge)"
state=""
for i in $(seq 1 12); do
  state=$(curl -s "$GRAFANA/api/alertmanager/grafana/api/v2/alerts" \
    | jq -r 'first(.[] | select(.labels.alertname=="QueryPlanRegression") | .status.state) // ""')
  [ "$state" = "active" ] && break
  sleep 5
done
[ "$state" = "active" ] || { echo "QueryPlanRegression did NOT fire ❌ — check petclinic_query_seqscan in Prometheus"; exit 1; }
echo "    QueryPlanRegression FIRING ✅"

echo "==> W5b-2: the EXPLAIN plan localizing the Seq Scan is observable in Loki"
plan=$(curl -s -G "$GRAFANA/api/datasources/proxy/uid/loki/loki/api/v1/query_range" \
  --data-urlencode 'query={service="petclinic"} |= "Seq Scan on owners"' \
  --data-urlencode 'limit=5' | jq -r '[.data.result[]?.values[]?] | length')
echo "    Loki log lines with 'Seq Scan on owners' = ${plan:-0} (expected >= 1)"
awk "BEGIN{exit !(${plan:-0}+0 >= 1)}" || { echo "FAIL: the EXPLAIN plan did not reach Loki (plan lines=${plan:-0})"; exit 1; }

echo "QUERY-PLAN AUGMENTATION HOLDS ✅"
echo "(W5b: the latency metric says '/owners search is slow' with no cause; the EXPLAIN plan names"
echo " the Seq Scan on owners (a leading-wildcard LIKE can't use the last_name index). The bench"
echo " supplies the localizing signal — whether the skill consumes the EXPLAIN is the skill's own work.)"
echo "(disarm: curl -s -XPOST $APP/chaos/queryPlanRegression/disarm)"
