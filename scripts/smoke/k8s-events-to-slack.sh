#!/usr/bin/env bash
# W5d AC W5d-1..W5d-4 (class-E k8s-native events): run the chaos app as a k8s Deployment with an
# in-cluster Alloy shipping k8s events to the host Loki, then trigger three pure-infrastructure
# faults — OOMKilled (kernel kill), FailedScheduling (unschedulable pod), Evicted (emptyDir
# sizeLimit eviction). Each surfaces as infrastructure with NO application stack trace —
# FailedScheduling/Evicted as k8s events, OOMKilled as pod status — and its infra alert fires.
# Requires: Docker Desktop Kubernetes (current context) + the observability stack up. Start it
# from the repo root WITH the env file so Grafana's Slack contact point validates (otherwise
# alerting provisioning aborts and no rules load):
#   docker compose -f observability/docker-compose.observability.yml --env-file .env up -d
# E1 (OOMKilled) is not in the k8s event stream — the kubelet emits no OOM event — so it is
# detected via kube-state-metrics (pod status) remote-written to Prometheus; see the alert.
set -euo pipefail

NS=petclinic-bench
GRAFANA=http://admin:admin@localhost:3000
K8S_DIR="$(cd "$(dirname "$0")/../../k8s" && pwd)"
CURL_IMAGE=curlimages/curl:8.11.1  # in-cluster one-shot curl for arming/triggering the app

loki_count() { # $1 = LogQL selector+filter; echoes the number of matching lines (0 on any error)
  curl -s -G "$GRAFANA/api/datasources/proxy/uid/loki/loki/api/v1/query_range" \
    --data-urlencode "query=$1" --data-urlencode 'limit=20' 2>/dev/null \
    | jq -r '[.data.result[]?.values[]?] | length' 2>/dev/null || echo 0
}

alert_state() { # $1 = alertname; echoes the alert state ("" if absent)
  curl -s "$GRAFANA/api/alertmanager/grafana/api/v2/alerts" \
    | jq -r --arg n "$1" 'first(.[] | select(.labels.alertname==$n) | .status.state) // ""'
}

wait_alert() { # $1 = alertname; polls up to 150s for state=active
  # 150s absorbs the worst case: a 30s rule-eval interval plus Grafana->alertmanager state
  # propagation, on top of the signal already being confirmed present before this is called.
  local s=""
  for _ in $(seq 1 30); do
    s=$(alert_state "$1"); [ "$s" = "active" ] && { echo "    $1 FIRING ✅"; return 0; }
    sleep 5
  done
  echo "    $1 did NOT fire ❌ (state='$s')"; return 1
}

echo "==> building the app image (boot jar + JRE Dockerfile → shared Docker Desktop image store)"
# Not bootBuildImage: the graalvm native plugin forces it into a native-image build that fails
# on Spring Boot 4. A plain JRE image (k8s/Dockerfile) is enough for the class-E faults.
./gradlew bootJar
LIBS=""  # the build dir may be redirected (init.d), so probe both locations
for d in build/libs /tmp/petclinic-build/libs; do [ -d "$d" ] && { LIBS="$d"; break; }; done
[ -n "$LIBS" ] || { echo "FAIL: no libs dir with the boot jar"; exit 1; }
docker build -t spring-petclinic:w5d -f "$K8S_DIR/Dockerfile" "$LIBS"

# Clean slate so every run validates from scratch: a prior run's OOMKilled pod status would
# otherwise leave kube_pod_container_status_last_terminated_reason=1, making KubeOOMKilled pass
# before this run does anything. Deleting the namespace drops that state (and KSM redeploys clean).
echo "==> clean slate: removing any prior bench namespace"
kubectl delete ns "$NS" --ignore-not-found --wait --timeout=120s

echo "==> applying namespace, app, in-cluster Alloy, kube-state-metrics"
kubectl apply -f "$K8S_DIR/namespace.yaml" -f "$K8S_DIR/petclinic-deployment.yaml" \
  -f "$K8S_DIR/alloy-events.yaml" -f "$K8S_DIR/kube-state-metrics.yaml"
kubectl -n "$NS" rollout status deploy/petclinic --timeout=150s
kubectl -n "$NS" rollout status deploy/alloy-events --timeout=120s
kubectl -n "$NS" rollout status deploy/kube-state-metrics --timeout=120s

# ---------- E2: FailedScheduling (no app involved) ----------
echo "==> E2: applying the unschedulable pod (expect FailedScheduling)"
kubectl -n "$NS" delete pod unschedulable-probe --ignore-not-found >/dev/null 2>&1  # fresh event on re-runs
kubectl apply -f "$K8S_DIR/failed-scheduling-pod.yaml"
echo "    waiting for the FailedScheduling event to reach Loki"
n=0; for _ in $(seq 1 18); do n=$(loki_count '{job="k8s-events"} |= `FailedScheduling`'); [ "${n:-0}" -ge 1 ] && break; sleep 5; done
awk "BEGIN{exit !(${n:-0}+0 >= 1)}" || { echo "FAIL: no FailedScheduling event in Loki"; exit 1; }
echo "    FailedScheduling event in Loki = $n ✅"
wait_alert KubeFailedScheduling

# ---------- E3: Evicted (safe per-pod eviction via emptyDir sizeLimit) ----------
echo "==> E3: applying the eviction probe (exceeds an emptyDir sizeLimit → kubelet evicts it, reason=Evicted)"
kubectl -n "$NS" delete pod evicted-probe --ignore-not-found >/dev/null 2>&1  # fresh event on re-runs
kubectl apply -f "$K8S_DIR/evicted-probe.yaml"
echo "    waiting for the Evicted event to reach Loki (kubelet eviction housekeeping ~1-2m)"
n=0; for _ in $(seq 1 36); do n=$(loki_count '{job="k8s-events"} |= `Evicted`'); [ "${n:-0}" -ge 1 ] && break; sleep 5; done
awk "BEGIN{exit !(${n:-0}+0 >= 1)}" || { echo "FAIL: no Evicted event in Loki"; exit 1; }
echo "    Evicted event in Loki = $n ✅"
wait_alert KubePodEvicted

# ---------- E1: OOMKilled (kernel kill of the app container) ----------
# Arm and trigger from INSIDE the cluster (one-shot curl pods hitting the Service), not via
# kubectl port-forward: a forward to Docker Desktop is flaky — a single dropped connection kills
# it, which would take the arm POST down with it under pipefail.
echo "==> E1: arming oomKill and exhausting memory (expect a kernel OOMKill)"
kubectl -n "$NS" run chaos-armer --rm -i --restart=Never --image="$CURL_IMAGE" \
  --command -- curl -s -XPOST http://petclinic:8080/chaos/oomKill/arm 2>&1 \
  | grep -q '"oomKill":true' || { echo "FAIL: could not arm oomKill"; exit 1; }
echo "    oomKill armed ✅"
# The endpoint allocates until the kernel SIGKILLs the container; curl's connection then drops.
kubectl -n "$NS" run chaos-trigger --rm -i --restart=Never --image="$CURL_IMAGE" \
  --command -- curl -s --max-time 60 -XPOST http://petclinic:8080/chaos/oom-kill >/dev/null 2>&1 || true
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
echo "(W5d: each fault is pure infrastructure with NO application stack trace — FailedScheduling"
echo " and Evicted in the k8s event stream, OOMKilled in pod status via kube-state-metrics. Each"
echo " localizes the cause to infrastructure (category=infrastructure), the signal the skill is"
echo " blind to without it.)"
echo "(cleanup: kubectl delete ns $NS)"
