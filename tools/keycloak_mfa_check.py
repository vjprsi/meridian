#!/usr/bin/env python3
"""Checks the generated realm against a real, THROWAWAY Keycloak 26.x: customer MFA (CHN-CUSTOMER-MFA), staff MFA
(GAP-13), per-service clients (GAP-16) and realm events (GAP-06).

Imports infra/local/keycloak/meridian-realm.json (DELETING any realm "meridian" there first), then drives the
customer browser flow like a browser (no JavaScript needed: password and TOTP forms) and checks: acr 1 for a password
sign-in with amr [pwd], sid and auth_time; acr_values=2 asks only the TOTP and returns acr 2 in the same session with a
newer auth_time; SSO answers acr 2 within the level-2 max age; max_age forces password + TOTP; a user without a second
factor cannot reach acr 2 but still signs in at acr 1; the enrolment / removal application-initiated actions
(CONFIGURE_TOTP, webauthn-register, delete_credential); the password grant needs totp; the meridian-customer-sessions
client lists sessions and credentials, ends one session (refresh token refused), signs a user out everywhere and
cannot read clients or change the realm; the profile / email scopes exist.
Staff (GAP-13): console and developer portal use the staff flow (password + TOTP / WebAuthn, acr 2 required), a lower
acr cannot be requested, max_age re-asks both factors, a staff user without a factor must enrol an authenticator app
before any token is issued. Service identities (GAP-16): svc-<service> client-credential tokens carry azp and the derived
scopes (role-gated platform:operator only where needed); the legacy shared client still works. Events (GAP-06): login,
login error, client login and admin events (with representation) are stored and readable only by the
meridian-audit-events client (view-events: no user reads, no realm changes).

    python tools/keycloak_realm.py
    python tools/keycloak_mfa_check.py --url http://127.0.0.1:18998 --replace-realm   # admin / admin by default

Never point it at a Keycloak whose meridian realm you need (the local stack on :15180 included).
"""
import argparse, base64, hashlib, hmac, html, http.cookiejar, json, re, secrets, struct, sys, time, urllib.parse, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REALM_FILE = ROOT / "infra" / "local" / "keycloak" / "meridian-realm.json"
KC = "http://127.0.0.1:18998"
ADMIN = ("admin", "admin")



