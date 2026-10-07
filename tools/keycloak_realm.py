#!/usr/bin/env python3
"""Generates the local Keycloak realm (infra/local/keycloak/meridian-realm.json) from the spec.

Staff scopes: one client scope per domain and access level (<domain>:read, <domain>:write, <domain>:*) plus
meridian:admin (the tenant's superuser) and platform:operator (platform-global writes, tenant registry, cross-tenant
jobs; SEC-15). channels:admin is the staff scope of the channel BFFs' operator endpoints (e.g. provisioning a
business's first portal ADMIN, /v1/business-provisioning): console and service clients only, never a channel client. Customer scopes: a domain that declares `scope:` in its spec (channels -> channels:customer) gets
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
  meridian-agent-mobile      public, authorization code + PKCE (native agent app, mobile/agent_app): same scope and
                             claims as meridian-agent-app; redirect io.meridian.agent:/oauth2redirect (custom scheme),
                             no web origins, no password grant
  meridian-business-portal   public, authorization code + PKCE (business banking portal); only channels:business plus
                             openid/profile; tokens carry business_customer_id, business_user_id and tenant_id from
                             admin-only user attributes and auth_time / acr (approvals and administration need a recent
                             authentication); no password grant
  meridian-business-m2m-demo confidential, client credentials (a business's machine-to-machine API client); only
                             channels:business; hardcoded claims business_customer_id, business_user_id (the
                             business's TECHNICAL portal user, created with the API client in the portal) and m2m=true.
                             Real business clients are provisioned the same way from identity-service api_client rows
                             (business_customer_id / business_user_id tags; Keycloak clientId = identity client_id)
  meridian-merchant-app      public, authorization code + PKCE (merchant app PWA); only channels:merchant plus
                             openid/profile; tokens carry merchant_id, merchant_user_id and tenant_id from admin-only
                             user attributes and auth_time / acr (refund approvals, payout account changes and
                             administration need a recent authentication); no password grant
  meridian-console / meridian-developer-portal also use the staff browser flow "meridian staff browser" (GAP-13):
                             password plus a second factor (WebAuthn security key when registered, else a TOTP app;
                             TOTP set-up forced for a user without one), acr.loa.map {"1": 1, "2": 2}, level 2
                             requested and required (default.acr.values / minimum.acr.value 2); tokens carry acr, amr,
                             auth_time. Services require acr 2 for meridian:admin / platform:operator
  svc-<service>              GAP-16: one confidential client per service (client credentials): meridian:internal plus
                             <domain>:write of every service it calls and the special scopes its call paths need
                             (tools/service_access.py); azp proves the calling service (libs/meridian-web TenantFilter).
                             Local secret svc-<service>-secret; deployments: Secret meridian-<service>-client
  meridian-service           LEGACY shared service client (migration only): every staff scope + meridian:internal +
                             platform:operator; its X-Meridian-Service header is believed only by services that list it
                             in meridian.security.legacy-service-clients
  meridian-audit-events      GAP-06: confidential, client credentials, realm-management view-events only (audit-service
                             copies user and admin events into the audit trail); no Meridian scope or audience
Realm events (GAP-06): user events (USER_EVENT_TYPES) and admin events with representation stored for 90 days;
listeners jboss-logging and metrics-listener (keycloak_user_events_total, needs --event-metrics-user-enabled).
  meridian-cell-router       confidential, client credentials, only platform:routing-read (tenant routing and cell
                             reads); tokens carry the platform-operator tenant, no meridian:internal
  meridian-readonly          confidential, client credentials, read scopes only (authorisation tests)
  meridian-customer-web      public, authorization code + PKCE (customer web banking); only the customer scope plus
                             openid/profile; tokens carry customer_id and tenant_id from user attributes (admin-managed,
                             not editable by the user), auth_time / acr / amr / sid for step-up and sessions; password
                             grant for local testing (password-grant tokens carry no auth_time, so step-up operations
                             refuse them). Browser flow override "meridian customer browser" with Levels of
                             Authentication (acr.loa.map {"1": 1, "2": 2}): acr 1 = password, acr 2 = password plus a
                             second factor (WebAuthn security key / passkey, or a TOTP authenticator app). Step-up:
                             authorization request with acr_values=2 (and max_age); the second factor is asked again
                             when the level-2 authentication is older than CUSTOMER_LOA2_MAX_AGE. Enrolment through
                             application-initiated actions (kc_action=CONFIGURE_TOTP / webauthn-register /
                             webauthn-register-passwordless; removal kc_action=delete_credential:<id>)
  meridian-customer-mobile   public, authorization code + PKCE (native customer app, mobile/customer_app): same scope,
                             claims, LoA levels and browser flow as meridian-customer-web (step-up with acr_values=2 and
                             max_age); redirect io.meridian.customer:/oauth2redirect (custom scheme), no web origins,
                             no password grant; refresh tokens are kept in the platform keystore behind biometrics
  meridian-customer-sessions confidential, client credentials, used only by customer-channel-service to list and end a
                             customer's own Keycloak sessions and list their sign-in factors (admin REST API, always for
                             the sub of the customer's token). Service-account roles realm-management view-users and
                             manage-users (Keycloak has no narrower role for user sessions); no Meridian scope, no
                             meridian-api audience, so its tokens are useless against the Meridian APIs
Users:
  ops.admin          staff demo user (console), demo tenant, realm role meridian-admin; demo TOTP authenticator
                     (secret DEMO_STAFF_TOTP_SECRET) for the staff second factor
  platform.operator  platform operator (console), platform-operator tenant, realm role platform-operator; same demo TOTP
  demo.customer      customer demo user (customer-web); set its customer_id attribute to a customer of the demo tenant.
                     Has a demo TOTP authenticator (secret DEMO_CUSTOMER_TOTP_SECRET, base32 in the guide) so acr 2
                     can be reached locally; enrol more factors in the app (Security)
  demo.agent         agent demo user (agent-app, Meridian#Agent2026); set its agent_id attribute to an ACTIVE agent of
                     the demo tenant
  demo.business.admin     business portal admin (Meridian#Business2026); business_customer_id = a business customer
                          (customer_type not INDIVIDUAL) of the demo tenant. Dev mode: the first user who signs in for
                          a business with no portal users becomes its ADMIN (meridian.business.bootstrap-first-admin,
                          off by default in jwt mode); jwt mode: bank staff provision the first ADMIN (console / POST
                          /v1/business-provisioning/admins, channels:admin) and set the returned id here.
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
# Native mobile apps (mobile/customer_app, mobile/agent_app; Flutter + AppAuth): custom-scheme redirect URIs (RFC 8252
# private-use URI scheme = reverse domain of the app id), no web origins, PKCE S256, no password grant.
CUSTOMER_MOBILE_REDIRECTS = ["io.meridian.customer:/oauth2redirect"]
AGENT_MOBILE_REDIRECTS = ["io.meridian.agent:/oauth2redirect"]
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
# Staff scope of the channel BFFs' operator endpoints (spec `scopes:` overrides, e.g. business-provisioning).
CHANNELS_ADMIN_SCOPE = "channels:admin"
# The demo business's TECHNICAL portal user (placeholder until an API client is created in the portal).
DEMO_BUSINESS_TECHNICAL = "0199a000-0000-7000-8000-0000000b0a03"
DEMO_MERCHANT_OWNER = "0199a000-0000-7000-8000-0000000d0a01"
DEMO_MERCHANT_CASHIER = "0199a000-0000-7000-8000-0000000d0a02"
MERCHANT_APP_ORIGINS = ["http://localhost:13600"]
CUSTOMER_SCOPE = "channels:customer"
AGENT_SCOPE = "channels:agent"
BUSINESS_SCOPE = "channels:business"
MERCHANT_SCOPE = "channels:merchant"
# The cell router's only scope: tenant routing / cell reads in the registry (spec `scopes:` on tenant-service).
ROUTING_SCOPE = "platform:routing-read"
# Customer Levels of Authentication (acr): 1 = password, 2 = password + second factor. The second factor is asked
# again when a step-up (acr_values=2) comes more than CUSTOMER_LOA2_MAX_AGE seconds after the last one; the channel
# additionally checks auth_time (meridian.channels.step-up.max-age).
CUSTOMER_ACR_LOA_MAP = {"1": 1, "2": 2}
CUSTOMER_LOA1_MAX_AGE = 36000
CUSTOMER_LOA2_MAX_AGE = 300
CUSTOMER_BROWSER_FLOW = "meridian customer browser"
CUSTOMER_BROWSER_FLOW_ID = "0199a000-0000-7000-8000-00000000f101"   # fixed: the client override refers to it
# Local demo only: TOTP secret of demo.customer (Keycloak keeps the raw secret; authenticator apps use its base32).
DEMO_CUSTOMER_TOTP_SECRET = "MeridianDemoTotp2026"
CUSTOMER_SESSIONS_CLIENT = "meridian-customer-sessions"
# GAP-13: staff Levels of Authentication. Staff clients require level 2 (password + second factor) for every sign-in;
# the second factor is asked again for an authorisation more than STAFF_LOA2_MAX_AGE seconds after the last one.
STAFF_LOA1_MAX_AGE = 36000
STAFF_LOA2_MAX_AGE = 3600
STAFF_BROWSER_FLOW = "meridian staff browser"
STAFF_BROWSER_FLOW_ID = "0199a000-0000-7000-8000-00000000f102"   # fixed: the client overrides refer to it
STAFF_ACR_LOA_MAP = {"1": 1, "2": 2}
# Local demo only: TOTP secret of the demo staff users ops.admin / platform.operator (raw secret; base32 in the guide).
DEMO_STAFF_TOTP_SECRET = "MeridianStaffTotp2026"
# GAP-06: realm events. Stored for EVENTS_EXPIRATION seconds in Keycloak (audit-service copies them into the audit
# trail, which keeps them per the audit retention policy); CODE_TO_TOKEN successes are left out (one per sign-in).
EVENTS_EXPIRATION = 90 * 24 * 3600
USER_EVENT_TYPES = [
    "LOGIN", "LOGIN_ERROR", "LOGOUT", "LOGOUT_ERROR", "CODE_TO_TOKEN_ERROR", "CLIENT_LOGIN", "CLIENT_LOGIN_ERROR",
    "REFRESH_TOKEN_ERROR", "UPDATE_PASSWORD", "UPDATE_PASSWORD_ERROR", "UPDATE_CREDENTIAL", "UPDATE_CREDENTIAL_ERROR",
    "REMOVE_CREDENTIAL", "REMOVE_CREDENTIAL_ERROR", "UPDATE_TOTP", "REMOVE_TOTP", "RESET_PASSWORD",
    "RESET_PASSWORD_ERROR", "SEND_RESET_PASSWORD", "USER_DISABLED_BY_PERMANENT_LOCKOUT",
    "USER_DISABLED_BY_TEMPORARY_LOCKOUT", "IMPERSONATE", "IMPERSONATE_ERROR", "TOKEN_EXCHANGE", "TOKEN_EXCHANGE_ERROR",
    "GRANT_CONSENT", "REVOKE_GRANT", "UPDATE_EMAIL", "DELETE_ACCOUNT",
]
# GAP-06: read-only client of audit-service (realm-management view-events: user and admin events, nothing else).
AUDIT_EVENTS_CLIENT = "meridian-audit-events"


def execution(authenticator: str, requirement: str, priority: int, config: str | None = None) -> dict:
    e = {"authenticator": authenticator, "authenticatorFlow": False, "requirement": requirement,
         "priority": priority, "autheticatorFlow": False, "userSetupAllowed": False}
    if config:
        e["authenticatorConfig"] = config
    return e


def sub_flow(alias: str, requirement: str, priority: int) -> dict:
    return {"flowAlias": alias, "authenticatorFlow": True, "requirement": requirement, "priority": priority,
            "autheticatorFlow": True, "userSetupAllowed": False}


def flow(alias: str, description: str, executions: list, top: bool = False, flow_id: str | None = None) -> dict:
    f = {"alias": alias, "description": description, "providerId": "basic-flow", "topLevel": top, "builtIn": False,
         "authenticationExecutions": executions}
    if flow_id:
        f["id"] = flow_id
    return f


def customer_browser_flows() -> tuple[list, list]:
    """Browser flow of the customer client with Levels of Authentication (Keycloak step-up).

    cookie (SSO; satisfies only the level the session already reached)
    | forms
        level 1 (Condition - Level of Authentication 1): username + password            -> acr 1, amr pwd
        level 2 (Condition - Level of Authentication 2): one second factor              -> acr 2, amr hwk / otp
            WebAuthn (security key or passkey registered as 2FA) | WebAuthn passwordless passkey | TOTP app
    Each authenticator carries an AMR reference (default.reference.value, RFC 8176) for the amr claim and how long
    it stays in amr after it ran (default.reference.maxAge, seconds; without it Keycloak drops the reference as soon
    as the second in which the authenticator ran is over).
    """
    def amr(value: str, max_age: int) -> dict:
        return {"default.reference.value": value, "default.reference.maxAge": str(max_age)}

    configs = [
        {"alias": "meridian-customer-loa1", "config": {"loa-condition-level": "1",
                                                       "loa-max-age": str(CUSTOMER_LOA1_MAX_AGE)}},
        {"alias": "meridian-customer-loa2", "config": {"loa-condition-level": "2",
                                                       "loa-max-age": str(CUSTOMER_LOA2_MAX_AGE)}},
        {"alias": "meridian-customer-amr-pwd", "config": amr("pwd", CUSTOMER_LOA1_MAX_AGE)},
        {"alias": "meridian-customer-amr-otp", "config": amr("otp", CUSTOMER_LOA2_MAX_AGE)},
        {"alias": "meridian-customer-amr-hwk", "config": amr("hwk", CUSTOMER_LOA2_MAX_AGE)},
        {"alias": "meridian-customer-amr-passkey", "config": amr("hwk", CUSTOMER_LOA2_MAX_AGE)},
    ]
    flows = [
        flow(CUSTOMER_BROWSER_FLOW, "Customer sign-in with Levels of Authentication: acr 1 password, acr 2 password "
             "plus WebAuthn or TOTP (step-up with acr_values=2)",
             [execution("auth-cookie", "ALTERNATIVE", 10),
              sub_flow("meridian customer forms", "ALTERNATIVE", 20)], top=True, flow_id=CUSTOMER_BROWSER_FLOW_ID),
        flow("meridian customer forms", "Level 1 and level 2 sub-flows",
             [sub_flow("meridian customer level 1", "CONDITIONAL", 10),
              sub_flow("meridian customer level 2", "CONDITIONAL", 20)]),
        flow("meridian customer level 1", "acr 1: username and password",
             [execution("conditional-level-of-authentication", "REQUIRED", 10, "meridian-customer-loa1"),
              execution("auth-username-password-form", "REQUIRED", 20, "meridian-customer-amr-pwd")]),
        flow("meridian customer level 2", "acr 2: a second factor (asked again after the level-2 max age)",
             [execution("conditional-level-of-authentication", "REQUIRED", 10, "meridian-customer-loa2"),
              sub_flow("meridian customer second factor", "REQUIRED", 20)]),
        flow("meridian customer second factor", "WebAuthn (2FA), passkey or TOTP authenticator app",
             [execution("webauthn-authenticator", "ALTERNATIVE", 10, "meridian-customer-amr-hwk"),
              execution("webauthn-authenticator-passwordless", "ALTERNATIVE", 20, "meridian-customer-amr-passkey"),
              execution("auth-otp-form", "ALTERNATIVE", 30, "meridian-customer-amr-otp")]),
    ]
    return flows, configs


def staff_browser_flows() -> tuple[list, list]:
    """GAP-13: browser flow of the staff apps (console, developer portal) with Levels of Authentication.

    cookie (SSO; satisfies only the level the session already reached)
    | forms
        level 1 (Condition - Level of Authentication 1): username + password                    -> acr 1, amr pwd
        level 2 (Condition - Level of Authentication 2): second factor                          -> acr 2, amr hwk / otp
            security key   (Condition - user configured): WebAuthn, when the user registered one
            authenticator  (Condition - sub-flow "security key" not executed): TOTP form; REQUIRED, so a user without
                           any factor must set up an authenticator app before the sign-in completes (forced enrolment)
    The staff clients request and require level 2 (default.acr.values / minimum.acr.value = 2): every staff sign-in is
    password plus a second factor. Services additionally require acr 2 for meridian:admin / platform:operator
    (meridian.security.privileged-acr), so a token from any weaker path is refused for privileged operations. The second
    factor is asked again when an authorisation comes more than STAFF_LOA2_MAX_AGE after the last one.
    """
    def amr(value: str, max_age: int) -> dict:
        return {"default.reference.value": value, "default.reference.maxAge": str(max_age)}

    configs = [
        {"alias": "meridian-staff-loa1", "config": {"loa-condition-level": "1", "loa-max-age": str(STAFF_LOA1_MAX_AGE)}},
        {"alias": "meridian-staff-loa2", "config": {"loa-condition-level": "2", "loa-max-age": str(STAFF_LOA2_MAX_AGE)}},
        {"alias": "meridian-staff-amr-pwd", "config": amr("pwd", STAFF_LOA1_MAX_AGE)},
        {"alias": "meridian-staff-amr-otp", "config": amr("otp", STAFF_LOA2_MAX_AGE)},
        {"alias": "meridian-staff-amr-hwk", "config": amr("hwk", STAFF_LOA2_MAX_AGE)},
        {"alias": "meridian-staff-no-security-key", "config": {"flow_to_check": "meridian staff security key",
                                                              "check_result": "not-executed"}},
    ]
    flows = [
        flow(STAFF_BROWSER_FLOW, "Staff sign-in (console, developer portal): password plus a second factor (acr 2); "
             "WebAuthn security key or TOTP authenticator app, enrolment forced on first sign-in",
             [execution("auth-cookie", "ALTERNATIVE", 10),
              sub_flow("meridian staff forms", "ALTERNATIVE", 20)], top=True, flow_id=STAFF_BROWSER_FLOW_ID),
        flow("meridian staff forms", "Level 1 and level 2 sub-flows",
             [sub_flow("meridian staff level 1", "CONDITIONAL", 10),
              sub_flow("meridian staff level 2", "CONDITIONAL", 20)]),
        flow("meridian staff level 1", "acr 1: username and password",
             [execution("conditional-level-of-authentication", "REQUIRED", 10, "meridian-staff-loa1"),
              execution("auth-username-password-form", "REQUIRED", 20, "meridian-staff-amr-pwd")]),
        flow("meridian staff level 2", "acr 2: a second factor (asked again after the level-2 max age)",
             [execution("conditional-level-of-authentication", "REQUIRED", 10, "meridian-staff-loa2"),
              sub_flow("meridian staff second factor", "REQUIRED", 20)]),
        flow("meridian staff second factor", "WebAuthn security key when registered, otherwise the TOTP app",
             [sub_flow("meridian staff security key", "CONDITIONAL", 10),
              sub_flow("meridian staff authenticator app", "CONDITIONAL", 20)]),
        flow("meridian staff security key", "WebAuthn (2FA) for users who registered a security key",
             [execution("conditional-user-configured", "REQUIRED", 10),
              execution("webauthn-authenticator", "REQUIRED", 20, "meridian-staff-amr-hwk")]),
        flow("meridian staff authenticator app", "TOTP for everyone else (set-up forced when missing)",
             [execution("conditional-sub-flow-executed", "REQUIRED", 10, "meridian-staff-no-security-key"),
              execution("auth-otp-form", "REQUIRED", 20, "meridian-staff-amr-otp")]),
    ]
    return flows, configs


def realm_events() -> dict:
    """GAP-06: user events (sign-ins, failures, factor changes, lockouts, client-credential logins) and admin events with
    representations are stored in the realm (eventsExpiration) and read by audit-service with the read-only
    meridian-audit-events client (polling the admin events API into the audit trail); metrics-listener feeds the
    keycloak_user_events_total metric (J4 login SLI, needs --event-metrics-user-enabled); jboss-logging writes them to
    Keycloak's log (shipped with the other container logs)."""
    return {
        "eventsEnabled": True,
        "eventsExpiration": EVENTS_EXPIRATION,
        "eventsListeners": ["jboss-logging", "metrics-listener"],
        "enabledEventTypes": USER_EVENT_TYPES,
        "adminEventsEnabled": True,
        "adminEventsDetailsEnabled": True,
    }


