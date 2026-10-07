import Link from "next/link";
import type { Metadata } from "next";
import { Callout, CodeBlock } from "@/components/code";
import { Guide } from "@/components/guide";
import { ScopeTable } from "./scope-table";

export const metadata: Metadata = { title: "Authentication & scopes" };

export default function Authentication() {
  return (
    <Guide slug="authentication" lead="Meridian uses OAuth 2.1 bearer tokens. Each operation requires exactly one scope of the form domain:read or domain:write.">
      <h2>Client types</h2>
      <table>
        <thead><tr><th>Integration</th><th>Flow</th><th>Auth method</th></tr></thead>
        <tbody>
          <tr><td>Server-to-server (most integrations)</td><td>Client credentials</td><td><code>CLIENT_SECRET_BASIC</code>, <code>CLIENT_SECRET_POST</code>, <code>PRIVATE_KEY_JWT</code> or <code>TLS_CLIENT_AUTH</code> (mTLS, RFC 8705)</td></tr>
          <tr><td>Web / mobile apps acting for a user</td><td>Authorization code + PKCE</td><td><code>NONE</code> (public client)</td></tr>
          <tr><td>Open banking TPPs</td><td>Authorization code + PKCE with FAPI 2.0 (<code>fapi_profile: true</code>)</td><td><code>PRIVATE_KEY_JWT</code> or mTLS</td></tr>
        </tbody>
      </table>
      <p>You manage clients in <Link href="/console/apps">Console → Apps</Link> (API: <code>/v1/api-clients</code>). Clients have an
        environment (<code>SANDBOX</code> or <code>PRODUCTION</code>), an allow-list of scopes and optionally redirect URIs, IP ranges and an expiry.</p>

      <h2>Client credentials</h2>
      <CodeBlock code={`POST /oauth2/token HTTP/1.1
Host: auth.meridian.example
Authorization: Basic base64(client_id:client_secret)
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&scope=payments:write%20accounts:read`} />
      <p>Send the token on every request as <code>Authorization: Bearer &lt;access_token&gt;</code>. The tenant is a claim of the
        token (<code>tenant_id</code>); you never send a tenant id yourself. Tokens live 15 minutes; requesting a new one is cheap.</p>

      <h2>API keys</h2>
      <p>
        An API client can also hold long-lived <strong>API keys</strong> (prefix visible, e.g. <code>mk_live_4f9a…</code>) whose scopes are a
        subset of the client&apos;s. The plaintext key is returned once at creation (and on rotation) and only its hash is stored.
        Rotation creates a replacement key and keeps the old one valid for a grace period (<code>grace_period_hours</code>) so you can
        roll deployments without downtime. Revocation is immediate.
      </p>
      <Callout title="Prefer short-lived tokens">
        <p>Use API keys only where an OAuth flow is impractical. Restrict their scopes and set <code>expires_at</code>.</p>
      </Callout>

      <h2>Scopes</h2>
      <p>
        Scopes follow <code>&lt;domain&gt;:read</code> (GET operations) and <code>&lt;domain&gt;:write</code> (everything else). The scope each
        operation needs is shown on it in the <Link href="/reference">API reference</Link> (<code>x-required-scope</code> in the contract).
        A token without it gets <code>403 forbidden</code>. Some scopes are <em>sensitive</em> and need approval before they can be granted to a
        client; open banking scopes may additionally need customer consent.
      </p>
      <ScopeTable />

      <h2>Common failures</h2>
      <table>
        <thead><tr><th>Status</th><th>Code</th><th>Meaning</th></tr></thead>
        <tbody>
          <tr><td>401</td><td>—</td><td>Missing, expired or malformed token (details in the <code>WWW-Authenticate: Bearer error=…</code> header)</td></tr>
          <tr><td>403</td><td><code>insufficient_scope</code></td><td>Token valid but lacks the operation&apos;s scope (<code>detail</code> names the scope needed)</td></tr>
          <tr><td>403</td><td><code>forbidden</code></td><td>Not allowed for this principal (e.g. access policy, suspended client)</td></tr>
          <tr><td>429</td><td>—</td><td>Rate-limit policy of the client exceeded; back off and retry</td></tr>
        </tbody>
      </table>
    </Guide>
  );
}
