-- wallet-service local migration (not generated), SEC-07: a transaction can never be reversed for more than its
-- amount, whatever the application does under concurrency.
SET search_path TO wallet;

ALTER TABLE wallet_transaction
  ADD CONSTRAINT ck_wallet_transaction_reversed_le_amount CHECK (reversed_amount >= 0 AND reversed_amount <= amount);
