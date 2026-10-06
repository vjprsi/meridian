#!/usr/bin/env bash
# Recreates every service database in the local Postgres and applies generated/db/<db>/V001__init.sql.
# Run from WSL (or any host with docker):  bash tools/db/apply-ddl.sh [database ...]
set -euo pipefail
cd "$(dirname "$0")/../.."
PG=meridian-postgres
psql_admin() { docker exec -i "$PG" psql -U meridian -d meridian -v ON_ERROR_STOP=1 -q "$@"; }

psql_admin -c "DO \$\$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='meridian_app') THEN
  CREATE ROLE meridian_app LOGIN PASSWORD 'meridian_app' NOSUPERUSER NOBYPASSRLS; END IF; END \$\$;"

dbs=("$@")
if [ ${#dbs[@]} -eq 0 ]; then dbs=($(ls generated/db)); fi
ok=0; fail=0
for db in "${dbs[@]}"; do
  f="generated/db/$db/V001__init.sql"
  psql_admin -c "DROP DATABASE IF EXISTS $db WITH (FORCE);" -c "CREATE DATABASE $db;"
  if out=$(docker exec -i "$PG" psql -U meridian -d "$db" -v ON_ERROR_STOP=1 -q < "$f" 2>&1); then
    tables=$(docker exec -i "$PG" psql -U meridian -d "$db" -tAc "select count(*) from information_schema.tables where table_schema='${db%_db}'")
    printf "OK    %-22s %3s tables\n" "$db" "$tables"; ok=$((ok+1))
  else
    printf "FAIL  %-22s %s\n" "$db" "$(echo "$out" | grep -m1 ERROR)"; fail=$((fail+1))
  fi
done
echo "databases ok=$ok failed=$fail"
[ "$fail" -eq 0 ]
