#!/usr/bin/env python3
"""Generates the local Keycloak realm (infra/local/keycloak/meridian-realm.json) from the spec.

One client scope per domain and access level (<domain>:read, <domain>:write, <domain>:*) plus meridian:admin.
Clients:
  meridian-console   public, authorization code + PKCE (web apps); password grant enabled for local testing only
  meridian-service   confidential, client credentials, every scope (service-to-service, scripts)
  meridian-readonly  confidential, client credentials, read scopes only (authorisation tests)
The tenant_id claim is a hardcoded claim for the local demo tenant (multi-tenant users come from the identity
service in later steps). Run after tools/generate.py.
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEMO_TENANT = "0199a000-0000-7000-8000-00000000000a"


def main():
    manifest = json.loads((ROOT / "generated" / "services.json").read_text(encoding="utf-8"))
    domains = sorted({s["domain"] for s in manifest})
    scope_names = [f"{d}:{a}" for d in domains for a in ("read", "write", "*")] + ["meridian:admin"]
    # meridian:internal = service identity allowed to act for the tenant in X-Tenant-Id (jobs, callbacks).
    # Privileged card-vault scopes (SEC-11) are optional: never in a default token, requested explicitly.
    privileged = ["cards:pan_reveal", "cards:vault_admin"]
    scopes = [{"name": n, "protocol": "openid-connect",
               "attributes": {"include.in.token.scope": "true", "display.on.consent.screen": "false"}}
              for n in scope_names + ["meridian:internal"] + privileged]
    tenant_mapper = {"name": "tenant_id", "protocol": "openid-connect",
                     "protocolMapper": "oidc-hardcoded-claim-mapper",
                     "config": {"claim.name": "tenant_id", "claim.value": DEMO_TENANT, "jsonType.label": "String",
                                "access.token.claim": "true", "id.token.claim": "true", "userinfo.token.claim": "true"}}
    read_scopes = [n for n in scope_names if n.endswith(":read")]
    realm = {
        "realm": "meridian",
        "enabled": True,
        "sslRequired": "external",
        "accessTokenLifespan": 900,
        "ssoSessionIdleTimeout": 1800,
        "bruteForceProtected": True,
        "passwordPolicy": "length(12) and upperCase(1) and digits(1) and specialChars(1) and notUsername",
        "clientScopes": scopes,
        "clients": [
            {"clientId": "meridian-console", "name": "Meridian web apps", "publicClient": True,
             "standardFlowEnabled": True, "directAccessGrantsEnabled": True,
             "redirectUris": ["http://localhost:*"], "webOrigins": ["+"],
             "attributes": {"pkce.code.challenge.method": "S256"},
             "protocolMappers": [tenant_mapper],
             "defaultClientScopes": ["profile", "email", "web-origins"] + scope_names,
             "optionalClientScopes": privileged},
            {"clientId": "meridian-service", "name": "Meridian service account", "publicClient": False,
             "secret": "meridian-service-secret", "serviceAccountsEnabled": True,
             "standardFlowEnabled": False, "directAccessGrantsEnabled": False,
             "protocolMappers": [tenant_mapper], "defaultClientScopes": scope_names + ["meridian:internal"]},
            {"clientId": "meridian-readonly", "name": "Read-only integration", "publicClient": False,
             "secret": "meridian-readonly-secret", "serviceAccountsEnabled": True,
             "standardFlowEnabled": False, "directAccessGrantsEnabled": False,
             "protocolMappers": [tenant_mapper], "defaultClientScopes": read_scopes},
        ],
        "users": [
            {"username": "ops.admin", "enabled": True, "email": "ops.admin@meridian.local", "emailVerified": True,
             "firstName": "Ops", "lastName": "Admin",
             "credentials": [{"type": "password", "value": "Meridian#Admin2026", "temporary": False}]},
        ],
    }
    out = ROOT / "infra" / "local" / "keycloak" / "meridian-realm.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(realm, indent=2), encoding="utf-8", newline="\n")
    print(f"realm with {len(scope_names)} scopes, 3 clients -> {out.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
