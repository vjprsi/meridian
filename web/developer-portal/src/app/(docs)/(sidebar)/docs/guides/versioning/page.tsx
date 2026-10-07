import Link from "next/link";
import type { Metadata } from "next";
import { Guide } from "@/components/guide";

export const metadata: Metadata = { title: "Versioning & changelog" };

const CHANGELOG: { date: string; version: string; items: string[] }[] = [
  {
    date: "2026-10-06",
    version: "v1 · 1.0.0",
    items: [
      "Developer portal: API reference, event catalogue, guides, app and API-key management, webhooks and the sandbox try-it console.",
      "All 16 domains and 63 services available under /v1 through the edge gateway (accounts, cards, engagement, ledger, lending, open banking, operations, party, payments, platform, product, reporting, risk, servicing, treasury, wallets).",
      "Contract hardening across every domain: typed responses, read-only server fields, explicit action request bodies and EOD/batch job actions.",
      "Webhooks: signed deliveries (Meridian-Signature, rotation with 24 h overlap), subscription filters, delivery attempts, manual retry and event replays.",
      "API clients: OAuth 2.1 clients (client secret, private_key_jwt, mTLS, FAPI 2.0 profile), API keys with one-time reveal and graceful rotation, OAuth scope catalogue.",
      "Every write requires Idempotency-Key; every update requires If-Match; all lists use keyset cursors; all errors are RFC 9457 problem+json.",
    ],
  },
];

export default function Versioning() {
  return (
    <Guide slug="versioning" lead="One major version in the URL, additive changes only within it, and plenty of notice before anything is removed.">
      <h2>Versioning policy</h2>
      <ul>
        <li>The major version is part of the path: <code>/v1/…</code>. Webhook payloads carry <code>api_version</code>; Kafka topics are <code>meridian.&lt;domain&gt;.v1</code>.</li>
        <li>Within a major version changes are <strong>additive only</strong>: new endpoints, new optional request fields, new response fields, new enum values, new event types and new error codes.</li>
        <li>Breaking changes (removing or renaming fields, changing types or semantics, new required inputs) ship only in a new major version (<code>/v2</code>), which runs side by side with the old one for a deprecation period.</li>
        <li>Deprecated operations are flagged <code>deprecated: true</code> in the contract and in this reference before removal.</li>
      </ul>

      <h2>Write tolerant clients</h2>
      <ul>
        <li>Ignore unknown JSON fields; don&apos;t fail on unknown enum values — map them to an &quot;other&quot; case.</li>
        <li>Branch on error <code>code</code>, not on <code>detail</code> text; treat unknown codes by HTTP status class.</li>
        <li>Accept new event types on wildcard webhook subscriptions without failing the delivery.</li>
      </ul>

      <h2>Contracts</h2>
      <p>The OpenAPI 3.1 and AsyncAPI 3 contracts are generated from the platform specification on every change. Download them per domain
        from the <Link href="/reference">API reference</Link>, or the bundle from the gateway at <code>/openapi.yaml</code>. The{" "}
        <a href="/specs/asyncapi/meridian.asyncapi.yaml">AsyncAPI document</a> describes every event.</p>

      <h2>Changelog</h2>
      {CHANGELOG.map((c) => (
        <section key={c.date}>
          <h3>{c.date} — {c.version}</h3>
          <ul>{c.items.map((i) => <li key={i}>{i}</li>)}</ul>
        </section>
      ))}
    </Guide>
  );
}
