-- payment-service local migration (not generated), SEC-06: a payment has at most one live (not FAILED / REJECTED)
-- return per direction, whatever the application does under concurrency.
SET search_path TO payment;

CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_return_live
  ON payment_return (tenant_id, payment_id, direction) WHERE status NOT IN ('FAILED', 'REJECTED');