def req(method, path, body=None, token=None, form=None, raw=False):
    headers = {}
    data = None
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    r = urllib.request.Request(KC + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(r) as resp:
            t = resp.read().decode()
            return resp.status, (t if raw or not t else json.loads(t))
    except urllib.error.HTTPError as e:
        t = e.read().decode()
        try:
            return e.code, json.loads(t)
        except Exception:
            return e.code, t


def admin_token():
    s, b = req("POST", "/realms/master/protocol/openid-connect/token",
               form={"grant_type": "password", "client_id": "admin-cli", "username": ADMIN[0], "password": ADMIN[1]})
    return b["access_token"]


def jwt(t):
    import base64
    p = t.split(".")[1]
    p += "=" * (-len(p) % 4)
    return json.loads(base64.urlsafe_b64decode(p))



REDIRECT = "http://localhost:13300/auth/callback"
CLIENT = "meridian-customer-web"
results = []


def check(name, cond, extra=""):
    results.append((name, bool(cond)))
    print(("PASS " if cond else "FAIL ") + name + (" :: " + str(extra) if extra else ""))


_last = {"c": None}


def totp(secret: bytes, t=None):
    c = int((t or time.time()) // 30)
    if c == _last["c"]:          # codes are single-use (otpPolicyCodeReusable false): wait for the next window
        time.sleep(30 - time.time() % 30 + 0.5)
        c = int(time.time() // 30)
    _last["c"] = c
    h = hmac.new(secret, struct.pack(">Q", c), hashlib.sha1).digest()
    o = h[-1] & 15
    return "%06d" % ((struct.unpack(">I", h[o:o + 4])[0] & 0x7FFFFFFF) % 1000000)


class NoRedirectToApp(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req_, fp, code, msg, headers, newurl):
        if newurl.startswith("http://localhost:13"):          # any Meridian web app (13100 console ... 13600)
            raise AppRedirect(newurl)
        return super().redirect_request(req_, fp, code, msg, headers, newurl)


class AppRedirect(Exception):
    def __init__(self, url):
        self.url = url


class Browser:
    def __init__(self, client=None, redirect=None):
        self.client = client or CLIENT
        self.redirect = redirect or REDIRECT

        class LocalhostIsSecure(http.cookiejar.DefaultCookiePolicy):
            def return_ok_secure(self, cookie, request):   # like browsers: http://localhost counts as secure
                return True
        self.jar = http.cookiejar.CookieJar(LocalhostIsSecure())
        self.op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar), NoRedirectToApp())

    def open(self, url, form=None):
        data = urllib.parse.urlencode(form).encode() if form is not None else None
        for c in self.jar:   # Keycloak marks its cookies Secure; browsers treat localhost http as secure
            c.secure = False
        try:
            with self.op.open(urllib.request.Request(url, data=data)) as r:
                return ("page", r.read().decode())
        except AppRedirect as e:
            return ("redirect", e.url)
        except urllib.error.HTTPError as e:
            return ("error", e.code, e.read().decode())

    def authorize(self, **extra):
        verifier = secrets.token_urlsafe(48)
        challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
        q = {"client_id": self.client, "redirect_uri": self.redirect, "response_type": "code",
             "scope": "openid profile email",
             "code_challenge": challenge, "code_challenge_method": "S256", "state": secrets.token_hex(8),
             "nonce": secrets.token_hex(8)}
        q.update(extra)
        res = self.open(f"{KC}/realms/meridian/protocol/openid-connect/auth?" + urllib.parse.urlencode(q))
        return res, verifier


def form_action(page):
    m = re.search(r'<form[^>]*action="([^"]+)"', page)
    return html.unescape(m.group(1)) if m else None


def page_kind(page):
    for k in ("kc-otp-login-form", "kc-form-login", "kc-totp-settings-form", "webauth", "kc-select-credential-form",
              "kc-delete-credential", "delete-credential", "registerWebAuthn", "kc-error-message", "authenticators-choice"):
        if k in page:
            return k
    return "unknown"


def exchange(code_url, verifier, client=None, redirect=None):
    code = urllib.parse.parse_qs(urllib.parse.urlparse(code_url).query).get("code", [None])[0]
    s, b = req("POST", "/realms/meridian/protocol/openid-connect/token",
               form={"grant_type": "authorization_code", "client_id": client or CLIENT, "code": code,
                     "redirect_uri": redirect or REDIRECT, "code_verifier": verifier})
    return s, b


def finish(br, res, verifier, password=None, otp_secret=None, username="demo.customer", enrol=False, enrolled=None):
    """Drives login pages until the app redirect; returns (tokens, pages seen). With enrol, completes a forced TOTP
    set-up (the new secret is stored in enrolled["secret"])."""
    enrolled = enrolled if enrolled is not None else {}
    seen = []
    for _ in range(8):
        if res[0] == "redirect":
            s, b = exchange(res[1], verifier, br.client, br.redirect)
            if s != 200:
                print("token exchange failed", s, b, res[1][:300])
                return None, seen
            return b, seen
        if res[0] == "error":
            seen.append(("error", res[1], res[2][:300]))
            return None, seen
        page = res[1]
        kind = page_kind(page)
        seen.append(kind)
        action = form_action(page)
        if kind == "kc-form-login":
            res = br.open(action, {"username": username, "password": password, "credentialId": ""})
        elif kind == "kc-otp-login-form":
            res = br.open(action, {"otp": totp(otp_secret), "login": "Sign In"})
        elif kind == "kc-totp-settings-form" and enrol:
            # forced enrolment: the page carries the new raw secret in the hidden totpSecret field
            secret_field = re.search(r'name="totpSecret"[^>]*value="([^"]+)"', page) or \
                re.search(r'value="([^"]+)"[^>]*name="totpSecret"', page)
            enrolled["secret"] = html.unescape(secret_field.group(1)).encode()
            res = br.open(action, {"totp": totp(enrolled["secret"]), "totpSecret": html.unescape(secret_field.group(1)),
                                   "userLabel": "check", "mode": ""})
        else:
            return None, seen + [page[:1500]]
    return None, seen


STAFF_TOTP = b"MeridianStaffTotp2026"
CONSOLE = ("meridian-console", "http://localhost:13100/auth/callback")
PORTAL = ("meridian-developer-portal", "http://localhost:13200/auth/callback")


def staff_checks(tok):
    """GAP-13: staff sign-in is password plus a second factor (acr 2), enrolment is forced, privileged scopes ride on
    acr 2 tokens."""
    s, flows = req("GET", "/admin/realms/meridian/authentication/flows", token=tok)
    aliases = {f["alias"]: f["id"] for f in flows}
    check("staff browser flow imported with its fixed id",
          aliases.get("meridian staff browser") == "0199a000-0000-7000-8000-00000000f102")
    for client_id, _ in (CONSOLE, PORTAL):
        s, cl = req("GET", f"/admin/realms/meridian/clients?clientId={client_id}", token=tok)
        a = cl[0]["attributes"]
        check(f"{client_id}: staff flow override, acr.loa.map, level 2 default and minimum",
              cl[0]["authenticationFlowBindingOverrides"].get("browser") == aliases.get("meridian staff browser")
              and json.loads(a.get("acr.loa.map", "{}")) == {"1": 1, "2": 2}
              and a.get("default.acr.values") == "2" and a.get("minimum.acr.value") == "2", a)
    # 1. enrolled staff user: password then TOTP, acr 2, admin scope present
    br = Browser(*CONSOLE)
    res, ver = br.authorize()
    tokens, seen = finish(br, res, ver, password="Meridian#Admin2026", otp_secret=STAFF_TOTP, username="ops.admin")
    at = jwt(tokens["access_token"]) if tokens else {}
    check("console sign-in asks password and the authenticator code, token acr 2",
          tokens and seen == ["kc-form-login", "kc-otp-login-form"] and at.get("acr") == "2",
          (seen, {k: at.get(k) for k in ("acr", "amr", "azp")}))
    check("staff token carries amr [pwd, otp], auth_time, tenant_id and meridian:admin",
          at.get("amr") == ["pwd", "otp"] and at.get("auth_time") and at.get("tenant_id")
          and "meridian:admin" in at.get("scope", "").split(), {k: at.get(k) for k in ("amr", "auth_time", "scope")})
    # 2. a lower level cannot be requested: acr_values=1 still ends at acr 2 (minimum), here through SSO
    res, ver = br.authorize(acr_values="1")
    t2, seen = finish(br, res, ver, otp_secret=STAFF_TOTP)
    check("acr_values=1 is raised to the minimum: SSO answers with acr 2, no password-only token",
          t2 and jwt(t2["access_token"]).get("acr") == "2", (seen, t2 and jwt(t2["access_token"]).get("acr")))
    # 3. max_age forces password + second factor again
    time.sleep(2)
    res, ver = br.authorize(max_age="1")
    t3, seen = finish(br, res, ver, password="Meridian#Admin2026", otp_secret=STAFF_TOTP, username="ops.admin")
    check("max_age exceeded re-asks password and second factor", seen == ["kc-form-login", "kc-otp-login-form"]
          and t3 and jwt(t3["access_token"]).get("acr") == "2", seen)
    # 4. a staff user without a second factor must enrol one before any token is issued
    new_staff = {"username": "new.staff", "enabled": True, "email": "new.staff@meridian.local", "emailVerified": True,
                 "firstName": "New", "lastName": "Staff", "attributes": {"tenant_id": ["0199a000-0000-7000-8000-00000000000a"]},
                 "credentials": [{"type": "password", "value": "Meridian#NewStaff2026", "temporary": False}]}
    s, b = req("POST", "/admin/realms/meridian/users", body=new_staff, token=admin_token())
    check("create staff user without second factor", s == 201, b)
    br2 = Browser(*PORTAL)
    res, ver = br2.authorize()
    first = finish(br2, res, ver, password="Meridian#NewStaff2026", username="new.staff")
    check("developer portal: no token for a staff user without a second factor; TOTP set-up is forced",
          first[0] is None and "kc-totp-settings-form" in first[1], first[1][:3])
    br3 = Browser(*PORTAL)
    res, ver = br3.authorize()
    enrolled = {}
    t4, seen = finish(br3, res, ver, password="Meridian#NewStaff2026", username="new.staff", enrol=True,
                      enrolled=enrolled)
    at4 = jwt(t4["access_token"]) if t4 else {}
    s, users = req("GET", "/admin/realms/meridian/users?username=new.staff&exact=true", token=admin_token())
    s, creds = req("GET", f"/admin/realms/meridian/users/{users[0]['id']}/credentials", token=admin_token())
    # (the enrolment runs as a required action, not as the OTP authenticator, so amr shows only pwd for this sign-in)
    check("after enrolling the authenticator app the sign-in completes with acr 2 and the user has an otp factor",
          t4 and at4.get("acr") == "2" and "otp" in {c["type"] for c in creds},
          (seen, at4.get("acr"), at4.get("amr"), [c["type"] for c in creds]))
    check("developer portal tokens carry platform:read/write only (never meridian:admin)",
          "meridian:admin" not in at4.get("scope", "") and "platform:write" in at4.get("scope", ""), at4.get("scope"))
    br4 = Browser(*CONSOLE)
    res, ver = br4.authorize()
    t5, seen = finish(br4, res, ver, password="Meridian#NewStaff2026", username="new.staff",
                      otp_secret=enrolled.get("secret"))
    check("enrolled factor is then asked at every new sign-in", seen == ["kc-form-login", "kc-otp-login-form"]
          and t5 is not None, seen)
    # a failed password for the events checks
    br5 = Browser(*CONSOLE)
    res, ver = br5.authorize()
    page = res[1] if res[0] == "page" else ""
    br5.open(form_action(page), {"username": "ops.admin", "password": "wrong-password", "credentialId": ""})


def client_token(client_id, secret):
    return req("POST", "/realms/meridian/protocol/openid-connect/token",
               form={"grant_type": "client_credentials", "client_id": client_id, "client_secret": secret})


def service_identity_checks():
    """GAP-16: one client per service; azp names the service; scopes derived from the calls it makes."""
    s, b = client_token("svc-deposit-service", "svc-deposit-service-secret")
    at = jwt(b["access_token"]) if s == 200 else {}
    scopes = set(at.get("scope", "").split())
    check("svc-deposit-service: client credentials token with azp svc-deposit-service, audience and tenant",
          s == 200 and at.get("azp") == "svc-deposit-service" and "meridian-api" in (
              at.get("aud") if isinstance(at.get("aud"), list) else [at.get("aud")]) and at.get("tenant_id"),
          {k: at.get(k) for k in ("azp", "aud", "tenant_id")})
    check("svc-deposit-service scopes: meridian:internal + the domains it calls (ledger, product), never admin",
          {"meridian:internal", "ledger:write", "product:write"} <= scopes and "meridian:admin" not in scopes
          and not any(x.startswith("channels:customer") for x in scopes), sorted(scopes))
    s, b = client_token("svc-archive-service", "svc-archive-service-secret")
    arch = set(jwt(b["access_token"]).get("scope", "").split()) if s == 200 else set()
    check("svc-archive-service gets the role-gated platform:operator (data-lifecycle calls)",
          "platform:operator" in arch, sorted(arch))
    s, b = client_token("svc-gl-service", "svc-gl-service-secret")
    gl = set(jwt(b["access_token"]).get("scope", "").split()) if s == 200 else set()
    check("svc-gl-service has no platform:operator and no foreign domain it does not call",
          s == 200 and "platform:operator" not in gl and "payments:write" not in gl, sorted(gl))
    s, b = client_token("svc-deposit-service", "wrong")
    check("a wrong client secret is refused", s == 401, s)
    s, b = client_token("meridian-service", "meridian-service-secret")
    check("legacy shared client still issues tokens during the migration",
          s == 200 and jwt(b["access_token"]).get("azp") == "meridian-service", s)


def events_checks():
    """GAP-06: login and admin events are stored and readable by the audit-service events client only."""
    tok = admin_token()
    s, realm = req("GET", "/admin/realms/meridian", token=tok)
    check("realm stores user events with metrics-listener and jboss-logging, admin events with representation",
          realm.get("eventsEnabled") and realm.get("adminEventsEnabled") and realm.get("adminEventsDetailsEnabled")
          and {"metrics-listener", "jboss-logging"} <= set(realm.get("eventsListeners", [])),
          {k: realm.get(k) for k in ("eventsEnabled", "eventsListeners", "adminEventsEnabled",
                                     "adminEventsDetailsEnabled", "eventsExpiration")})
    s, b = client_token("meridian-audit-events", "meridian-audit-events-secret")
    check("audit events client obtains a token", s == 200, b if s != 200 else "")
    et = b.get("access_token")
    claims = jwt(et) if et else {}
    check("audit events client token has no Meridian audience or scope",
          "meridian-api" not in (claims.get("aud") if isinstance(claims.get("aud"), list) else [claims.get("aud")])
          and ":" not in claims.get("scope", "").replace("openid", ""), (claims.get("aud"), claims.get("scope")))
    s, events = req("GET", "/admin/realms/meridian/events?max=500", token=et)
    types = {e.get("type") for e in events} if s == 200 else set()
    print("event types seen:", sorted(types))
    print("event sample:", json.dumps(events[0])[:500] if s == 200 and events else events)
    check("user events readable: LOGIN, LOGIN_ERROR, CLIENT_LOGIN recorded with ids",
          s == 200 and {"LOGIN", "LOGIN_ERROR", "CLIENT_LOGIN"} <= types and all(e.get("id") for e in events),
          (s, sorted(types)))
    svc = [e for e in events if e.get("type") == "CLIENT_LOGIN" and e.get("clientId") == "svc-deposit-service"]
    check("service client logins are attributed to the per-service client", bool(svc), len(svc))
    s, admin_events = req("GET", "/admin/realms/meridian/admin-events?max=200", token=et)
    created = [e for e in admin_events if e.get("operationType") == "CREATE" and e.get("resourceType") == "USER"] \
        if s == 200 else []
    print("admin event sample:", json.dumps(created[0])[:600] if created else admin_events)
    check("admin events readable: user creation recorded with representation and id",
          s == 200 and created and created[0].get("representation") and created[0].get("id"), (s, len(created)))
    s, b = req("GET", "/admin/realms/meridian/users", token=et)
    check("audit events client cannot read users", s == 403, s)
    s, b = req("PUT", "/admin/realms/meridian", body={"eventsEnabled": False}, token=et)
    check("audit events client cannot change the realm (or switch events off)", s == 403, s)


def main():
    tok = admin_token()
    req("DELETE", "/admin/realms/meridian", token=tok)
    realm = json.load(open(REALM_FILE, encoding="utf-8"))  # noqa: SIM115
    s, b = req("POST", "/admin/realms", body=realm, token=tok)
    check("realm imports", s == 201, (s, str(b)[:500]))
    tok = admin_token()
    s, flows = req("GET", "/admin/realms/meridian/authentication/flows", token=tok)
    aliases = {f["alias"]: f["id"] for f in flows}
    check("default flows still present (browser, direct grant, registration, reset credentials)",
          all(a in aliases for a in ("browser", "direct grant", "registration", "reset credentials")), sorted(aliases))
    check("customer browser flow imported with its fixed id",
          aliases.get("meridian customer browser") == "0199a000-0000-7000-8000-00000000f101")
    s, clients = req("GET", "/admin/realms/meridian/clients?clientId=meridian-customer-web", token=tok)
    c = clients[0]
    check("customer client browser override + acr.loa.map",
          c["authenticationFlowBindingOverrides"].get("browser") == aliases.get("meridian customer browser")
          and json.loads(c["attributes"]["acr.loa.map"]) == {"1": 1, "2": 2}, c["attributes"])
    s, ras = req("GET", "/admin/realms/meridian/authentication/required-actions", token=tok)
    enabled = {r["alias"] for r in ras if r["enabled"]}
    check("enrolment required actions enabled",
          {"CONFIGURE_TOTP", "webauthn-register", "webauthn-register-passwordless", "delete_credential"} <= enabled,
          sorted(enabled))
    s, users = req("GET", "/admin/realms/meridian/users?username=demo.customer&exact=true", token=tok)
    demo = users[0]
    s, creds = req("GET", f"/admin/realms/meridian/users/{demo['id']}/credentials", token=tok)
    check("demo.customer has password + otp", sorted(x["type"] for x in creds) == ["otp", "password"], creds)
    # a customer without a second factor
    nofactor = {"username": "no.factor", "enabled": True, "email": "nf@meridian.local", "emailVerified": True,
                "firstName": "No", "lastName": "Factor",
                "attributes": {"customer_id": ["0199a000-0000-7000-8000-0000000c0009"],
                               "tenant_id": ["0199a000-0000-7000-8000-00000000000a"]},
                "credentials": [{"type": "password", "value": "Meridian#NoFactor2026", "temporary": False}]}
    s, b = req("POST", "/admin/realms/meridian/users", body=nofactor, token=tok)
    check("create user without second factor", s == 201, b)

    secret = b"MeridianDemoTotp2026"
    br = Browser()
    # 1. normal sign-in -> acr 1, amr pwd
    res, ver = br.authorize()
    tokens, seen = finish(br, res, ver, password="Meridian#Customer2026", otp_secret=secret)
    at = jwt(tokens["access_token"]) if tokens else {}
    check("sign-in without acr_values -> password only, acr 1", tokens and at.get("acr") == "1" and seen == ["kc-form-login"],
          (seen, {k: at.get(k) for k in ("acr", "amr", "sid", "auth_time", "customer_id", "aud", "scope")}))
    check("access token carries amr [pwd], sid and auth_time",
          at.get("amr") == ["pwd"] and at.get("sid") and at.get("auth_time"), {k: at.get(k) for k in ("amr", "sid", "auth_time")})
    sid1, auth1 = at.get("sid"), at.get("auth_time")
    refresh1 = tokens["refresh_token"] if tokens else None
    time.sleep(2)
    # 2. step-up acr 2 in the same SSO session -> only the OTP form
    res, ver = br.authorize(acr_values="2")
    tokens2, seen = finish(br, res, ver, otp_secret=secret)
    at2 = jwt(tokens2["access_token"]) if tokens2 else {}
    check("step-up acr_values=2 asks only the second factor and yields acr 2",
          tokens2 and at2.get("acr") == "2" and seen == ["kc-otp-login-form"], (seen, at2.get("acr"), at2.get("amr")))
    check("step-up keeps the session (same sid), amr has otp", at2.get("sid") == sid1 and "otp" in (at2.get("amr") or []),
          (at2.get("sid"), at2.get("amr")))
    print("auth_time before/after OTP step-up:", auth1, at2.get("auth_time"))
    check("auth_time advances on a second-factor-only step-up", (at2.get("auth_time") or 0) > (auth1 or 0),
          (auth1, at2.get("auth_time")))
    # 3. within the level-2 max age a further acr 2 request is satisfied by SSO
    res, ver = br.authorize(acr_values="2")
    tokens3, seen = finish(br, res, ver, otp_secret=secret)
    at3 = jwt(tokens3["access_token"]) if tokens3 else {}
    check("acr 2 again within loa-max-age: SSO, no prompt", tokens3 and seen == [] and at3.get("acr") == "2", (seen, at3.get("acr")))
    # 4. acr 2 + max_age exceeded -> full re-authentication (password + OTP)
    time.sleep(2)
    res, ver = br.authorize(acr_values="2", max_age="1")
    tokens4, seen = finish(br, res, ver, password="Meridian#Customer2026", otp_secret=secret)
    at4 = jwt(tokens4["access_token"]) if tokens4 else {}
    check("acr_values=2 & max_age exceeded re-asks password and OTP", seen == ["kc-form-login", "kc-otp-login-form"]
          and at4.get("acr") == "2" and at4.get("amr") == ["pwd", "otp"], (seen, at4.get("acr"), at4.get("amr")))
    # 5. plain request after step-up: default acr 1 requested, session has 2
    res, ver = br.authorize()
    tokens5, seen = finish(br, res, ver)
    at5 = jwt(tokens5["access_token"]) if tokens5 else {}
    print("plain SSO after level 2:", seen, at5.get("acr"), at5.get("amr"))
    # 6. user without a second factor asks for acr 2
    br2 = Browser()
    res, ver = br2.authorize(acr_values="2")
    tokens6, seen = finish(br2, res, ver, password="Meridian#NoFactor2026", username="no.factor")
    print("no-factor user acr 2:", [x if isinstance(x, (str, tuple)) else x for x in seen][:3])
    check("no second factor: acr 2 is not granted", not tokens6 or jwt(tokens6["access_token"]).get("acr") != "2",
          tokens6 and jwt(tokens6["access_token"]).get("acr"))
    br2 = Browser()
    res, ver = br2.authorize()
    nf_tokens, seen = finish(br2, res, ver, password="Meridian#NoFactor2026", username="no.factor")
    check("no second factor: acr 1 sign-in still works", nf_tokens and jwt(nf_tokens["access_token"]).get("acr") == "1",
          seen)
    # 7. enrolment AIA pages (fresh browsers: one sign-in, then the application-initiated action)
    brA = Browser()
    res, ver = brA.authorize()
    finish(brA, res, ver, password="Meridian#NoFactor2026", username="no.factor")
    res, ver = brA.authorize(kc_action="CONFIGURE_TOTP")
    check("kc_action=CONFIGURE_TOTP (no factor yet) shows the authenticator app set-up",
          res[0] == "page" and page_kind(res[1]) == "kc-totp-settings-form", (res[0], str(res[1])[:300]))
    brB = Browser()
    res, ver = brB.authorize()
    finish(brB, res, ver, password="Meridian#Customer2026")
    res, ver = brB.authorize(kc_action="CONFIGURE_TOTP")
    check("kc_action=CONFIGURE_TOTP with an existing factor first asks that factor (strong authentication)",
          res[0] == "page" and page_kind(res[1]) == "kc-otp-login-form", (res[0], str(res[1])[:300]))
    res, ver = br.authorize(kc_action="webauthn-register")
    check("kc_action=webauthn-register shows the security key / passkey registration",
          res[0] == "page" and ("registerWebAuthn" in res[1] or "webauthn" in res[1].lower()), res[0])
    otp_id = next(x["id"] for x in creds if x["type"] == "otp")
    res, ver = br.authorize(kc_action="delete_credential:" + otp_id)
    check("kc_action=delete_credential:<id> asks to confirm the removal",
          res[0] == "page" and ("delete" in res[1].lower()), res[0] == "page" and res[1][res[1].find("<form"):][:300])
    # 8. password grant (scripts): acr
    s, pg = req("POST", "/realms/meridian/protocol/openid-connect/token",
                form={"grant_type": "password", "client_id": CLIENT, "username": "demo.customer",
                      "password": "Meridian#Customer2026", "scope": "openid"})
    check("password grant without totp refused for a user with an authenticator app", s == 401, (s, pg))
    s, pg = req("POST", "/realms/meridian/protocol/openid-connect/token",
                form={"grant_type": "password", "client_id": CLIENT, "username": "demo.customer",
                      "password": "Meridian#Customer2026", "scope": "openid", "totp": totp(secret)})
    pga = jwt(pg["access_token"]) if s == 200 else {}
    print("password grant with totp:", s, {k: pga.get(k) for k in ("acr", "amr", "auth_time", "sid")})
    # 9. sessions client
    s, cc = req("POST", "/realms/meridian/protocol/openid-connect/token",
                form={"grant_type": "client_credentials", "client_id": "meridian-customer-sessions",
                      "client_secret": "meridian-customer-sessions-secret"})
    check("sessions client obtains a token", s == 200, cc if s != 200 else "")
    st = cc.get("access_token")
    sct = jwt(st)
    check("sessions client token has no Meridian audience or scope",
          "meridian-api" not in (sct.get("aud") if isinstance(sct.get("aud"), list) else [sct.get("aud")])
          and "channels" not in sct.get("scope", ""), (sct.get("aud"), sct.get("scope")))
    sub = at.get("sub")
    s, sess = req("GET", f"/admin/realms/meridian/users/{sub}/sessions", token=st)
    check("sessions client lists the customer's sessions (id == sid claim)",
          s == 200 and any(x["id"] == sid1 for x in sess), (s, sess))
    print("session sample:", json.dumps(sess[0])[:400] if s == 200 and sess else sess)
    s, cr = req("GET", f"/admin/realms/meridian/users/{sub}/credentials", token=st)
    check("sessions client lists the customer's credentials", s == 200 and len(cr) == 2, (s, [x.get("type") for x in cr] if s == 200 else cr))
    # sessions client must not reach other realms / realm admin
    s, b = req("GET", "/admin/realms/meridian/clients", token=st)
    check("sessions client cannot list clients", s == 403, s)
    s, b = req("PUT", "/admin/realms/meridian", body={"bruteForceProtected": False}, token=st)
    check("sessions client cannot change the realm", s == 403, s)
    # revoke one session
    s, b = req("DELETE", f"/admin/realms/meridian/sessions/{sid1}", token=st)
    check("sessions client ends one session", s == 204, (s, b))
    s, b = req("POST", "/realms/meridian/protocol/openid-connect/token",
               form={"grant_type": "refresh_token", "client_id": CLIENT, "refresh_token": tokens4["refresh_token"]})
    check("refresh token of the ended session is refused", s == 400, (s, b))
    # logout everywhere for the no-factor user
    if nf_tokens:
        nsub = jwt(nf_tokens["access_token"])["sub"]
    else:
        s, u = req("GET", "/admin/realms/meridian/users?username=no.factor&exact=true", token=tok)
        nsub = u[0]["id"]
    s, b = req("POST", f"/admin/realms/meridian/users/{nsub}/logout", token=st)
    check("sessions client signs a user out everywhere", s == 204, (s, b))
    # staff console still signs in with openid profile email (realm-wide scopes fix)
    s, b = req("GET", "/realms/meridian/.well-known/openid-configuration")
    check("profile and email scopes advertised", {"profile", "email"} <= set(b.get("scopes_supported", [])),
          b.get("scopes_supported"))
    staff_checks(tok)
    service_identity_checks()
    events_checks()
    print("\nSUMMARY", sum(1 for _, ok in results if ok), "/", len(results), "passed")
    for n, ok in results:
        if not ok:
            print("  FAILED:", n)


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--url", default=KC, help="throwaway Keycloak base URL")
    ap.add_argument("--admin", default="admin:admin", help="master realm admin user:password")
    ap.add_argument("--replace-realm", action="store_true", help="required: the meridian realm there is deleted")
    a = ap.parse_args()
    if not a.replace_realm:
        sys.exit("refusing to run without --replace-realm (the meridian realm of that Keycloak is deleted)")
    KC = a.url.rstrip("/")
    ADMIN = tuple(a.admin.split(":", 1))
    main()
    sys.exit(0 if all(ok for _, ok in results) else 1)