def service_clients(scope_names: set, tenant_mapper: dict) -> tuple[list, list]:
    """GAP-16: one confidential client per service (svc-<service>, client credentials), scopes from
    tools/service_access.py; the secret here is a local development value (deployments: Secret
    meridian-<service>-client, keys client-id / client-secret). Service accounts of clients that need platform:operator
    get the platform-operator realm role (the scope is role-gated)."""
    import service_access
    clients, users = [], []
    realm_scopes = set(scope_names)
    for s in service_access.manifest():
        name = s["name"]
        scopes = service_access.http_scopes(name, realm_scopes)
        clients.append({
            "clientId": service_access.client_id(name), "name": f"{name} (service identity)",
            "description": "GAP-16 per-service identity: client credentials, azp proves the calling service",
            "publicClient": False, "secret": f"{service_access.client_id(name)}-secret",
            "serviceAccountsEnabled": True, "standardFlowEnabled": False, "directAccessGrantsEnabled": False,
            "implicitFlowEnabled": False, "fullScopeAllowed": False,
            "protocolMappers": [tenant_mapper, audience_mapper()],
            "defaultClientScopes": scopes, "optionalClientScopes": []})
        if "platform:operator" in scopes:
            users.append({"username": f"service-account-{service_access.client_id(name)}", "enabled": True,
                          "serviceAccountClientId": service_access.client_id(name),
                          "realmRoles": ["platform-operator"]})
    return clients, users


