#!/bin/bash
# One k6 load-test run inside the cluster, then the server side of the same window.
# Usage: scripts/load-test.sh <smoke|step|soak|spike> [rate] [duration, or peak for spike]
#   scripts/load-test.sh smoke
#   scripts/load-test.sh step 100 3m
#   scripts/load-test.sh soak 140 30m
#   scripts/load-test.sh spike 60 400
# Prints PASS/FAIL against the SLOs (docs/LOAD_TEST.md) and writes load-tests/results/<run>.json.
set -euo pipefail
cd "$(dirname "$0")/.."

SCENARIO=${1:-smoke}
RATE=${2:-5}
EXTRA=${3:-}
DURATION=3m
PEAK=$((RATE * 2))
case "$SCENARIO" in
  smoke) DURATION=1m ;;
  step|soak) DURATION=${EXTRA:-$([ "$SCENARIO" = soak ] && echo 30m || echo 3m)} ;;
  spike) PEAK=${EXTRA:-$((RATE * 3))}; DURATION=spike ;;
  *) echo "unknown scenario $SCENARIO" >&2; exit 2 ;;
esac
MERCHANTS=${MERCHANTS:-50}
NAMESPACE=payflow
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$SCENARIO-$RATE"
mkdir -p load-tests/results
LOG="load-tests/results/$RUN_ID.log"

# The operator secret for creating test merchants, from the generated local secrets.
ADMIN_SECRET=$(grep '^admin-client-secret=' k8s/overlays/local/secrets/auth.env | cut -d= -f2-)
kubectl -n "$NAMESPACE" create secret generic k6-admin --from-literal=admin-client-secret="$ADMIN_SECRET" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n "$NAMESPACE" create configmap k6-script --from-file=payflow.js=load-tests/k6/payflow.js \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

kubectl -n "$NAMESPACE" delete job k6-load --ignore-not-found --wait=true >/dev/null
sed -e "s/__SCENARIO__/$SCENARIO/" -e "s/__RATE__/$RATE/" -e "s/__PEAK__/$PEAK/" -e "s/__DURATION__/$DURATION/" \
    -e "s/__MERCHANTS__/$MERCHANTS/" -e "s/__RUN_ID__/$RUN_ID/" k8s/load/k6-job.yaml | kubectl apply -f - >/dev/null
echo "==> $RUN_ID: $SCENARIO at $RATE payments/s ($([ "$SCENARIO" = spike ] && echo "peak $PEAK" || echo "$DURATION"))"

kubectl -n "$NAMESPACE" wait --for=condition=Ready pod -l app=k6-load --timeout=180s >/dev/null
START=$(date +%s)
# k6 exits non-zero when a threshold fails, so the Job ends Complete or Failed.
until [ "$(kubectl -n "$NAMESPACE" get job k6-load -o jsonpath='{.status.succeeded}{.status.failed}')" != "" ]; do
  sleep 5
done
END=$(date +%s)
kubectl -n "$NAMESPACE" logs job/k6-load > "$LOG" 2>&1 || true

python load-tests/collect.py "$LOG" "$START" "$END" "load-tests/results/$RUN_ID.json"
