#!/bin/bash
# Builds every service jar and its Docker image, tagged payflow/<service>:local.
# Usage: scripts/build-images.sh [service ...]   (default: all services)
set -euo pipefail
cd "$(dirname "$0")/.."

SERVICES=("$@")
if [ ${#SERVICES[@]} -eq 0 ]; then
  SERVICES=(payment-service notification-service ledger-service webhook-service merchant-service api-gateway)
fi

for svc in "${SERVICES[@]}"; do
  echo "==> $svc"
  (cd "$svc" && mvn -q -B clean package -DskipTests)
  docker build -q -t "payflow/$svc:local" "$svc"
done
