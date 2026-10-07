#!/usr/bin/env python3
"""Generates the local Keycloak realm (infra/local/keycloak/meridian-realm.json) from the spec.

Staff scopes: one client scope per domain and access level (<domain>:read, <domain>:write, <domain>:*) plus
meridian:admin. Customer scopes: a domain that declares `scope:` in its spec (channels -> channels:customer) gets
only that scope, and only customer clients receive it; staff clients never do, and customer clients receive no
staff scope (so a customer token is refused by every operator endpoint).

Clients:
  meridian-console        public, authorization code + PKCE (staff web apps); password grant for local testing only
  meridian-service        confidential, client credentials, every staff scope + meridian:internal (service-to-service)
  meridian-readonly       confidential, client credentials, read scopes only (authorisation tests)
  meridian-customer-web   public, authorization code + PKCE (customer web banking); only the customer scope plus
                          openid/profile; tokens carry customer_id and tenant_id from user attributes (admin-managed,
                          not editable by the user) and auth_time / acr for step-up; password grant for local testing
                          (password-grant tokens carry no auth_time, so step-up operations refuse them)
Users:
  ops.admin       staff demo user (console)
  demo.customer   customer demo user (customer-web); set its customer_id attribute to a customer of the demo tenant
The staff tenant_id claim is a hardcoded claim for the local demo tenant (multi-tenant staff users come from the
identity service in later steps). Run after tools/generate.py.
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEMO_TENANT = "0199a000-0000-7000-8000-00000000000a"
# Demo customer bound to the demo customer user. Change the user's customer_id attribute (Keycloak admin console,
# Users -> demo.customer -> Attributes) to an existing customer of the demo tenant.
DEMO_CUSTOMER = "0199a000-0000-7000-8000-0000000c0001"
CUSTOMER_WEB_ORIGINS = ["http://localhost:13300"]


def attribute_mapper(name: str, attribute: str) -> dict:
    return {"name": name, "protocol": "openid-connect", "protocolMapper": "oidc-usermodel-attribute-mapper",
            "config": {"user.attribute": attribute, "claim.name": name, "jsonType.label": "String",
                       "access.token.claim": "true", "id.token.claim": "true", "userinfo.token.claim": "true",
                       "multivalued": "false"}}


def admin_only_attribute(name: str, display: str) -> dict:
    """User-profile attribute only administrators may see or change (never the user via the account console)."""
    return {"name": name, "displayName": display, "multivalued": False,
            "validations": {"pattern": {"pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
                                        "error-message": "must be a UUID"}},
            "permissions": {"view": ["admin"], "edit": ["admin"]}}


def user_profile() -> dict:
    """Keycloak 26 declarative user profile: the defaults plus customer_id / tenant_id as admin-only attributes."""
    person = {"length": {"max": 255}, "person-name-prohibited-characters": {}}
    rw = {"view": ["admin", "user"], "edit": ["admin", "user"]}
    return {
        "attributes": [
            {"name": "username", "displayName": "${username}", "multivalued": False, "permissions": rw,
             "validations": {"length": {"min": 3, "max": 255}, "username-prohibited-characters": {},
                             "up-username-not-idn-homograph": {}}},
            {"name": "email", "displayName": "${email}", "multivalued": False, "permissions": rw,
             "required": {"roles": ["user"]}, "validations": {"email": {}, "length": {"max": 255}}},
            {"name": "firstName", "displayName": "${firstName}", "multivalued": False, "permissions": rw,
             "required": {"roles": ["user"]}, "validations": person},
            {"name": "lastName", "displayName": "${lastName}", "multivalued": False, "permissions": rw,
             "required": {"roles": ["user"]}, "validations": person},
            admin_only_attribute("customer_id", "Customer id"),
            admin_only_attribute("tenant_id", "Tenant id"),
        ],
        "groups": [{"name": "user-metadata", "displayHeader": "User metadata",
                    "displayDescription": "Attributes, which refer to user metadata"}],
    }


def main():
    manifest = json.loads((ROOT / "generated" / "services.json").read_text(encoding="utf-8"))
    customer_scopes = sorted({s["scope"] for s in manifest if s.get("scope")})
    customer_domains = {s["domain"] for s in manifest if s.get("scope")}
    domains = sorted({s["domain"] for s in manifest} - customer_domains)
    scope_names = [f"{d}:{a}" for d in domains for a in ("read", "write", "*")] + ["meridian:admin"]
    # meridian:internal = service identity allowed to act for the tenant in X-Tenant-Id (jobs, callbacks).
    scopes = [{"name": n, "protocol": "openid-connect",
               "attributes": {"include.in.token.scope": "true", "display.on.consent.screen": "false"}}
              for n in scope_names + ["meridian:internal"] + customer_scopes]
    tenant_mapper = {"name": "tenant_id", "protocol": "openid-connect",
                     "protocolMapper": "oidc-hardcoded-claim-mapper",
                     "config": {"claim.name": "tenant_id", "claim.value": DEMO_TENANT, "jsonType.label": "String",
                                "access.token.claim": "true", "id.token.claim": "true", "userinfo.token.claim": "true"}}
    # Customer tokens: identity from admin-managed user attributes; auth_time and acr for step-up checks; sub and
    # preferred_username explicitly (the realm's default "basic" scope may be absent in an imported realm).
    customer_mappers = [
        attribute_mapper("customer_id", "customer_id"),
        attribute_mapper("tenant_id", "tenant_id"),
        {"name": "auth_time", "protocol": "openid-connect", "protocolMapper": "oidc-usersessionmodel-note-mapper",
         "config": {"user.session.note": "AUTH_TIME", "claim.name": "auth_time", "jsonType.label": "long",
                    "access.token.claim": "true", "id.token.claim": "true", "introspection.token.claim": "true"}},
        {"name": "acr", "protocol": "openid-connect", "protocolMapper": "oidc-acr-mapper",
         "config": {"access.token.claim": "true", "id.token.claim": "true", "introspection.token.claim": "true"}},
        {"name": "sub", "protocol": "openid-connect", "protocolMapper": "oidc-sub-mapper",
         "config": {"access.token.claim": "true", "introspection.token.claim": "true"}},
        {"name": "preferred_username", "protocol": "openid-connect", "protocolMapper": "oidc-usermodel-property-mapper",
         "config": {"user.attribute": "username", "claim.name": "preferred_username", "jsonType.label": "String",
                    "access.token.claim": "true", "id.token.claim": "true", "userinfo.token.claim": "true"}},
    ]
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
        "components": {"org.keycloak.userprofile.UserProfileProvider": [{
            "providerId": "declarative-user-profile", "subComponents": {},
            "config": {"kc.user.profile.config": [json.dumps(user_profile(), separators=(",", ":"))]}}]},
        "clients": [
            {"clientId": "meridian-console", "name": "Meridian web apps", "publicClient": True,
             "standardFlowEnabled": True, "directAccessGrantsEnabled": True,
             "redirectUris": ["http://localhost:*"], "webOrigins": ["+"],
             "attributes": {"pkce.code.challenge.method": "S256"},
             "protocolMappers": [tenant_mapper],
             "defaultClientScopes": ["profile", "email", "web-origins"] + scope_names},
            {"clientId": "meridian-service", "name": "Meridian service account", "publicClient": False,
             "secret": "meridian-service-secret", "serviceAccountsEnabled": True,
             "standardFlowEnabled": False, "directAccessGrantsEnabled": False,
             "protocolMappers": [tenant_mapper], "defaultClientScopes": scope_names + ["meridian:internal"]},
            {"clientId": "meridian-readonly", "name": "Read-only integration", "publicClient": False,
             "secret": "meridian-readonly-secret", "serviceAccountsEnabled": True,
             "standardFlowEnabled": False, "directAccessGrantsEnabled": False,
             "protocolMappers": [tenant_mapper], "defaultClientScopes": read_scopes},
            {"clientId": "meridian-customer-web", "name": "Meridian customer web banking", "publicClient": True,
             "standardFlowEnabled": True, "implicitFlowEnabled": False, "serviceAccountsEnabled": False,
             "directAccessGrantsEnabled": True,           # local testing only (scripts obtaining a customer token)
             "fullScopeAllowed": False,
             "redirectUris": [o + "/*" for o in CUSTOMER_WEB_ORIGINS], "webOrigins": CUSTOMER_WEB_ORIGINS,
             "attributes": {"pkce.code.challenge.method": "S256", "post.logout.redirect.uris": "+"},
             "protocolMappers": customer_mappers,
             "defaultClientScopes": ["profile", "email", "web-origins"] + customer_scopes,
             "optionalClientScopes": []},
        ],
        "users": [
            {"username": "ops.admin", "enabled": True, "email": "ops.admin@meridian.local", "emailVerified": True,
             "firstName": "Ops", "lastName": "Admin",
             "credentials": [{"type": "password", "value": "Meridian#Admin2026", "temporary": False}]},
            {"username": "demo.customer", "enabled": True, "email": "demo.customer@meridian.local",
             "emailVerified": True, "firstName": "Demo", "lastName": "Customer",
             "attributes": {"customer_id": [DEMO_CUSTOMER], "tenant_id": [DEMO_TENANT]},
             "credentials": [{"type": "password", "value": "Meridian#Customer2026", "temporary": False}]},
        ],
    }
    out = ROOT / "infra" / "local" / "keycloak" / "meridian-realm.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(realm, indent=2), encoding="utf-8", newline="\n")
    print(f"realm with {len(scope_names)} staff scopes, {len(customer_scopes)} customer scope(s), 4 clients, "
          f"2 users -> {out.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
