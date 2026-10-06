-- Application role used by services at runtime (no superuser, no BYPASSRLS), created before Flyway runs
-- so the generated DDL's GRANT block applies.
CREATE ROLE meridian_app LOGIN PASSWORD 'meridian_app' NOSUPERUSER NOBYPASSRLS;
