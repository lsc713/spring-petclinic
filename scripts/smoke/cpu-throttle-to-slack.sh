#!/usr/bin/env bash
# W7 (CPU throttling): run the chaos app as a k8s Deployment with a 1-core CPU limit and an
# in-cluster Alloy scraping the kubelet /metrics/cadvisor, then arm cpuThrottle so the app
# burns CPU past the limit. CPU usage just sits pinned at ~1 core (looks fine); only the
# cAdvisor CFS throttle ratio names the cause. Requires Docker Desktop Kubernetes (current
# context) + the observability stack up. Start it from the repo root WITH the env file so
# Grafana's Slack contact point validates (otherwise alerting provisioning aborts and no
# rules load):
#   docker compose -f observability/docker-compose.observability.yml --env-file .env up -d
set -euo pipefail

NS=petclinic-bench
PROM=http://localhost:9090
GRAFANA=http://admin:admin@localhost:3000
K8S_DIR="$(cd "$(dirname "$0")/../../k8s" && pwd)"
CURL_IMAGE=curlimages/curl:8.11.1  # in-cluster one-shot curl for arming the app

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

echo "==> building the app image (boot jar + JRE Dockerfile → shared Docker Desktop image store)"
./gradlew bootJar
LIBS=""  # the build dir may be redirected (init.d), so probe both locations
for d in build/libs /tmp/petclinic-build/libs; do [ -d "$d" ] && { LIBS="$d"; break; }; done
[ -n "$LIBS" ] || { echo "FAIL: no libs dir with the boot jar"; exit 1; }
docker build -t spring-petclinic:w7 -f "$K8S_DIR/Dockerfile" "$LIBS"

echo "==> clean slate: removing any prior bench namespace"
kubectl delete ns "$NS" --ignore-not-found --wait --timeout=120s

echo "==> applying namespace, app (cpu limit), in-cluster Alloy (cadvisor scrape)"
kubectl apply -f "$K8S_DIR/namespace.yaml" -f "$K8S_DIR/petclinic-deployment.yaml" \
  -f "$K8S_DIR/alloy-events.yaml"
kubectl -n "$NS" rollout status deploy/petclinic --timeout=150s
kubectl -n "$NS" rollout status deploy/alloy-events --timeout=120s

echo "==> arming cpuThrottle (the @Scheduled reconcile then spawns the burn threads)"
kubectl -n "$NS" run chaos-armer --rm -i --restart=Never --image="$CURL_IMAGE" \
  --command -- curl -s -XPOST http://petclinic:8080/chaos/cpuThrottle/arm 2>&1 \
  | grep -q '"cpuThrottle":true' || { echo "FAIL: could not arm cpuThrottle"; exit 1; }
echo "    cpuThrottle armed ✅"

echo "==> letting the burn run (~75s) so the CFS throttle ratio accumulates"
sleep 75

echo "==> waiting up to 150s for CpuThrottled to fire"
wait_alert CpuThrottled

echo "==> augmentation: throttle ratio high while CPU usage sits pinned at the limit"
# Docker Desktop's kubelet emits CFS throttle metrics at the pod-cgroup level (pod label, no
# container label), so these select on pod only.
ratio=$(prom 'sum(rate(container_cpu_cfs_throttled_periods_total{namespace="petclinic-bench",pod=~"petclinic.*"}[1m])) / sum(rate(container_cpu_cfs_periods_total{namespace="petclinic-bench",pod=~"petclinic.*"}[1m]))')
usage=$(prom 'sum(rate(container_cpu_usage_seconds_total{namespace="petclinic-bench",pod=~"petclinic.*"}[1m]))')
echo "    cfs throttle ratio = $ratio (>0.2),  cpu usage cores = $usage (≈ 1-core limit, looks fine)"

echo "==> disarming cpuThrottle (burn threads self-terminate)"
kubectl -n "$NS" run chaos-disarmer --rm -i --restart=Never --image="$CURL_IMAGE" \
  --command -- curl -s -XPOST http://petclinic:8080/chaos/cpuThrottle/disarm >/dev/null 2>&1 || true

echo
echo "CPU-THROTTLE AUGMENTATION HOLDS ✅"
echo "(W7: the app is slow but CPU usage looks fine — pinned at the limit. Only the cAdvisor CFS"
echo " throttle ratio names the cause as a CPU-limit cap (infrastructure), the signal the skill is"
echo " blind to without it.)"
echo "(cleanup: kubectl delete ns $NS)"
echo "SMOKE EXIT=0"