def oidc_standard_scopes() -> list:
    """The OIDC scopes the web apps request (openid profile email) plus web-origins. An imported realm that lists its
    own clientScopes gets none of Keycloak's built-in scopes, and an authorization request naming a missing scope
    fails with invalid_scope."""
    def prop(name, attribute, claim, json_type="String"):
        return {"name": name, "protocol": "openid-connect", "protocolMapper": "oidc-usermodel-property-mapper",
                "config": {"user.attribute": attribute, "claim.name": claim, "jsonType.label": json_type,
                           "id.token.claim": "true", "access.token.claim": "true", "userinfo.token.claim": "true",
                           "introspection.token.claim": "true"}}
    shown = {"include.in.token.scope": "true", "display.on.consent.screen": "true"}
    return [
        {"name": "profile", "protocol": "openid-connect", "description": "OpenID Connect built-in scope: profile",
         "attributes": shown, "protocolMappers": [
             prop("username", "username", "preferred_username"), prop("given name", "firstName", "given_name"),
             prop("family name", "lastName", "family_name"),
             {"name": "full name", "protocol": "openid-connect", "protocolMapper": "oidc-full-name-mapper",
              "config": {"id.token.claim": "true", "access.token.claim": "true", "userinfo.token.claim": "true",
                         "introspection.token.claim": "true"}}]},
        {"name": "email", "protocol": "openid-connect", "description": "OpenID Connect built-in scope: email",
         "attributes": shown, "protocolMappers": [
             prop("email", "email", "email"), prop("email verified", "emailVerified", "email_verified", "boolean")]},
        {"name": "web-origins", "protocol": "openid-connect", "description": "Allowed web origins (CORS)",
         "attributes": {"include.in.token.scope": "false", "display.on.consent.screen": "false"},
         "protocolMappers": [{"name": "allowed web origins", "protocol": "openid-connect",
                              "protocolMapper": "oidc-allowed-origins-mapper",
                              "config": {"access.token.claim": "true", "introspection.token.claim": "true"}}]},
    ]


