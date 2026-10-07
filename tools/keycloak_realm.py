#!/usr/bin/env python3
"""Generates the local Keycloak realm (infra/local/keycloak/meridian-realm.json) from the spec.

Staff scopes: one client scope per domain and access level (<domain>:read, <domain>:write, <domain>:*) plus
meridian:admin (the tenant's superuser) and platform:operator (platform-global writes, tenant registry, cross-tenant
jobs; SEC-15). Customer scopes: a domain that declares `scope:` in its spec (channels -> channels:customer) gets
only that scope, and only customer clients receive it; staff clients never do, and customer clients receive no
staff scope (so a customer token is refused by every operator endpoint).

Every client's access tokens carry the audience `meridian-api`; services refuse tokens without it (SEC-14).
meridian:admin and platform:operator are role-gated: Keycloak adds them to a token only for users holding the realm
role meridian-admin / platform-operator (client-scope role scope mappings). Services accept platform:operator from a
user only when the token's tenant is the platform-operator tenant (meridian.security.platform-operator-tenant).

Clients (SEC-14: one per app, minimal scopes, no password grant for staff apps):
  meridian-console           public, authorization code + PKCE (ops console); every staff domain scope, meridian:admin
                             (role-gated), platform:operator / card-vault scopes optional
  meridian-developer-portal  public, authorization code + PKCE (developer portal): platform:read / platform:write only
                             (API clients, keys, webhooks, request logs); never meridian:admin
  meridian-agent-app         public, authorization code + PKCE (agent outlet app); only channels:agent plus
                             openid/profile; tokens carry agent_id and tenant_id from admin-only user attributes and
                             auth_time / acr (money movement needs a recent login or a device proof)
  meridian-business-portal   public, authorization code + PKCE (business banking portal); only channels:business plus
                             openid/profile; tokens carry business_customer_id, business_user_id and tenant_id from
                             admin-only user attributes and auth_time / acr (approvals and administration need a recent
                             authentication); no password grant
  meridian-merchant-app      public, authorization code + PKCE (merchant app PWA); only channels:merchant plus
                             openid/profile; tokens carry merchant_id, merchant_user_id and tenant_id from admin-only
                             user attributes and auth_time / acr (refund approvals, payout account changes and
                             administration need a recent authentication); no password grant
  meridian-service           confidential, client credentials, every staff scope + meridian:internal + platform:operator
                             (service-to-service)
  meridian-cell-router       confidential, client credentials, only platform:routing-read (tenant routing and cell
                             reads); tokens carry the platform-operator tenant, no meridian:internal
  meridian-readonly          confidential, client credentials, read scopes only (authorisation tests)
  meridian-customer-web      public, authorization code + PKCE (customer web banking); only the customer scope plus
                             openid/profile; tokens carry customer_id and tenant_id from user attributes (admin-managed,
                             not editable by the user) and auth_time / acr for step-up; password grant for local testing
                             (password-grant tokens carry no auth_time, so step-up operations refuse them)
Users:
  ops.admin          staff demo user (console), demo tenant, realm role meridian-admin
  platform.operator  platform operator (console), platform-operator tenant, realm role platform-operator
  demo.customer      customer demo user (customer-web); set its customer_id attribute to a customer of the demo tenant
  demo.agent         agent demo user (agent-app, Meridian#Agent2026); set its agent_id attribute to an ACTIVE agent of
                     the demo tenant
  demo.business.admin     business portal admin (Meridian#Business2026); business_customer_id = a business customer
                          (customer_type not INDIVIDUAL) of the demo tenant. The first user who signs in for a business
                          with no portal users becomes its ADMIN (meridian.business.bootstrap-first-admin).
  demo.business.approver  business portal approver (Meridian#Approver2026), same business. The admin adds the user in
                          the portal (Users, role APPROVER) and an IdP administrator sets the returned id as this
                          user's business_user_id attribute (the preset value is a placeholder until then).
  demo.merchant.owner     merchant app owner (Meridian#Merchant2026); merchant_id = an ACTIVE merchant of the demo
                          tenant. The first user who signs in for a merchant with no app users becomes its OWNER
                          (meridian.merchant.bootstrap-first-owner).
  demo.merchant.cashier   merchant app cashier (Meridian#Cashier2026), same merchant. The owner adds the user in the
                          app (Staff, role CASHIER, stores) and an IdP administrator sets the returned id as this
                          user's merchant_user_id attribute (the preset value is a placeholder until then).
Staff web-app tokens take tenant_id from the user's admin-managed tenant_id attribute; service-account tokens carry the
demo tenant (internal tokens name their tenant in X-Tenant-Id). Run after tools/generate.py.
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEMO_TENANT = "0199a000-0000-7000-8000-00000000000a"
# Platform-operator tenant (set the same id in meridian.security.platform-operator-tenant / Helm
# global.auth.platformOperatorTenant). Its users may use platform:operator; a bank's users never can.
PLATFORM_TENANT = "0199a000-0000-7000-8000-0000000000f0"
AUDIENCE = "meridian-api"
DEV_PORTAL_ORIGINS = ["http://localhost:13200"]
# Demo customer bound to the demo customer user. Change the user's customer_id attribute (Keycloak admin console,
# Users -> demo.customer -> Attributes) to an existing customer of the demo tenant.
DEMO_CUSTOMER = "0199a000-0000-7000-8000-0000000c0001"
CUSTOMER_WEB_ORIGINS = ["http://localhost:13300"]
# Demo agent bound to the demo agent user (agent-network-service agent id). Change the user's agent_id attribute
# (Users -> demo.agent -> Attributes; admin-only) to an ACTIVE agent of the demo tenant.
DEMO_AGENT = "0199a000-0000-7000-8000-0000000a0001"
AGENT_APP_ORIGINS = ["http://localhost:13400"]
# Demo business (a COMPANY customer of the demo tenant) and its two portal users (business-channel-service
# business_user ids). Change the attributes (Users -> demo.business.* -> Attributes; admin-only) to real values.
DEMO_BUSINESS = "0199a000-0000-7000-8000-0000000b0001"
DEMO_BUSINESS_ADMIN = "0199a000-0000-7000-8000-0000000b0a01"
DEMO_BUSINESS_APPROVER = "0199a000-0000-7000-8000-0000000b0a02"
BUSINESS_PORTAL_ORIGINS = ["http://localhost:13500"]
# Demo merchant (an ACTIVE merchant of merchant-service in the demo tenant) and its two app users
# (merchant-channel-service merchant_user ids). Change the attributes (Users -> demo.merchant.* -> Attributes;
# admin-only) to real values.
DEMO_MERCHANT = "0199a000-0000-7000-8000-0000000d0001"
DEMO_MERCHANT_OWNER = "0199a000-0000-7000-8000-0000000d0a01"
DEMO_MERCHANT_CASHIER = "0199a000-0000-7000-8000-0000000d0a02"
MERCHANT_APP_ORIGINS = ["http://localhost:13600"]
CUSTOMER_SCOPE = "channels:customer"
AGENT_SCOPE = "channels:agent"
BUSINESS_SCOPE = "channels:business"
MERCHANT_SCOPE = "channels:merchant"
# The cell router's only scope: tenant routing / cell reads in the registry (spec `scopes:` on tenant-service).
ROUTING_SCOPE = "platform:routing-read"


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
            admin_only_attribute("agent_id", "Agent id"),
            admin_only_attribute("business_customer_id", "Business customer id"),
            admin_only_attribute("business_user_id", "Business portal user id"),
            admin_only_attribute("merchant_id", "Merchant id"),
            admin_only_attribute("merchant_user_id", "Merchant app user id"),
            admin_only_attribute("tenant_id", "Tenant id"),
        ],
        "groups": [{"name": "user-metadata", "displayHeader": "User metadata",
                    "displayDescription": "Attributes, which refer to user metadata"}],
    }


def audience_mapper() -> dict:
    """SEC-14: every Meridian token is issued for the Meridian API (services validate aud)."""
    return {"name": "audience", "protocol": "openid-connect", "protocolMapper": "oidc-audience-mapper",
            "config": {"included.custom.audience": AUDIENCE, "access.token.claim": "true",
                       "id.token.claim": "false", "introspection.token.claim": "true"}}


def staff_app(client_id: str, name: str, origins: list, scopes: list, optional: list | None = None) -> dict:
    """Public staff web app: authorization code + PKCE only, tenant from the user's tenant_id attribute."""
    return {"clientId": client_id, "name": name, "publicClient": True,
            "standardFlowEnabled": True, "implicitFlowEnabled": False, "directAccessGrantsEnabled": False,
            "serviceAccountsEnabled": False, "fullScopeAllowed": False,
            "redirectUris": [o + "/*" for o in origins], "webOrigins": origins,
            "attributes": {"pkce.code.challenge.method": "S256", "post.logout.redirect.uris": "+"},
            "protocolMappers": [attribute_mapper("tenant_id", "tenant_id"), audience_mapper()],
            "defaultClientScopes": ["profile", "email", "web-origins"] + scopes,
            "optionalClientScopes": optional or []}


