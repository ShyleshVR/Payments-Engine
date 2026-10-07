#!/bin/bash
# Creates the local overlay's secrets once (random DB passwords, admin client secret, Grafana
# password, JWT signing key) in k8s/overlays/local/secrets/, which is git-ignored.
# Never overwrites: Postgres creates its users from these on first start only, and tokens are
# signed with the key, so regenerating would break an existing cluster.
set -euo pipefail
cd "$(dirname "$0")/../k8s/overlays/local"
mkdir -p secrets

random() { openssl rand -hex 24; }

if [ ! -f secrets/db.env ]; then
  {
    echo "postgres-password=$(random)"
    for svc in payment ledger notification webhook merchant; do
      echo "$svc-password=$(random)"
    done
  } > secrets/db.env
  echo "created secrets/db.env"
fi

if [ ! -f secrets/auth.env ]; then
  {
    echo "admin-client-secret=$(random)"
    echo "grafana-admin-password=$(random)"
  } > secrets/auth.env
  echo "created secrets/auth.env"
fi

if [ ! -f secrets/signing-key.pem ]; then
  # PKCS#8 PEM, as merchant-service's PemFileJwkSource expects.
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out secrets/signing-key.pem 2>/dev/null
  echo "created secrets/signing-key.pem"
fi
