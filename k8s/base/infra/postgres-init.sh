#!/bin/bash
# Runs once, on an empty data directory: one database and one owning user per service, so
# services stay isolated (database-per-service) while sharing a single Postgres instance.
set -euo pipefail

create() {
  local db=$1 user=$2 password=$3
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
       -v db="$db" -v usr="$user" -v pwd="$password" <<'SQL'
CREATE USER :"usr" WITH PASSWORD :'pwd';
CREATE DATABASE :"db" OWNER :"usr";
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
SQL
}

create payflow         payflow           "$PAYMENT_DB_PASSWORD"
create ledger_db       ledger_user       "$LEDGER_DB_PASSWORD"
create notification_db notification_user "$NOTIFICATION_DB_PASSWORD"
create webhook_db      webhook_user      "$WEBHOOK_DB_PASSWORD"
create merchant_db     merchant_user     "$MERCHANT_DB_PASSWORD"
