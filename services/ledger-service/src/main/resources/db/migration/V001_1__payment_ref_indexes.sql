-- ledger-service local migration (not generated). Lookups of journal entries by payment reference
-- (GET /v1/journal-entries?uetr=... / end_to_end_id / instruction_id / bank_reference / scheme_reference / batch_id /
-- counterparty_account_hash: reconciliation investigations, payment enquiries). The indexes are PARTIAL: a posting
-- without payment references (internal postings, the ledger load test) adds no index entry, so the posting hot path
-- only pays for the indexes of the references it actually carries. Ledger entries (lines) are read per account
-- (ix_ledger_entry_c1) and carry copies of the references, so they need no extra index.
SET search_path TO ledger;

CREATE INDEX IF NOT EXISTS ix_journal_entry_uetr ON journal_entry (uetr) WHERE uetr IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_journal_entry_end_to_end_id ON journal_entry (end_to_end_id) WHERE end_to_end_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_journal_entry_instruction_id ON journal_entry (instruction_id) WHERE instruction_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_journal_entry_bank_reference ON journal_entry (bank_reference) WHERE bank_reference IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_journal_entry_scheme_reference ON journal_entry (scheme_reference) WHERE scheme_reference IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_journal_entry_batch_id ON journal_entry (batch_id) WHERE batch_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_journal_entry_counterparty_account_hash ON journal_entry (counterparty_account_hash)
  WHERE counterparty_account_hash IS NOT NULL;
