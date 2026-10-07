#!/bin/bash
# Removes PayFlow from the local cluster by deleting its namespace, including the Postgres and
# Kafka volumes (all data). The generated secrets in k8s/overlays/local/secrets/ are kept, so
# the next k8s-up.sh starts fresh databases with the same credentials and signing key.
set -euo pipefail

kubectl delete namespace payflow --ignore-not-found --wait=true
echo "payflow namespace deleted (metrics-server in kube-system is left installed)."
