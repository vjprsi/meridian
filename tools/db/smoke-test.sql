-- Behaviour smoke test for generated DDL. Run as the application role against ledger_db:
--   docker exec -i meridian-postgres psql "postgresql://meridian_app:meridian_app@localhost/ledger_db" -v ON_ERROR_STOP=1 -f - < tools/db/smoke-test.sql
SET search_path TO ledger;
\set tenant_a '''0199a000-0000-7000-8000-00000000000a'''
\set tenant_b '''0199a000-0000-7000-8000-00000000000b'''

-- 1. Tenant A writes a ledger, two accounts and a balanced journal entry (with custom fields)
SET app.tenant_id = :tenant_a;
INSERT INTO ledger (tenant_id, code, name, legal_entity_id, base_currency, custom_field_1)
VALUES (:tenant_a, 'MAIN-USD', 'Main USD book', gen_random_uuid(), 'USD', 'cost-centre-42') RETURNING id AS ledger_id \gset
INSERT INTO ledger_account (tenant_id, ledger_id, code, name, account_type, normal_balance, currency)
VALUES (:tenant_a, :'ledger_id', '1000', 'Cash', 'ASSET', 'DEBIT', 'USD') RETURNING id AS cash_id \gset
INSERT INTO ledger_account (tenant_id, ledger_id, code, name, account_type, normal_balance, currency)
VALUES (:tenant_a, :'ledger_id', '2000', 'Customer deposits', 'LIABILITY', 'CREDIT', 'USD') RETURNING id AS dep_id \gset
INSERT INTO journal_entry (tenant_id, ledger_id, idempotency_key, effective_at, value_date, source_service, entry_count, total_debits)
VALUES (:tenant_a, :'ledger_id', 'idem-1', now(), current_date, 'smoke-test', 2, 100.00) RETURNING id AS je_id \gset
INSERT INTO ledger_entry (tenant_id, journal_entry_id, ledger_account_id, line_no, direction, amount, currency, base_amount, effective_at, account_version, balance_after)
VALUES (:tenant_a, :'je_id', :'cash_id', 1, 'DEBIT', 100.00, 'USD', 100.00, now(), 1, 100.00),
       (:tenant_a, :'je_id', :'dep_id', 2, 'CREDIT', 100.00, 'USD', 100.00, now(), 1, 100.00);
SELECT 'PASS 1 tenant A wrote ' || count(*) || ' ledger entries' FROM ledger_entry;

-- 2. Row-level security: tenant B sees nothing of tenant A
SET app.tenant_id = :tenant_b;
SELECT CASE WHEN count(*) = 0 THEN 'PASS 2 RLS hides tenant A rows from tenant B'
            ELSE 'FAIL 2 RLS leak: ' || count(*) END FROM ledger_entry;

-- 3. Append-only: updating or deleting a posted journal entry is rejected
SET app.tenant_id = :tenant_a;
DO $$ BEGIN
  UPDATE ledger.journal_entry SET description = 'tampered';
  RAISE EXCEPTION 'FAIL 3 append-only table accepted an UPDATE';
EXCEPTION WHEN object_not_in_prerequisite_state THEN
  RAISE NOTICE 'PASS 3 journal_entry UPDATE blocked (append-only)';
END $$;
DO $$ BEGIN
  DELETE FROM ledger.ledger_entry;
  RAISE EXCEPTION 'FAIL 3b append-only table accepted a DELETE';
EXCEPTION WHEN object_not_in_prerequisite_state THEN
  RAISE NOTICE 'PASS 3b ledger_entry DELETE blocked (append-only)';
END $$;

-- 4. Optimistic locking: an update bumps row_version and updated_at
UPDATE ledger SET name = 'Main USD book (renamed)', custom_field_2 = 'region-emea' WHERE code = 'MAIN-USD';
SELECT CASE WHEN row_version = 2 THEN 'PASS 4 row_version incremented to 2'
            ELSE 'FAIL 4 row_version=' || row_version END FROM ledger WHERE code = 'MAIN-USD';

-- 5. Enum CHECK constraint rejects an unknown value
DO $$ BEGIN
  INSERT INTO ledger.ledger_account (tenant_id, ledger_id, code, name, account_type, normal_balance, currency)
  SELECT tenant_id, id, '9999', 'Bad', 'NOT_A_TYPE', 'DEBIT', 'USD' FROM ledger.ledger LIMIT 1;
  RAISE EXCEPTION 'FAIL 5 invalid enum accepted';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'PASS 5 invalid enum value rejected by CHECK';
END $$;

-- 6. Custom fields persist
SELECT CASE WHEN custom_field_1 = 'cost-centre-42' AND custom_field_2 = 'region-emea'
            THEN 'PASS 6 custom_field_1/2 stored and returned' ELSE 'FAIL 6 custom fields' END
FROM ledger WHERE code = 'MAIN-USD';
