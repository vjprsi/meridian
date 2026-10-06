#!/usr/bin/env bash
# Creates EMPTY service databases in the local Postgres so each service's Flyway can migrate it on startup.
# (apply-ddl.sh instead loads the DDL directly, for verification.)  Run from WSL:
#   bash tools/db/reset-dbs.sh                 # all databases from generated/services.json
#   bash tools/db/reset-dbs.sh ledger_db gl_db # selected
set -euo pipefail
cd "$(dirname "$0")/../.."
PG=meridian-postgres
psql_admin() { docker exec -i "$PG" psql -U meridian -d meridian -v ON_ERROR_STOP=1 -q "$@"; }

psql_admin -c "DO \$\$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='meridian_app') THEN
  CREATE ROLE meridian_app LOGIN PASSWORD 'meridian_app' NOSUPERUSER NOBYPASSRLS; END IF; END \$\$;"

dbs=("$@")
if [ ${#dbs[@]} -eq 0 ]; then
  dbs=($(grep -oE '"database": "[a-z0-9_]+"' generated/services.json | cut -d'"' -f4 | sort -u))
fi
for db in "${dbs[@]}"; do
  psql_admin -c "DROP DATABASE IF EXISTS $db WITH (FORCE);" -c "CREATE DATABASE $db;" 2>/dev/null
  echo "empty database $db"
done
