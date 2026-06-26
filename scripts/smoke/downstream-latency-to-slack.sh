#!/usr/bin/env bash
# AC W5a-1 + W5a-2 (distributed tracing): arm downstreamLatency so owner-details makes a slow
# downstream call to the httpbin stub. The metric alert says "slow" but not WHERE; the trace in
# Tempo localizes the ~2s span to the downstream /delay call (vs the fast JDBC span). Requires the
# app under the chaos profile and the observability stack (incl. tempo + httpbin-stub) up.
set -euo pipefail

APP=http://localhost:8080
GRAFANA=http://admin:admin@localhost:3000
TEMPO=http://localhost:3200

echo "==> confirming app, stub, and tempo are reachable"
acode=$(curl -s -o /dev/null -w '%{http_code}' "$APP/chaos/status"); echo "    app /chaos/status -> $acode"
[ "$acode" = "200" ] || { echo "app not reachable (chaos profile) at 8080"; exit 1; }
scode=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8088/delay/0); echo "    httpbin-stub -> $scode"
[ "$scode" = "200" ] || { echo "stub not reachable; bring up httpbin-stub"; exit 1; }
curl -sf "$TEMPO/ready" >/dev/null || { echo "Tempo not reachable at 3200; bring up tempo"; exit 1; }
echo "    tempo /ready -> ok"

echo "==> arming downstreamLatency"
curl -s -XPOST "$APP/chaos/downstreamLatency/arm" | jq .

echo "==> W5a-1: a slow owner-details request (downstream /delay/2)"
t=$(curl -s -o /dev/null -w '%{time_total}' "$APP/owners/1")
echo "    /owners/1 took ${t}s (expected ~2s)"
awk "BEGIN{exit !($t+0 >= 1.5)}" || { echo "FAIL: request was not slow ($t s) — downstream call not firing"; exit 1; }

echo "==> W5a-1: waiting up to 90s for DownstreamLatency to fire"
# keep generating slow requests for the whole poll window so the p99 stays elevated
( for i in $(seq 1 60); do curl -s -o /dev/null "$APP/owners/1" || true; done ) &
LOAD_PID=$!
state=""
for i in $(seq 1 18); do
  state=$(curl -s "$GRAFANA/api/alertmanager/grafana/api/v2/alerts" \
    | jq -r 'first(.[] | select(.labels.alertname=="DownstreamLatency") | .status.state) // ""')
  [ "$state" = "active" ] && break
  sleep 5
done
kill $LOAD_PID 2>/dev/null || true
[ "$state" = "active" ] || { echo "DownstreamLatency did NOT fire ❌ — check /owners/{ownerId} p99 in Prometheus"; exit 1; }
echo "    DownstreamLatency FIRING ✅"

echo "==> W5a-2: the trace localizes the slow span to the downstream /delay call"
# Tempo TraceQL search: a span named for the downstream HTTP GET with duration > 1s.
# (Exact query finalized at the live gate; this searches recent traces for a slow client span.)
sleep 8  # allow trace ingest
found=$(curl -s "$TEMPO/api/search" --data-urlencode 'q={ span.http.url =~ ".*/delay/.*" && duration > 1s }' \
  | jq -r '.traces | length')
echo "    Tempo traces with a >1s downstream /delay span = ${found:-0} (expected >= 1)"
awk "BEGIN{exit !(${found:-0}+0 >= 1)}" || { echo "FAIL: no trace localized the slow downstream span"; exit 1; }

echo "DISTRIBUTED-TRACE AUGMENTATION HOLDS ✅"
echo "(W5a: the latency alert says '/owners/{ownerId} is slow' with no cause; the trace names the"
echo " downstream /delay span as the ~2s culprit (the JDBC span is fast). The bench supplies the"
echo " localizing signal — whether the skill consumes the trace is the skill's own work.)"
echo "(disarm: curl -s -XPOST $APP/chaos/downstreamLatency/disarm)"
