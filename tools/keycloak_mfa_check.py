#!/usr/bin/env python3
"""Checks the generated realm's customer MFA against a real, THROWAWAY Keycloak 26.x (CHN-CUSTOMER-MFA).

Imports infra/local/keycloak/meridian-realm.json (DELETING any realm "meridian" there first), then drives the
customer browser flow like a browser (no JavaScript needed: password and TOTP forms) and checks: acr 1 for a password
sign-in with amr [pwd], sid and auth_time; acr_values=2 asks only the TOTP and returns acr 2 in the same session with a
newer auth_time; SSO answers acr 2 within the level-2 max age; max_age forces password + TOTP; a user without a second
factor cannot reach acr 2 but still signs in at acr 1; the enrolment / removal application-initiated actions
(CONFIGURE_TOTP, webauthn-register, delete_credential); the password grant needs totp; the meridian-customer-sessions
client lists sessions and credentials, ends one session (refresh token refused), signs a user out everywhere and
cannot read clients or change the realm; the profile / email scopes exist.

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
        if newurl.startswith("http://localhost:13300"):
            raise AppRedirect(newurl)
        return super().redirect_request(req_, fp, code, msg, headers, newurl)


class AppRedirect(Exception):
    def __init__(self, url):
        self.url = url


class Browser:
    def __init__(self):
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
        q = {"client_id": CLIENT, "redirect_uri": REDIRECT, "response_type": "code", "scope": "openid profile email",
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


def exchange(code_url, verifier):
    code = urllib.parse.parse_qs(urllib.parse.urlparse(code_url).query).get("code", [None])[0]
    s, b = req("POST", "/realms/meridian/protocol/openid-connect/token",
               form={"grant_type": "authorization_code", "client_id": CLIENT, "code": code,
                     "redirect_uri": REDIRECT, "code_verifier": verifier})
    return s, b


def finish(br, res, verifier, password=None, otp_secret=None, username="demo.customer"):
    """Drives login pages until the app redirect; returns (tokens, pages seen)."""
    seen = []
    for _ in range(6):
        if res[0] == "redirect":
            s, b = exchange(res[1], verifier)
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
        else:
            return None, seen + [page[:1500]]
    return None, seen


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
