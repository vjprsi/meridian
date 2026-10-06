-- search-service owned storage for the PostgreSQL full-text search engine (not part of the generated spec DDL).
-- search_document: one row per entity per physical index (aliases swap between physical indexes on reindex).
-- search_ingest_log: append-only journal of ingested changes, the source for full reindex and catch-up replays.
SET search_path TO search;

CREATE TABLE search_document (
  tenant_id        uuid NOT NULL,
  physical_index   varchar(160) NOT NULL,
  entity_type      varchar(40) NOT NULL,
  entity_id        varchar(128) NOT NULL,
  title            text,
  body             jsonb NOT NULL,
  search_text      text NOT NULL,
  tsv              tsvector NOT NULL,
  source_event_id  varchar(128),
  source_version   bigint,
  occurred_at      timestamptz,
  indexed_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, physical_index, entity_id)
);
CREATE INDEX ix_search_document_tsv ON search_document USING gin (tsv);
CREATE INDEX ix_search_document_body ON search_document USING gin (body jsonb_path_ops);
CREATE INDEX ix_search_document_title ON search_document (tenant_id, physical_index, lower(title) text_pattern_ops);
ALTER TABLE search_document ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON search_document USING (tenant_id = current_setting('app.tenant_id')::uuid);

CREATE TABLE search_ingest_log (
  id               uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id        uuid NOT NULL,
  entity_type      varchar(40) NOT NULL,
  entity_id        varchar(128) NOT NULL,
  operation        varchar(16) NOT NULL CHECK (operation IN ('UPSERT', 'DELETE')),
  document         jsonb,
  source_event_id  varchar(128),
  source_version   bigint,
  occurred_at      timestamptz NOT NULL DEFAULT now(),
  ingested_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_search_ingest_log_entity ON search_ingest_log (tenant_id, entity_type, entity_id, id);
CREATE INDEX ix_search_ingest_log_time ON search_ingest_log (tenant_id, entity_type, occurred_at);
ALTER TABLE search_ingest_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON search_ingest_log USING (tenant_id = current_setting('app.tenant_id')::uuid);
CREATE TRIGGER trg_search_ingest_log_append_only BEFORE UPDATE OR DELETE ON search_ingest_log
  FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

DO $$
BEGIN
  IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'meridian_app') THEN
    GRANT SELECT, INSERT, UPDATE, DELETE ON search_document, search_ingest_log TO meridian_app;
  END IF;
END $$;
