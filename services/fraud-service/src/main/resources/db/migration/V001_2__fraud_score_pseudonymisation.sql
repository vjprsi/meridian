-- fraud-service local migration (not generated). fraud_score stays append-only, with one narrow exception: the
-- one-off pseudonymisation of identifiers stored before keyed pseudonymisation (POST
-- /v1/risk/fraud/scores/pseudonymise-stored) may rewrite the `features` column, and nothing else, inside a
-- transaction that set fraud.pseudonymise = 'on'. Deletes stay forbidden.
SET search_path TO fraud;

CREATE OR REPLACE FUNCTION fraud_score_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND current_setting('fraud.pseudonymise', true) = 'on'
     AND (to_jsonb(NEW) - 'features') = (to_jsonb(OLD) - 'features') THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'table % is append-only', TG_TABLE_NAME USING ERRCODE = '55000';
END $$;

DROP TRIGGER IF EXISTS trg_fraud_score_append_only ON fraud_score;
CREATE TRIGGER trg_fraud_score_append_only BEFORE UPDATE OR DELETE ON fraud_score
  FOR EACH ROW EXECUTE FUNCTION fraud_score_guard();
