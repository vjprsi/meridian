-- Data mover progress (MR-GAME-DAY): one run per tenant move, one item per service table. Lets any mover instance
-- resume a run after a crash, restart or transient failure (lease), and keeps the verification manifest.

CREATE TABLE move_run (
  move_id          uuid PRIMARY KEY,
  tenant_id        uuid NOT NULL,
  from_cell        varchar(64) NOT NULL,
  to_cell          varchar(64) NOT NULL,
  strategy         varchar(32) NOT NULL,
  request          jsonb NOT NULL,
  status           varchar(16) NOT NULL DEFAULT 'PENDING'
                   CHECK (status IN ('PENDING', 'COPYING', 'VERIFYING', 'CONFIRMING', 'COMPLETED', 'FAILED', 'CANCELLED')),
  not_before       timestamptz NOT NULL,
  owner            varchar(128),
  lease_until      timestamptz,
  attempts         integer NOT NULL DEFAULT 0,
  planned          boolean NOT NULL DEFAULT false,
  docs_done        boolean NOT NULL DEFAULT false,
  last_error       text,
  docs_last_key    varchar(1024),
  docs_copied      bigint NOT NULL DEFAULT 0,
  docs_bytes       bigint NOT NULL DEFAULT 0,
  docs_verified    boolean NOT NULL DEFAULT false,
  docs_digest      varchar(96),
  checkpoint       varchar(255),
  manifest         jsonb,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  completed_at     timestamptz
);
CREATE INDEX ix_move_run_open ON move_run (status, lease_until) WHERE status IN ('PENDING', 'COPYING', 'VERIFYING', 'CONFIRMING');

CREATE TABLE move_item (
  move_id          uuid NOT NULL REFERENCES move_run (move_id),
  service          varchar(64) NOT NULL,
  table_name       varchar(63) NOT NULL,
  ord              integer NOT NULL,
  phase            varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (phase IN ('PENDING', 'COPIED', 'VERIFIED')),
  last_id          uuid,
  rows_copied      bigint NOT NULL DEFAULT 0,
  deferred         jsonb NOT NULL DEFAULT '[]'::jsonb,
  source_rows      bigint,
  target_rows      bigint,
  source_digest    varchar(64),
  target_digest    varchar(64),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (move_id, service, table_name)
);
