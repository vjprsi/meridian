-- gl-service local migration (not generated): gl_journal is append-only, yet the spec requires a checker to sign off
-- manual journals (approved_by). Allow exactly one change: approved_by from NULL to a value, nothing else.
SET search_path TO gl;

CREATE OR REPLACE FUNCTION gl_journal_signoff_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND OLD.approved_by IS NULL AND NEW.approved_by IS NOT NULL
     AND (to_jsonb(NEW) - 'approved_by') = (to_jsonb(OLD) - 'approved_by') THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'table % is append-only', TG_TABLE_NAME USING ERRCODE = '55000';
END $$;

DROP TRIGGER IF EXISTS trg_gl_journal_append_only ON gl_journal;
CREATE TRIGGER trg_gl_journal_append_only BEFORE UPDATE OR DELETE ON gl_journal
  FOR EACH ROW EXECUTE FUNCTION gl_journal_signoff_only();
