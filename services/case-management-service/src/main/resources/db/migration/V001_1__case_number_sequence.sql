-- case-management-service local migration (not generated): human-readable case numbers come from a sequence
-- (gap-tolerant, unique across tenants, never reused).
SET search_path TO aml_case;

CREATE SEQUENCE IF NOT EXISTS aml_case_number_seq START WITH 1 INCREMENT BY 1;

DO $$
BEGIN
  IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'meridian_app') THEN
    GRANT USAGE, SELECT ON SEQUENCE aml_case_number_seq TO meridian_app;
  END IF;
END $$;
