-- payment-service local migration (not generated): transaction taxes (W3, Colombia GMF 4x1000 / IVA on fees).
-- The generated V001 declares the same columns for new databases; IF NOT EXISTS makes this a no-op there.
SET search_path TO payment;

ALTER TABLE payment ADD COLUMN IF NOT EXISTS tax_amount numeric(28,8);
ALTER TABLE payment ADD COLUMN IF NOT EXISTS tax_breakdown jsonb;
COMMENT ON COLUMN payment.tax_amount IS 'Transaction taxes debited on top of the amount and fee (e.g. Colombia GMF 4x1000, IVA on the fee), in the payment currency';
COMMENT ON COLUMN payment.tax_breakdown IS 'Taxes applied: {total, lines: [{tax_type, name, rate, base, exempt_amount, amount, reason}]}';