def required_actions() -> list:
    """Enrolment / removal of factors (also started by the app as application-initiated actions, kc_action)."""
    actions = [("CONFIGURE_TOTP", "Configure OTP"), ("webauthn-register", "Webauthn Register"),
               ("webauthn-register-passwordless", "Webauthn Register Passwordless"),
               ("delete_credential", "Delete Credential"), ("UPDATE_PASSWORD", "Update Password"),
               ("VERIFY_EMAIL", "Verify Email"), ("UPDATE_PROFILE", "Update Profile"),
               ("TERMS_AND_CONDITIONS", "Terms and Conditions"), ("VERIFY_PROFILE", "Verify Profile"),
               ("update_user_locale", "Update User Locale"), ("CONFIGURE_RECOVERY_AUTHN_CODES",
                                                               "Recovery Authentication Codes")]
    off = {"TERMS_AND_CONDITIONS", "CONFIGURE_RECOVERY_AUTHN_CODES"}
    return [{"alias": a, "name": n, "providerId": a, "enabled": a not in off, "defaultAction": False,
             "priority": 10 * (i + 1), "config": {}} for i, (a, n) in enumerate(actions)]


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


def hardcoded_claim(name: str, value: str, json_type: str = "String") -> dict:
    """A fixed claim of every token of a client (never from a user attribute)."""
    return {"name": name, "protocol": "openid-connect", "protocolMapper": "oidc-hardcoded-claim-mapper",
            "config": {"claim.name": name, "claim.value": value, "jsonType.label": json_type,
                       "access.token.claim": "true", "id.token.claim": "false", "userinfo.token.claim": "false",
                       "introspection.token.claim": "true"}}


