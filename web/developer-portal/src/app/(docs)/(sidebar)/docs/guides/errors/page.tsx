import type { Metadata } from "next";
import { CodeBlock } from "@/components/code";
import { Guide } from "@/components/guide";

export const metadata: Metadata = { title: "Errors" };

const CODES: [number, string, string][] = [
  [400, "validation_failed", "Body or parameters failed validation; see errors[] for each field"],
  [400, "malformed_request", "Unparseable JSON, wrong type or missing header"],
  [400, "missing_parameter", "A required query parameter is missing"],
  [400, "idempotency_key_required", "POST without Idempotency-Key"],
  [400, "if_match_required", "PATCH without If-Match"],
  [400, "invalid_value", "A value is out of range or has invalid syntax"],
  [401, "—", "Missing or invalid bearer token"],
  [403, "insufficient_scope", "The token lacks the operation's scope"],
  [403, "forbidden", "The principal may not perform this action"],
  [404, "not_found", "No such resource in your tenant (or no such endpoint)"],
  [405, "method_not_allowed", "HTTP method not supported on this path"],
  [409, "version_conflict", "If-Match does not match the current row_version"],
  [409, "request_in_progress", "Same Idempotency-Key is still executing"],
  [409, "duplicate", "A resource with the same unique key already exists"],
  [409, "immutable_record", "Append-only record; post a correction instead"],
  [409, "invalid_transition", "The resource's state machine does not allow this action now"],
  [415, "unsupported_media_type", "Send Content-Type: application/json"],
  [422, "idempotency_key_reused", "Same Idempotency-Key with a different body"],
  [422, "constraint_violation", "A business or database constraint was violated"],
  [422, "insufficient_funds", "Available balance too low (ledger, wallets, payments)"],
  [422, "currency_mismatch", "Amount currency differs from the account / instrument currency"],
  [422, "four_eyes_violation", "Maker-checker: the approver must differ from the requester"],
  [429, "—", "Rate limit exceeded; retry after a back-off"],
  [500, "internal_error", "Unexpected error; quote request_id to support"],
];

export default function Errors() {
  return (
    <Guide slug="errors" lead="Every error is an RFC 9457 problem document with a stable, machine-readable code.">
      <h2>Shape</h2>
      <CodeBlock code={`HTTP/1.1 422 Unprocessable Content
Content-Type: application/problem+json

{
  "type": "https://docs.meridian.example/errors/insufficient_funds",
  "title": "Unprocessable Content",
  "status": 422,
  "code": "insufficient_funds",
  "detail": "Available balance 12.50 USD is below 100.00 USD",
  "instance": "/v1/payments",
  "request_id": "01J9ZK3Q8V7W4X2Y1Z0A5B6C7D",
  "errors": [ { "field": "amount", "code": "insufficient_funds", "message": "exceeds available balance" } ]
}`} />
      <table>
        <thead><tr><th>Field</th><th>Description</th></tr></thead>
        <tbody>
          <tr><td><code>code</code></td><td>Stable snake_case identifier — branch on this, never on <code>detail</code></td></tr>
          <tr><td><code>type</code></td><td>URI <code>https://docs.meridian.example/errors/&lt;code&gt;</code></td></tr>
          <tr><td><code>status</code>, <code>title</code></td><td>HTTP status and its reason phrase</td></tr>
          <tr><td><code>detail</code></td><td>Human-readable explanation (may change; for logs and operators)</td></tr>
          <tr><td><code>request_id</code></td><td>Correlation id — include it when contacting support</td></tr>
          <tr><td><code>errors[]</code></td><td>Per-field problems: <code>field</code> (snake_case), <code>code</code>, <code>message</code></td></tr>
        </tbody>
      </table>

      <h2>Handling</h2>
      <ul>
        <li><strong>4xx</strong>: fix the request; retrying unchanged will fail again — except <code>409 request_in_progress</code>, <code>409 version_conflict</code> (re-read first) and <code>429</code>.</li>
        <li><strong>5xx and network errors</strong>: retry with exponential back-off and the <em>same</em> <code>Idempotency-Key</code>.</li>
        <li>Unknown codes: treat by HTTP status class. New codes may be added within v1.</li>
      </ul>

      <h2>Common codes</h2>
      <table>
        <thead><tr><th>Status</th><th>Code</th><th>Meaning</th></tr></thead>
        <tbody>
          {CODES.map(([s, c, m]) => (
            <tr key={`${s}-${c}`}><td>{s}</td><td>{c === "—" ? "—" : <code>{c}</code>}</td><td>{m}</td></tr>
          ))}
        </tbody>
      </table>
      <p>Domain operations add their own codes (e.g. <code>account_not_active</code>, <code>unbalanced_entry</code>, <code>wallet_not_active</code>);
        they always come with a descriptive <code>detail</code>.</p>
    </Guide>
  );
}
