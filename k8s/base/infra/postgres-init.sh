#!/bin/bash
# One database and one owning user per service, so services stay isolated (database-per-service)
# while sharing a single Postgres instance. Idempotent: the image runs it on first start, and
# scripts/k8s-up.sh runs it again on every deploy, so a database added later (e.g. recon_db)
# is created in an existing cluster too.
set -euo pipefail

create() {
  local db=$1 user=$2 password=$3
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
       -v db="$db" -v usr="$user" -v pwd="$password" <<'SQL'
SELECT format('CREATE USER %I WITH PASSWORD %L', :'usr', :'pwd')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'usr') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'db', :'usr')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db') \gexec
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
SQL
}

create payflow         payflow           "$PAYMENT_DB_PASSWORD"
create ledger_db       ledger_user       "$LEDGER_DB_PASSWORD"
create notification_db notification_user "$NOTIFICATION_DB_PASSWORD"
create webhook_db      webhook_user      "$WEBHOOK_DB_PASSWORD"
create merchant_db     merchant_user     "$MERCHANT_DB_PASSWORD"
create processor_db    processor_user    "$PROCESSOR_DB_PASSWORD"
create recon_db        recon_user        "$RECON_DB_PASSWORD"