def audience_mapper() -> dict:
    """SEC-14: every Meridian token is issued for the Meridian API (services validate aud)."""
    return {"name": "audience", "protocol": "openid-connect", "protocolMapper": "oidc-audience-mapper",
            "config": {"included.custom.audience": AUDIENCE, "access.token.claim": "true",
                       "id.token.claim": "false", "introspection.token.claim": "true"}}


def session_mappers() -> list:
    """auth_time / acr / amr / sub of the sign-in (step-up checks, privileged-scope acr, session revocation)."""
    return [
        {"name": "auth_time", "protocol": "openid-connect", "protocolMapper": "oidc-usersessionmodel-note-mapper",
         "config": {"user.session.note": "AUTH_TIME", "claim.name": "auth_time", "jsonType.label": "long",
                    "access.token.claim": "true", "id.token.claim": "true", "introspection.token.claim": "true"}},
        {"name": "acr", "protocol": "openid-connect", "protocolMapper": "oidc-acr-mapper",
         "config": {"access.token.claim": "true", "id.token.claim": "true", "introspection.token.claim": "true"}},
        {"name": "amr", "protocol": "openid-connect", "protocolMapper": "oidc-amr-mapper",
         "config": {"access.token.claim": "true", "id.token.claim": "true", "introspection.token.claim": "true"}},
        {"name": "sub", "protocol": "openid-connect", "protocolMapper": "oidc-sub-mapper",
         "config": {"access.token.claim": "true", "introspection.token.claim": "true"}},
    ]


