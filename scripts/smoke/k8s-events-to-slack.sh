#!/usr/bin/env bash
# W5d AC W5d-1..W5d-4 (class-E k8s-native events): run the chaos app as a k8s Deployment with an
# in-cluster Alloy shipping k8s events to the host Loki, then trigger three pure-infrastructure
# faults — OOMKilled (kernel kill), FailedScheduling (unschedulable pod), Evicted (Eviction API).
# Each produces a k8s event with NO application stack trace; the matching infra alert must fire.
# Requires: Docker Desktop Kubernetes (current context) + the observability stack up
# (docker compose -f observability/docker-compose.observability.yml up -d). E1 OOM signal path
# (kubelet event vs kube-state-metrics) is finalized at this live gate; see the alert rule's note.
set -euo pipefail

NS=petclinic-bench
GRAFANA=http://admin:admin@localhost:3000
K8S_DIR="$(cd "$(dirname "$0")/../../k8s" && pwd)"

loki_count() { # $1 = LogQL selector+filter; echoes the number of matching lines
  curl -s -G "$GRAFANA/api/datasources/proxy/uid/loki/loki/api/v1/query_range" \
    --data-urlencode "query=$1" --data-urlencode 'limit=20' \
    | jq -r '[.data.result[]?.values[]?] | length'
}

alert_state() { # $1 = alertname; echoes the alert state ("" if absent)
  curl -s "$GRAFANA/api/alertmanager/grafana/api/v2/alerts" \
    | jq -r --arg n "$1" 'first(.[] | select(.labels.alertname==$n) | .status.state) // ""'
}

wait_alert() { # $1 = alertname; polls up to 90s for state=active
  local s=""
  for _ in $(seq 1 18); do
    s=$(alert_state "$1"); [ "$s" = "active" ] && { echo "    $1 FIRING ✅"; return 0; }
    sleep 5
  done
  echo "    $1 did NOT fire ❌ (state='$s')"; return 1
}

echo "==> building the app image (bootBuildImage → shared Docker Desktop image store)"
./gradlew bootBuildImage --imageName=spring-petclinic:w5d

echo "==> applying namespace, app, in-cluster Alloy"
kubectl apply -f "$K8S_DIR/namespace.yaml" -f "$K8S_DIR/petclinic-deployment.yaml" -f "$K8S_DIR/alloy-events.yaml"
kubectl -n "$NS" rollout status deploy/petclinic --timeout=120s
kubectl -n "$NS" rollout status deploy/alloy-events --timeout=120s

echo "==> port-forwarding the app (8080) for chaos arming"
kubectl -n "$NS" port-forward svc/petclinic 8080:8080 >/tmp/w5d-pf.log 2>&1 &
PF_PID=$!
trap 'kill $PF_PID 2>/dev/null || true' EXIT
for _ in $(seq 1 20); do curl -fsS http://localhost:8080/chaos/status >/dev/null 2>&1 && break; sleep 1; done

# ---------- E2: FailedScheduling (no app involved) ----------
echo "==> E2: applying the unschedulable pod (expect FailedScheduling)"
kubectl apply -f "$K8S_DIR/failed-scheduling-pod.yaml"
echo "    waiting for the FailedScheduling event to reach Loki"
n=0; for _ in $(seq 1 18); do n=$(loki_count '{job="k8s-events"} |= `FailedScheduling`'); [ "${n:-0}" -ge 1 ] && break; sleep 5; done
awk "BEGIN{exit !(${n:-0}+0 >= 1)}" || { echo "FAIL: no FailedScheduling event in Loki"; exit 1; }
echo "    FailedScheduling event in Loki = $n ✅"
wait_alert KubeFailedScheduling

# ---------- E3: Evicted (Eviction API on the app pod) ----------
echo "==> E3: evicting the app pod via the Eviction API (expect Evicted)"
POD=$(kubectl -n "$NS" get pod -l app=petclinic -o jsonpath='{.items[0].metadata.name}')
kubectl -n "$NS" create -f - <<EOF
apiVersion: policy/v1
kind: Eviction
metadata:
  name: $POD
  namespace: $NS
EOF
n=0; for _ in $(seq 1 18); do n=$(loki_count '{job="k8s-events"} |= `Evicted`'); [ "${n:-0}" -ge 1 ] && break; sleep 5; done
awk "BEGIN{exit !(${n:-0}+0 >= 1)}" || { echo "FAIL: no Evicted event in Loki"; exit 1; }
echo "    Evicted event in Loki = $n ✅"
wait_alert KubePodEvicted
kubectl -n "$NS" rollout status deploy/petclinic --timeout=120s  # let the Deployment recreate the pod

echo "==> re-establishing port-forward to the new pod"
kill $PF_PID 2>/dev/null || true
kubectl -n "$NS" port-forward svc/petclinic 8080:8080 >/tmp/w5d-pf.log 2>&1 &
PF_PID=$!
for _ in $(seq 1 20); do curl -fsS http://localhost:8080/chaos/status >/dev/null 2>&1 && break; sleep 1; done

# ---------- E1: OOMKilled (kernel kill of the app container) ----------
echo "==> E1: arming oomKill and exhausting memory (expect a kernel OOMKill)"
curl -s -XPOST http://localhost:8080/chaos/oomKill/arm | jq .
# The endpoint allocates until the kernel SIGKILLs the container; curl drops the connection.
curl -s -XPOST --max-time 60 http://localhost:8080/chaos/oom-kill >/dev/null 2>&1 || true
echo "    waiting for the container to report OOMKilled (ground truth: app-blind SIGKILL)"
reason=""
for _ in $(seq 1 24); do
  reason=$(kubectl -n "$NS" get pod -l app=petclinic \
    -o jsonpath='{.items[0].status.containerStatuses[0].lastState.terminated.reason}' 2>/dev/null || true)
  [ "$reason" = "OOMKilled" ] && break
  sleep 5
done
[ "$reason" = "OOMKilled" ] || { echo "FAIL: container did not report OOMKilled (reason='$reason')"; exit 1; }
echo "    container lastState.reason = OOMKilled ✅ (no OutOfMemoryError, no app stack trace)"
wait_alert KubeOOMKilled

echo
echo "K8S-NATIVE EVENT AUGMENTATION HOLDS ✅"
echo "(W5d: each fault is a pure infrastructure event with NO application stack trace —"
echo " OOMKilled / FailedScheduling / Evicted. The k8s event stream localizes the cause to"
echo " infrastructure (category=infrastructure), the signal the skill is blind to without it.)"
echo "(cleanup: kubectl delete ns $NS)"
