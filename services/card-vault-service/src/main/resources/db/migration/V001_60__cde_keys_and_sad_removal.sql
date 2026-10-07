-- card-vault-service local migration (not generated). GAP-01 / GAP-03.
--
-- Fresh databases already have the generated schema (V001__init.sql: no cvv_encrypted, cvk_key_id / pin_key_id /
-- wrapped_key / master_key_ref / wrapped_at present); every statement below is then a no-op. Databases created
-- before the change (after `flyway repair` of the regenerated V001) are brought to the same shape here:
--
--   1. key metadata gains the envelope columns (wrapped data key, master key reference, wrap time); existing keys
--      stay "legacy derived" until the master key re-wrap job adopts them;
--   2. every record names the card verification key its CVV2 derives from (the CVK active when it was vaulted);
--   3. PCI DSS 3.3.1: the stored CVV2 is removed - first overwritten, then the column is dropped. The old values
--      remain in dead tuples until `VACUUM FULL card_vault.card_data_vault` (RUNBOOKS 22) and in backups until the
--      PAN DEK that encrypted them is rotated and the retired key destroyed (crypto-shredding);
--   4. PINs: pin_key_id names the PIN encryption key of the stored PIN block. Rows with a PIN but no pin_key_id are
--      legacy (PIN encrypted under the DEK); the service converts them on first use or with a PIN_KEY rotation;
--   5. card_vault_key_tenants(): tenant ids holding vault keys, for the master key re-wrap job (ids only, owner
--      rights so the job can enumerate tenants without bypassing row-level security on the key rows themselves).
SET search_path TO card_vault;

ALTER TABLE card_vault_key ADD COLUMN IF NOT EXISTS wrapped_key bytea;
ALTER TABLE card_vault_key ADD COLUMN IF NOT EXISTS master_key_ref varchar(256);
ALTER TABLE card_vault_key ADD COLUMN IF NOT EXISTS wrapped_at timestamptz;

ALTER TABLE card_data_vault ADD COLUMN IF NOT EXISTS cvk_key_id uuid REFERENCES card_vault_key(id);
ALTER TABLE card_data_vault ADD COLUMN IF NOT EXISTS pin_key_id uuid REFERENCES card_vault_key(id);

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns
             WHERE table_schema = 'card_vault' AND table_name = 'card_data_vault' AND column_name = 'cvv_encrypted') THEN
    UPDATE card_data_vault v SET cvk_key_id = (
        SELECT k.id FROM card_vault_key k
        WHERE k.tenant_id = v.tenant_id AND k.key_type = 'CVK' AND k.created_at <= v.created_at
        ORDER BY k.version DESC, k.created_at DESC LIMIT 1)
      WHERE v.cvk_key_id IS NULL;
    UPDATE card_data_vault SET cvv_encrypted = NULL WHERE cvv_encrypted IS NOT NULL;
    ALTER TABLE card_data_vault DROP COLUMN cvv_encrypted;
  END IF;
END $$;

CREATE OR REPLACE FUNCTION card_vault_key_tenants() RETURNS SETOF uuid
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = card_vault, pg_temp
  AS $$ SELECT DISTINCT tenant_id FROM card_vault_key $$;
REVOKE ALL ON FUNCTION card_vault_key_tenants() FROM PUBLIC;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'meridian_app') THEN
    GRANT EXECUTE ON FUNCTION card_vault_key_tenants() TO meridian_app;
  END IF;
END $$;
