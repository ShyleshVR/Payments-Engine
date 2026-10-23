#!/bin/bash
# Creates the local overlay's secrets (random DB passwords, admin client secret, Grafana password,
# processor API key, service client secrets, JWT signing key) in k8s/overlays/local/secrets/,
# which is git-ignored. An optional secrets/slack-webhook-url (written by you) enables Slack alerts.
# With --agent, also secrets/incident-agent-token, which sends every alert to the incident agent
# (scripts/k8s-up.sh then deploys the local-agent overlay; the agent copies the token into its .env).
# Never changes an existing value: Postgres users are created with these passwords and tokens are
# signed with the key, so regenerating would break a running cluster. Keys added in later
# versions are appended to existing files.
set -euo pipefail
cd "$(dirname "$0")/../k8s/overlays/local"
mkdir -p secrets
touch secrets/db.env secrets/auth.env

random() { openssl rand -hex 24; }

# ensure <file> <key>: append key=<random> unless the key is already there
ensure() {
  if ! grep -q "^$2=" "$1"; then
    echo "$2=$(random)" >> "$1"
    echo "added $2 to $1"
  fi
}

ensure secrets/db.env postgres-password
for svc in payment ledger notification webhook merchant processor recon payout; do
  ensure secrets/db.env "$svc-password"
done

ensure secrets/auth.env admin-client-secret
ensure secrets/auth.env grafana-admin-password
ensure secrets/auth.env processor-api-key
ensure secrets/auth.env reconciliation-client-secret
ensure secrets/auth.env payout-client-secret
ensure secrets/auth.env incident-agent-client-secret
ensure secrets/auth.env incident-agent-operator-client-secret

if [ "${1:-}" = "--agent" ] && [ ! -f secrets/incident-agent-token ]; then
  # one line, no newline: Alertmanager sends the file's content as the bearer token
  random | tr -d '\r\n' > secrets/incident-agent-token
  echo "created secrets/incident-agent-token"
fi

if [ ! -f secrets/signing-key.pem ]; then
  # PKCS#8 PEM, as merchant-service's PemFileJwkSource expects.
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out secrets/signing-key.pem 2>/dev/null
  echo "created secrets/signing-key.pem"
fi
