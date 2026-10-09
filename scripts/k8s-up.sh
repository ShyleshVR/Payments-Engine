#!/bin/bash
# Builds the images and deploys PayFlow to the local Kubernetes cluster (Docker Desktop).
# Usage: scripts/k8s-up.sh            build all images, deploy, wait until everything is ready
#        SKIP_BUILD=1 scripts/k8s-up.sh   redeploy without rebuilding images
set -euo pipefail
cd "$(dirname "$0")/.."

NAMESPACE=payflow
# Slack alerts only when you have put a Slack incoming-webhook URL in this file.
OVERLAY=k8s/overlays/local
if [ -f k8s/overlays/local/secrets/slack-webhook-url ]; then
  OVERLAY=k8s/overlays/local-slack
fi
METRICS_SERVER_VERSION=v0.7.2

if ! kubectl cluster-info >/dev/null 2>&1; then
  echo "No Kubernetes cluster reachable. Enable it in Docker Desktop: Settings > Kubernetes > Enable Kubernetes," >&2
  echo "then: kubectl config use-context docker-desktop" >&2
  exit 1
fi
echo "Cluster context: $(kubectl config current-context)"

# The LoadBalancer services take localhost:80/3000/9090/16686; the docker-compose stack holds
# some of those ports, and a LoadBalancer whose port is taken silently stays unreachable.
if docker ps --format '{{.Names}}' | grep -qE '^payflow-(grafana|prometheus|jaeger)$'; then
  echo "The docker-compose observability containers are running and hold ports 3000/9090/16686." >&2
  echo "Stop them first: docker compose -f infrastructure/docker/docker-compose.yml stop" >&2
  exit 1
fi

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  bash scripts/build-images.sh
fi

bash scripts/generate-local-secrets.sh

# metrics-server feeds CPU usage to the HorizontalPodAutoscalers. Docker Desktop's kubelet uses
# a self-signed certificate, hence --kubelet-insecure-tls (local clusters only).
if ! kubectl -n kube-system get deployment metrics-server >/dev/null 2>&1; then
  echo "==> installing metrics-server $METRICS_SERVER_VERSION"
  kubectl apply -f "https://github.com/kubernetes-sigs/metrics-server/releases/download/$METRICS_SERVER_VERSION/components.yaml"
  kubectl -n kube-system patch deployment metrics-server --type=json \
    -p '[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
fi

already_deployed=$(kubectl -n "$NAMESPACE" get deployments -o name 2>/dev/null | grep -c . || true)

echo "==> applying $OVERLAY"
# Grafana's provisioning files are shared with docker-compose and live outside k8s/, which
# kustomize only reads with this load restrictor.
kubectl kustomize --load-restrictor LoadRestrictionsNone "$OVERLAY" | kubectl apply -f -

# Image tags stay ":local", so a rebuilt image is only picked up by new pods.
if [ "$already_deployed" -gt 0 ] && [ "${SKIP_BUILD:-0}" != "1" ]; then
  echo "==> rolling restart to pick up the rebuilt images"
  kubectl -n "$NAMESPACE" rollout restart deployment \
    api-gateway merchant-service payment-service ledger-service notification-service webhook-service processor-simulator     reconciliation-service
fi

echo "==> waiting for rollouts"
kubectl -n "$NAMESPACE" rollout status statefulset/postgres --timeout=300s
# The image runs init scripts only on an empty volume; re-run the (idempotent) script so
# databases added since the volume was created exist too.
# (MSYS_NO_PATHCONV: stops Git Bash on Windows from rewriting the container path; no-op elsewhere)
MSYS_NO_PATHCONV=1 kubectl -n "$NAMESPACE" exec postgres-0 -- bash /docker-entrypoint-initdb.d/postgres-init.sh
kubectl -n "$NAMESPACE" rollout status statefulset/kafka --timeout=300s
for d in redis jaeger prometheus grafana alertmanager alert-sink kafka-exporter merchant-service processor-simulator          payment-service ledger-service notification-service webhook-service reconciliation-service api-gateway; do
  kubectl -n "$NAMESPACE" rollout status "deployment/$d" --timeout=600s
done

cat <<EOF

PayFlow is up.
  API gateway  http://localhost             (e.g. POST http://localhost/oauth2/token)
  Grafana      http://localhost:3000        (user admin)
  Prometheus   http://localhost:9090
  Jaeger       http://localhost:16686
  Alertmanager http://localhost:9093        (notifications: kubectl -n $NAMESPACE logs deploy/alert-sink)

Generated credentials (admin client id: payflow-admin; Grafana user: admin):
  cat k8s/overlays/local/secrets/auth.env
EOF
