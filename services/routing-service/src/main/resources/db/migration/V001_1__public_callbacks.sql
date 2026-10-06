-- routing-service local migration (not generated): provider callbacks ([public] operations) arrive without a
-- token or tenant header. The tenant is resolved from the referenced rail through this SECURITY DEFINER function
-- (owned by the schema owner, so it reads past row-level security) and returns nothing but the tenant id.
SET search_path TO routing;

CREATE OR REPLACE FUNCTION public_rail_tenant(p_rail_id uuid) RETURNS uuid
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = routing AS $$
  SELECT tenant_id FROM payment_rail WHERE id = p_rail_id
$$;

REVOKE ALL ON FUNCTION public_rail_tenant(uuid) FROM PUBLIC;
DO $$
BEGIN
  IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'meridian_app') THEN
    GRANT EXECUTE ON FUNCTION public_rail_tenant(uuid) TO meridian_app;
  END IF;
END $$;