def main():
    manifest = json.loads((ROOT / "generated" / "services.json").read_text(encoding="utf-8"))
    channel_scopes = sorted({s["scope"] for s in manifest if s.get("scope")})
    # Each channel client gets exactly its own channel scope: customers channels:customer, agents channels:agent.
    customer_scopes = [s for s in channel_scopes if s == CUSTOMER_SCOPE]
    agent_scopes = [s for s in channel_scopes if s == AGENT_SCOPE]
    business_scopes = [s for s in channel_scopes if s == BUSINESS_SCOPE]
    merchant_scopes = [s for s in channel_scopes if s == MERCHANT_SCOPE]
    unknown = [s for s in channel_scopes if s not in (CUSTOMER_SCOPE, AGENT_SCOPE, BUSINESS_SCOPE, MERCHANT_SCOPE)]
    if unknown:
        raise SystemExit(f"channel scope(s) {unknown} have no client; add one in tools/keycloak_realm.py")
    customer_domains = {s["domain"] for s in manifest if s.get("scope")}
    domains = sorted({s["domain"] for s in manifest} - customer_domains)
    scope_names = [f"{d}:{a}" for d in domains for a in ("read", "write", "*")] + ["meridian:admin"]
    operator = "platform:operator"
    # meridian:internal = service identity allowed to act for the tenant in X-Tenant-Id (jobs, callbacks).
    # Privileged card-vault scopes (SEC-11) are optional: never in a default token, requested explicitly.
    privileged = ["cards:pan_reveal", "cards:vault_admin"]
    scopes = [{"name": n, "protocol": "openid-connect",
               "attributes": {"include.in.token.scope": "true", "display.on.consent.screen": "false"}}
              for n in scope_names + ["meridian:internal", operator, ROUTING_SCOPE] + channel_scopes + privileged]
    tenant_mapper = {"name": "tenant_id", "protocol": "openid-connect",
                     "protocolMapper": "oidc-hardcoded-claim-mapper",
                     "config": {"claim.name": "tenant_id", "claim.value": DEMO_TENANT, "jsonType.label": "String",
                                "access.token.claim": "true", "id.token.claim": "true", "userinfo.token.claim": "true"}}
    # The cell router's tokens: tenant = the platform-operator tenant (services honour platform:routing-read only for
    # that tenant or internal tokens), audience meridian-api, no meridian:internal.
    router_tenant_mapper = dict(tenant_mapper, config=dict(tenant_mapper["config"], **{"claim.value": PLATFORM_TENANT}))
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
    # Agent tokens: the agent from the admin-managed agent_id attribute (never editable by the user), the tenant, and
    # auth_time / acr / sub / preferred_username like customer tokens (no customer_id mapper).
    agent_mappers = [attribute_mapper("agent_id", "agent_id")] + [m for m in customer_mappers
                                                                  if m["name"] != "customer_id"]
    # Business portal tokens: the business and the portal user from admin-managed attributes (never editable by the
    # user), the tenant, and auth_time / acr / sub / preferred_username for step-up and audit.
    business_mappers = [attribute_mapper("business_customer_id", "business_customer_id"),
                        attribute_mapper("business_user_id", "business_user_id")] + [
        m for m in customer_mappers if m["name"] != "customer_id"]
    # Merchant app tokens: the merchant and the app user from admin-managed attributes (never editable by the user),
    # the tenant, and auth_time / acr / sub / preferred_username for step-up and audit.
    merchant_mappers = [attribute_mapper("merchant_id", "merchant_id"),
                        attribute_mapper("merchant_user_id", "merchant_user_id")] + [
        m for m in customer_mappers if m["name"] != "customer_id"]
    read_scopes = [n for n in scope_names if n.endswith(":read")]
    console_scopes = scope_names
    portal_scopes = ["platform:read", "platform:write"]
    realm = {
        "realm": "meridian",
        "enabled": True,
        "sslRequired": "external",
        "accessTokenLifespan": 900,
        "ssoSessionIdleTimeout": 1800,
        "bruteForceProtected": True,
        "passwordPolicy": "length(12) and upperCase(1) and digits(1) and specialChars(1) and notUsername",
        "clientScopes": scopes,
        "roles": {"realm": [
            {"name": "meridian-admin", "description": "Tenant superuser: may receive meridian:admin"},
            {"name": "platform-operator", "description": "Platform operator: may receive platform:operator"}]},
        # Role-gated scopes: added to a token only when the user holds the role (service accounts get them by role too)
        "scopeMappings": [{"clientScope": "meridian:admin", "roles": ["meridian-admin"]},
                          {"clientScope": operator, "roles": ["platform-operator"]}],
        "components": {"org.keycloak.userprofile.UserProfileProvider": [{
            "providerId": "declarative-user-profile", "subComponents": {},
            "config": {"kc.user.profile.config": [json.dumps(user_profile(), separators=(",", ":"))]}}]},
        "clients": [
            staff_app("meridian-console", "Meridian ops console", ["http://localhost:13100"], console_scopes,
                      [operator] + privileged),
            staff_app("meridian-developer-portal", "Meridian developer portal", DEV_PORTAL_ORIGINS, portal_scopes),
            {"clientId": "meridian-service", "name": "Meridian service account", "publicClient": False,
             "secret": "meridian-service-secret", "serviceAccountsEnabled": True,
             "standardFlowEnabled": False, "directAccessGrantsEnabled": False,
             "protocolMappers": [tenant_mapper, audience_mapper()],
             "defaultClientScopes": scope_names + ["meridian:internal", operator]},
            # The cell router (platform/cell-router): reads tenant routing and cells from the registry, nothing else.
            {"clientId": "meridian-cell-router", "name": "Meridian cell router", "publicClient": False,
             "secret": "meridian-cell-router-secret", "serviceAccountsEnabled": True,
             "standardFlowEnabled": False, "directAccessGrantsEnabled": False, "fullScopeAllowed": False,
             "protocolMappers": [router_tenant_mapper, audience_mapper()],
             "defaultClientScopes": [ROUTING_SCOPE], "optionalClientScopes": []},
            {"clientId": "meridian-readonly", "name": "Read-only integration", "publicClient": False,
             "secret": "meridian-readonly-secret", "serviceAccountsEnabled": True,
             "standardFlowEnabled": False, "directAccessGrantsEnabled": False,
             "protocolMappers": [tenant_mapper, audience_mapper()], "defaultClientScopes": read_scopes},
            {"clientId": "meridian-customer-web", "name": "Meridian customer web banking", "publicClient": True,
             "standardFlowEnabled": True, "implicitFlowEnabled": False, "serviceAccountsEnabled": False,
             "directAccessGrantsEnabled": True,           # local testing only (scripts obtaining a customer token)
             "fullScopeAllowed": False,
             "redirectUris": [o + "/*" for o in CUSTOMER_WEB_ORIGINS], "webOrigins": CUSTOMER_WEB_ORIGINS,
             "attributes": {"pkce.code.challenge.method": "S256", "post.logout.redirect.uris": "+"},
             "protocolMappers": customer_mappers + [audience_mapper()],
             "defaultClientScopes": ["profile", "email", "web-origins"] + customer_scopes,
             "optionalClientScopes": []},
            {"clientId": "meridian-agent-app", "name": "Meridian agent outlet app", "publicClient": True,
             "standardFlowEnabled": True, "implicitFlowEnabled": False, "serviceAccountsEnabled": False,
             "directAccessGrantsEnabled": True,           # local testing only (scripts obtaining an agent token)
             "fullScopeAllowed": False,
             "redirectUris": [o + "/*" for o in AGENT_APP_ORIGINS], "webOrigins": AGENT_APP_ORIGINS,
             "attributes": {"pkce.code.challenge.method": "S256", "post.logout.redirect.uris": "+"},
             "protocolMappers": agent_mappers + [audience_mapper()],
             "defaultClientScopes": ["profile", "email", "web-origins"] + agent_scopes,
             "optionalClientScopes": []},
            {"clientId": "meridian-business-portal", "name": "Meridian business banking portal", "publicClient": True,
             "standardFlowEnabled": True, "implicitFlowEnabled": False, "serviceAccountsEnabled": False,
             "directAccessGrantsEnabled": False, "fullScopeAllowed": False,
             "redirectUris": [o + "/*" for o in BUSINESS_PORTAL_ORIGINS], "webOrigins": BUSINESS_PORTAL_ORIGINS,
             "attributes": {"pkce.code.challenge.method": "S256", "post.logout.redirect.uris": "+"},
             "protocolMappers": business_mappers + [audience_mapper()],
             "defaultClientScopes": ["profile", "email", "web-origins"] + business_scopes,
             "optionalClientScopes": []},
            {"clientId": "meridian-merchant-app", "name": "Meridian merchant app", "publicClient": True,
             "standardFlowEnabled": True, "implicitFlowEnabled": False, "serviceAccountsEnabled": False,
             "directAccessGrantsEnabled": False, "fullScopeAllowed": False,
             "redirectUris": [o + "/*" for o in MERCHANT_APP_ORIGINS], "webOrigins": MERCHANT_APP_ORIGINS,
             "attributes": {"pkce.code.challenge.method": "S256", "post.logout.redirect.uris": "+"},
             "protocolMappers": merchant_mappers + [audience_mapper()],
             "defaultClientScopes": ["profile", "email", "web-origins"] + merchant_scopes,
             "optionalClientScopes": []},
        ],
        "users": [
            {"username": "ops.admin", "enabled": True, "email": "ops.admin@meridian.local", "emailVerified": True,
             "firstName": "Ops", "lastName": "Admin", "attributes": {"tenant_id": [DEMO_TENANT]},
             "realmRoles": ["meridian-admin"],
             "credentials": [{"type": "password", "value": "Meridian#Admin2026", "temporary": False}]},
            {"username": "platform.operator", "enabled": True, "email": "platform.operator@meridian.local",
             "emailVerified": True, "firstName": "Platform", "lastName": "Operator",
             "attributes": {"tenant_id": [PLATFORM_TENANT]}, "realmRoles": ["platform-operator"],
             "credentials": [{"type": "password", "value": "Meridian#Operator2026", "temporary": False}]},
            {"username": "service-account-meridian-service", "enabled": True, "serviceAccountClientId": "meridian-service",
             "realmRoles": ["meridian-admin", "platform-operator"]},
            {"username": "demo.customer", "enabled": True, "email": "demo.customer@meridian.local",
             "emailVerified": True, "firstName": "Demo", "lastName": "Customer",
             "attributes": {"customer_id": [DEMO_CUSTOMER], "tenant_id": [DEMO_TENANT]},
             "credentials": [{"type": "password", "value": "Meridian#Customer2026", "temporary": False}]},
            {"username": "demo.agent", "enabled": True, "email": "demo.agent@meridian.local",
             "emailVerified": True, "firstName": "Demo", "lastName": "Agent",
             "attributes": {"agent_id": [DEMO_AGENT], "tenant_id": [DEMO_TENANT]},
             "credentials": [{"type": "password", "value": "Meridian#Agent2026", "temporary": False}]},
            {"username": "demo.business.admin", "enabled": True, "email": "demo.business.admin@meridian.local",
             "emailVerified": True, "firstName": "Demo", "lastName": "Business Admin",
             "attributes": {"business_customer_id": [DEMO_BUSINESS], "business_user_id": [DEMO_BUSINESS_ADMIN],
                            "tenant_id": [DEMO_TENANT]},
             "credentials": [{"type": "password", "value": "Meridian#Business2026", "temporary": False}]},
            {"username": "demo.business.approver", "enabled": True, "email": "demo.business.approver@meridian.local",
             "emailVerified": True, "firstName": "Demo", "lastName": "Business Approver",
             "attributes": {"business_customer_id": [DEMO_BUSINESS], "business_user_id": [DEMO_BUSINESS_APPROVER],
                            "tenant_id": [DEMO_TENANT]},
             "credentials": [{"type": "password", "value": "Meridian#Approver2026", "temporary": False}]},
            {"username": "demo.merchant.owner", "enabled": True, "email": "demo.merchant.owner@meridian.local",
             "emailVerified": True, "firstName": "Demo", "lastName": "Merchant Owner",
             "attributes": {"merchant_id": [DEMO_MERCHANT], "merchant_user_id": [DEMO_MERCHANT_OWNER],
                            "tenant_id": [DEMO_TENANT]},
             "credentials": [{"type": "password", "value": "Meridian#Merchant2026", "temporary": False}]},
            {"username": "demo.merchant.cashier", "enabled": True, "email": "demo.merchant.cashier@meridian.local",
             "emailVerified": True, "firstName": "Demo", "lastName": "Merchant Cashier",
             "attributes": {"merchant_id": [DEMO_MERCHANT], "merchant_user_id": [DEMO_MERCHANT_CASHIER],
                            "tenant_id": [DEMO_TENANT]},
             "credentials": [{"type": "password", "value": "Meridian#Cashier2026", "temporary": False}]},
        ],
    }
    out = ROOT / "infra" / "local" / "keycloak" / "meridian-realm.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(realm, indent=2), encoding="utf-8", newline="\n")
    print(f"realm with {len(scope_names)} staff scopes, {len(channel_scopes)} channel scope(s), "
          f"{len(realm['clients'])} clients, {len(realm['users'])} users -> {out.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