def staff_app(client_id: str, name: str, origins: list, scopes: list, optional: list | None = None) -> dict:
    """Public staff web app: authorization code + PKCE only, tenant from the user's tenant_id attribute. GAP-13: the
    staff browser flow (password + second factor), level 2 requested and required for every sign-in; tokens carry
    acr / amr / auth_time / sub."""
    return {"clientId": client_id, "name": name, "publicClient": True,
            "standardFlowEnabled": True, "implicitFlowEnabled": False, "directAccessGrantsEnabled": False,
            "serviceAccountsEnabled": False, "fullScopeAllowed": False,
            "redirectUris": [o + "/*" for o in origins], "webOrigins": origins,
            "attributes": {"pkce.code.challenge.method": "S256", "post.logout.redirect.uris": "+",
                           "acr.loa.map": json.dumps(STAFF_ACR_LOA_MAP, separators=(",", ":")),
                           "default.acr.values": "2", "minimum.acr.value": "2"},
            "authenticationFlowBindingOverrides": {"browser": STAFF_BROWSER_FLOW_ID},
            "protocolMappers": [attribute_mapper("tenant_id", "tenant_id"), audience_mapper()] + session_mappers(),
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
              for n in scope_names + [CHANNELS_ADMIN_SCOPE, "meridian:internal", operator, ROUTING_SCOPE]
              + channel_scopes + privileged] + oidc_standard_scopes()
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
        {"name": "amr", "protocol": "openid-connect", "protocolMapper": "oidc-amr-mapper",
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
    console_scopes = scope_names + [CHANNELS_ADMIN_SCOPE]
    portal_scopes = ["platform:read", "platform:write"]
    customer_flows, customer_flow_configs = customer_browser_flows()
    staff_flows, staff_flow_configs = staff_browser_flows()
    svc_clients, svc_users = service_clients(set(scope_names + [CHANNELS_ADMIN_SCOPE, operator, ROUTING_SCOPE]),
                                             tenant_mapper)
    # MR-GAME-DAY platform identities (not in the services manifest): the data mover (data-lifecycle move API of
    # every service, /confirm-copy in tenant-service) and the operator tooling of tools/gameday (cell-role promotion,
    # outbox replay: /v1/internal/ops/*). Internal tokens of the platform-operator tenant; azp svc-<name> proves them.
    for name in ("data-mover", "platform-ops"):
        cid = f"svc-{name}"
        svc_clients.append({
            "clientId": cid, "name": f"{name} (platform identity)",
            "description": "MR-GAME-DAY platform identity: client credentials, azp proves the calling component",
            "publicClient": False, "secret": f"{cid}-secret", "serviceAccountsEnabled": True,
            "standardFlowEnabled": False, "directAccessGrantsEnabled": False, "implicitFlowEnabled": False,
            "fullScopeAllowed": False, "protocolMappers": [router_tenant_mapper, audience_mapper()],
            "defaultClientScopes": ["meridian:internal", operator], "optionalClientScopes": []})
        svc_users.append({"username": f"service-account-{cid}", "enabled": True, "serviceAccountClientId": cid,
                          "realmRoles": ["platform-operator"]})
    realm = {
        "realm": "meridian",
        "enabled": True,
        "sslRequired": "external",
        "accessTokenLifespan": 900,
        "ssoSessionIdleTimeout": 1800,
        "bruteForceProtected": True,
        "passwordPolicy": "length(12) and upperCase(1) and digits(1) and specialChars(1) and notUsername",
        # Customer second factors (Keycloak built-ins): TOTP authenticator apps and WebAuthn (security keys and
        # passkeys). The relying party id defaults to the host name Keycloak is served on.
        "otpPolicyType": "totp", "otpPolicyAlgorithm": "HmacSHA1", "otpPolicyDigits": 6, "otpPolicyPeriod": 30,
        "otpPolicyLookAheadWindow": 1, "otpPolicyCodeReusable": False,
        "webAuthnPolicyRpEntityName": "Meridian", "webAuthnPolicySignatureAlgorithms": ["ES256", "RS256"],
        "webAuthnPolicyUserVerificationRequirement": "preferred", "webAuthnPolicyRequireResidentKey": "not specified",
        "webAuthnPolicyAvoidSameAuthenticatorRegister": True,
        "webAuthnPolicyPasswordlessRpEntityName": "Meridian",
        "webAuthnPolicyPasswordlessSignatureAlgorithms": ["ES256", "RS256"],
        "webAuthnPolicyPasswordlessRequireResidentKey": "Yes",
        "webAuthnPolicyPasswordlessUserVerificationRequirement": "required",
        "webAuthnPolicyPasswordlessAvoidSameAuthenticatorRegister": True,
        "authenticationFlows": customer_flows + staff_flows,
        "authenticatorConfig": customer_flow_configs + staff_flow_configs,
        **realm_events(),
        "requiredActions": required_actions(),
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
            # Legacy shared service client (GAP-16 migration only: Helm global.auth.sharedServiceClient). Every service
            # now has its own client svc-<service> (below); receivers believe this client's X-Meridian-Service header
            # only while they list it in meridian.security.legacy-service-clients. Delete it once no release uses it.
            {"clientId": "meridian-service", "name": "Meridian service account (legacy shared, migration only)",
             "publicClient": False,
             "secret": "meridian-service-secret", "serviceAccountsEnabled": True,
             "standardFlowEnabled": False, "directAccessGrantsEnabled": False,
             "protocolMappers": [tenant_mapper, audience_mapper()],
             "defaultClientScopes": scope_names + [CHANNELS_ADMIN_SCOPE, "meridian:internal", operator]},
            # A business's machine-to-machine API client: client credentials, the business scope only, the business and
            # its TECHNICAL portal user as hardcoded claims and the m2m marker (business-channel-service accepts it only
            # for a TECHNICAL user whose client_id is this client).
            {"clientId": "meridian-business-m2m-demo", "name": "Demo business API client (machine-to-machine)",
             "publicClient": False, "secret": "meridian-business-m2m-secret", "serviceAccountsEnabled": True,
             "standardFlowEnabled": False, "directAccessGrantsEnabled": False, "fullScopeAllowed": False,
             "protocolMappers": [hardcoded_claim("business_customer_id", DEMO_BUSINESS),
                                 hardcoded_claim("business_user_id", DEMO_BUSINESS_TECHNICAL),
                                 hardcoded_claim("m2m", "true", "boolean"),
                                 hardcoded_claim("tenant_id", DEMO_TENANT), audience_mapper()],
             "defaultClientScopes": business_scopes, "optionalClientScopes": []},
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
             "attributes": {"pkce.code.challenge.method": "S256", "post.logout.redirect.uris": "+",
                            # acr values the app may request (acr_values=1 / 2) and the default level (password)
                            "acr.loa.map": json.dumps(CUSTOMER_ACR_LOA_MAP, separators=(",", ":")),
                            "default.acr.values": "1", "minimum.acr.value": "1"},
             "authenticationFlowBindingOverrides": {"browser": CUSTOMER_BROWSER_FLOW_ID},
             "protocolMappers": customer_mappers + [audience_mapper()],
             "defaultClientScopes": ["profile", "email", "web-origins"] + customer_scopes,
             "optionalClientScopes": []},
            # GAP-06: audit-service only: reads user and admin events (realm-management view-events). No Meridian scope
            # or audience, so its tokens are useless against the Meridian APIs.
            {"clientId": AUDIT_EVENTS_CLIENT, "name": "Keycloak events reader (audit-service)",
             "publicClient": False, "secret": "meridian-audit-events-secret", "serviceAccountsEnabled": True,
             "standardFlowEnabled": False, "directAccessGrantsEnabled": False, "implicitFlowEnabled": False,
             "fullScopeAllowed": True, "protocolMappers": [], "defaultClientScopes": [], "optionalClientScopes": []},
            # customer-channel-service only: list / end the signed-in customer's own Keycloak sessions and list their
            # sign-in factors (admin REST API for the token's sub). No Meridian scope or audience.
            {"clientId": CUSTOMER_SESSIONS_CLIENT, "name": "Customer sessions (customer-channel-service)",
             "publicClient": False, "secret": "meridian-customer-sessions-secret", "serviceAccountsEnabled": True,
             "standardFlowEnabled": False, "directAccessGrantsEnabled": False, "implicitFlowEnabled": False,
             "fullScopeAllowed": True, "protocolMappers": [], "defaultClientScopes": [], "optionalClientScopes": []},
            {"clientId": "meridian-agent-app", "name": "Meridian agent outlet app", "publicClient": True,
             "standardFlowEnabled": True, "implicitFlowEnabled": False, "serviceAccountsEnabled": False,
             "directAccessGrantsEnabled": True,           # local testing only (scripts obtaining an agent token)
             "fullScopeAllowed": False,
             "redirectUris": [o + "/*" for o in AGENT_APP_ORIGINS], "webOrigins": AGENT_APP_ORIGINS,
             "attributes": {"pkce.code.challenge.method": "S256", "post.logout.redirect.uris": "+"},
             "protocolMappers": agent_mappers + [audience_mapper()],
             "defaultClientScopes": ["profile", "email", "web-origins"] + agent_scopes,
             "optionalClientScopes": []},
            {"clientId": "meridian-customer-mobile", "name": "Meridian customer mobile app", "publicClient": True,
             "standardFlowEnabled": True, "implicitFlowEnabled": False, "serviceAccountsEnabled": False,
             "directAccessGrantsEnabled": False, "fullScopeAllowed": False,
             "redirectUris": CUSTOMER_MOBILE_REDIRECTS, "webOrigins": [],
             "attributes": {"pkce.code.challenge.method": "S256",
                            "post.logout.redirect.uris": "##".join(CUSTOMER_MOBILE_REDIRECTS),
                            "acr.loa.map": json.dumps(CUSTOMER_ACR_LOA_MAP, separators=(",", ":")),
                            "default.acr.values": "1", "minimum.acr.value": "1"},
             "authenticationFlowBindingOverrides": {"browser": CUSTOMER_BROWSER_FLOW_ID},
             "protocolMappers": customer_mappers + [audience_mapper()],
             "defaultClientScopes": ["profile", "email", "web-origins"] + customer_scopes,
             "optionalClientScopes": []},
            {"clientId": "meridian-agent-mobile", "name": "Meridian agent mobile app", "publicClient": True,
             "standardFlowEnabled": True, "implicitFlowEnabled": False, "serviceAccountsEnabled": False,
             "directAccessGrantsEnabled": False, "fullScopeAllowed": False,
             "redirectUris": AGENT_MOBILE_REDIRECTS, "webOrigins": [],
             "attributes": {"pkce.code.challenge.method": "S256",
                            "post.logout.redirect.uris": "##".join(AGENT_MOBILE_REDIRECTS)},
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
        ] + svc_clients,
        "users": [
            {"username": "ops.admin", "enabled": True, "email": "ops.admin@meridian.local", "emailVerified": True,
             "firstName": "Ops", "lastName": "Admin", "attributes": {"tenant_id": [DEMO_TENANT]},
             "realmRoles": ["meridian-admin"],
             "credentials": [{"type": "password", "value": "Meridian#Admin2026", "temporary": False},
                             {"type": "otp", "userLabel": "Demo staff authenticator app",
                              "secretData": json.dumps({"value": DEMO_STAFF_TOTP_SECRET}),
                              "credentialData": json.dumps({"subType": "totp", "digits": 6, "counter": 0,
                                                            "period": 30, "algorithm": "HmacSHA1"})}]},
            {"username": "platform.operator", "enabled": True, "email": "platform.operator@meridian.local",
             "emailVerified": True, "firstName": "Platform", "lastName": "Operator",
             "attributes": {"tenant_id": [PLATFORM_TENANT]}, "realmRoles": ["platform-operator"],
             "credentials": [{"type": "password", "value": "Meridian#Operator2026", "temporary": False},
                             {"type": "otp", "userLabel": "Demo staff authenticator app",
                              "secretData": json.dumps({"value": DEMO_STAFF_TOTP_SECRET}),
                              "credentialData": json.dumps({"subType": "totp", "digits": 6, "counter": 0,
                                                            "period": 30, "algorithm": "HmacSHA1"})}]},
            {"username": "service-account-meridian-service", "enabled": True, "serviceAccountClientId": "meridian-service",
             "realmRoles": ["meridian-admin", "platform-operator"]},
            {"username": "demo.customer", "enabled": True, "email": "demo.customer@meridian.local",
             "emailVerified": True, "firstName": "Demo", "lastName": "Customer",
             "attributes": {"customer_id": [DEMO_CUSTOMER], "tenant_id": [DEMO_TENANT]},
             "credentials": [{"type": "password", "value": "Meridian#Customer2026", "temporary": False},
                             {"type": "otp", "userLabel": "Demo authenticator app",
                              "secretData": json.dumps({"value": DEMO_CUSTOMER_TOTP_SECRET}),
                              "credentialData": json.dumps({"subType": "totp", "digits": 6, "counter": 0,
                                                            "period": 30, "algorithm": "HmacSHA1"})}]},
            {"username": f"service-account-{AUDIT_EVENTS_CLIENT}", "enabled": True,
             "serviceAccountClientId": AUDIT_EVENTS_CLIENT, "clientRoles": {"realm-management": ["view-events"]}},
            {"username": f"service-account-{CUSTOMER_SESSIONS_CLIENT}", "enabled": True,
             "serviceAccountClientId": CUSTOMER_SESSIONS_CLIENT,
             "clientRoles": {"realm-management": ["view-users", "manage-users"]}},
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
        ] + svc_users,
    }
    out = ROOT / "infra" / "local" / "keycloak" / "meridian-realm.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(realm, indent=2), encoding="utf-8", newline="\n")
    print(f"realm with {len(scope_names)} staff scopes, {len(channel_scopes)} channel scope(s), "
          f"{len(realm['clients'])} clients, {len(realm['users'])} users -> {out.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
