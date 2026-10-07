import Link from "next/link";
import type { Metadata } from "next";
import { Callout, CodeBlock, CodeTabs } from "@/components/code";
import { Guide } from "@/components/guide";

export const metadata: Metadata = { title: "Getting started" };

const TOKEN = `curl -s -X POST https://auth.meridian.example/oauth2/token \\
  -u "$CLIENT_ID:$CLIENT_SECRET" \\
  -d grant_type=client_credentials \\
  -d scope="party:read party:write accounts:read"`;

const TOKEN_LOCAL = `# Local stack: Keycloak realm "meridian" (python tools/dev.py up --phase 1)
curl -s -X POST http://localhost:15180/realms/meridian/protocol/openid-connect/token \\
  -d grant_type=client_credentials \\
  -d client_id=meridian-service -d client_secret=meridian-service-secret`;

const FIRST_CALL = [
  { label: "curl", code: `curl https://sandbox.api.meridian.example/v1/customers?limit=5 \\
  -H "Authorization: Bearer $TOKEN"` },
  { label: "Node", code: `const res = await fetch("https://sandbox.api.meridian.example/v1/customers?limit=5", {
  headers: { Authorization: \`Bearer \${process.env.TOKEN}\` },
});
const page = await res.json();          // { data: [...], has_more, next_cursor }
console.log(page.data.map((c) => c.id));` },
  { label: "Python", code: `import os, requests

r = requests.get("https://sandbox.api.meridian.example/v1/customers",
                 params={"limit": 5},
                 headers={"Authorization": f"Bearer {os.environ['TOKEN']}"})
r.raise_for_status()
print([c["id"] for c in r.json()["data"]])` },
  { label: "Java", code: `var http = HttpClient.newHttpClient();
var req = HttpRequest.newBuilder(URI.create("https://sandbox.api.meridian.example/v1/customers?limit=5"))
        .header("Authorization", "Bearer " + System.getenv("TOKEN"))
        .build();
var res = http.send(req, HttpResponse.BodyHandlers.ofString());
System.out.println(res.statusCode() + " " + res.body());` },
];

const FIRST_WRITE = `curl -X POST https://sandbox.api.meridian.example/v1/customers \\
  -H "Authorization: Bearer $TOKEN" \\
  -H "Idempotency-Key: $(uuidgen)" \\
  -H "Content-Type: application/json" \\
  -d '{
    "customer_type": "INDIVIDUAL",
    "legal_entity_id": "'"$LEGAL_ENTITY_ID"'",
    "first_name": "Ada",
    "last_name": "Lovelace"
  }'`;

export default function GettingStarted() {
  return (
    <Guide slug="getting-started" lead="From zero to your first API call in the sandbox in about five minutes.">
      <h2>1. Your sandbox tenant</h2>
      <p>
        Every Meridian deployment is multi-tenant: each bank (or fintech) is a <em>tenant</em> and every API call runs inside
        exactly one tenant, taken from your access token. When you sign in to the <Link href="/console">developer console</Link> you
        work in your organisation&apos;s <strong>sandbox tenant</strong> — an isolated copy of the platform with test data and
        simulated payment rails, card networks and bureaus. Nothing you do there moves real money.
      </p>
      <p>
        Base URLs: sandbox <code>https://sandbox.api.meridian.example</code>, production <code>https://api.meridian.example</code>.
        When running the platform locally the edge gateway is at <code>http://localhost:18000</code>.
      </p>

      <h2>2. Create an API client</h2>
      <p>
        Server integrations authenticate with the OAuth 2.0 <strong>client credentials</strong> grant. Open{" "}
        <Link href="/console/apps">Console → Apps</Link>, choose <em>New app</em>, pick environment <code>SANDBOX</code>, auth
        method <code>CLIENT_SECRET_BASIC</code> and the scopes your integration needs (for this guide{" "}
        <code>party:read</code>, <code>party:write</code> and <code>accounts:read</code>). The client secret is shown{" "}
        <strong>once</strong>; store it in your secret manager.
      </p>
      <Callout tone="warn" title="Keep secrets server-side">
        <p>Client secrets and API keys must never ship in a browser or mobile app. Public apps use authorization code + PKCE instead.</p>
      </Callout>

      <h2>3. Get an access token</h2>
      <CodeBlock code={TOKEN} title="Sandbox token endpoint" />
      <p>The response contains <code>access_token</code> (a JWT valid for 15 minutes) — cache it and request a new one shortly before it expires.</p>
      <CodeBlock code={TOKEN_LOCAL} title="Local development" />

      <h2>4. Make your first call</h2>
      <CodeTabs tabs={FIRST_CALL} />
      <p>
        List endpoints return a page: <code>{"{ \"data\": [...], \"has_more\": false, \"next_cursor\": null }"}</code> — see{" "}
        <Link href="/docs/guides/pagination">Pagination</Link>.
      </p>

      <h2>5. Your first write</h2>
      <p>Every <code>POST</code> needs an <code>Idempotency-Key</code> so that a retry can never create a duplicate:</p>
      <CodeBlock code={FIRST_WRITE} />
      <p>
        The exact fields of each request body are in the <Link href="/reference/party?tag=Customers">API reference</Link>. You can
        also send requests from the browser with the <Link href="/console/try">sandbox try-it console</Link>, which signs them with your
        own token and generates idempotency keys for you.
      </p>

      <h2>Next steps</h2>
      <ul>
        <li><Link href="/docs/guides/authentication">Authentication &amp; scopes</Link> — which scope each operation needs.</li>
        <li><Link href="/docs/guides/errors">Errors</Link> — handle <code>problem+json</code> responses.</li>
        <li><Link href="/docs/guides/webhooks">Webhooks</Link> — get notified instead of polling.</li>
      </ul>
    </Guide>
  );
}
