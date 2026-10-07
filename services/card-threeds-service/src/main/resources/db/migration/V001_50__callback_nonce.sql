-- Local migration (not generated), SEC-17: nonces of v2-signed provider callbacks, each accepted once per provider.
-- Platform-global (no tenant, no row-level security); rows older than twice the accepted clock skew are purged.
SET search_path TO card_3ds;

CREATE TABLE IF NOT EXISTS callback_nonce (
  provider     text        NOT NULL,
  nonce        text        NOT NULL,
  received_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (provider, nonce)
);
CREATE INDEX IF NOT EXISTS ix_callback_nonce_received ON callback_nonce (received_at);
