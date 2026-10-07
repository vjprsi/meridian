import type { Metadata } from "next";
import { Callout, CodeBlock, CodeTabs } from "@/components/code";
import { Guide } from "@/components/guide";

export const metadata: Metadata = { title: "Idempotency keys" };

const RETRY = [
  { label: "Node", code: `import { randomUUID } from "node:crypto";

async function createPayment(body, token) {
  const key = randomUUID();                       // one key per logical operation, reused on every retry
  for (let attempt = 0; attempt < 5; attempt++) {
    const res = await fetch("https://sandbox.api.meridian.example/v1/payments", {
      method: "POST",
      headers: { Authorization: \`Bearer \${token}\`, "Idempotency-Key": key, "Content-Type": "application/json" },
      body: JSON.stringify(body),
    }).catch(() => null);                         // network error: outcome unknown -> retry with the same key
    if (res && res.status !== 409 && res.status < 500) return res;   // 409 request_in_progress: wait and retry
    await new Promise((r) => setTimeout(r, 2 ** attempt * 250));
  }
  throw new Error("payment outcome unknown; retry later with key " + key);
}` },
  { label: "Python", code: `import time, uuid, requests

def create_payment(body, token):
    key = str(uuid.uuid4())   # one key per logical operation, reused on every retry
    for attempt in range(5):
        try:
            r = requests.post("https://sandbox.api.meridian.example/v1/payments", json=body, timeout=30,
                              headers={"Authorization": f"Bearer {token}", "Idempotency-Key": key})
            if r.status_code != 409 and r.status_code < 500:
                return r
        except requests.ConnectionError:
            pass              # outcome unknown -> retry with the same key
        time.sleep(0.25 * 2 ** attempt)
    raise RuntimeError(f"payment outcome unknown; retry later with key {key}")` },
];

export default function Idempotency() {
  return (
    <Guide slug="idempotency" lead="Networks fail. Idempotency keys make every write safe to retry: the platform executes it at most once.">
      <h2>The rule</h2>
      <p>Every <code>POST</code> (create or action) requires an <code>Idempotency-Key</code> header: any unique string of 1–128
        characters; a UUID v4 is ideal. Without it you get <code>400 idempotency_key_required</code>.</p>
      <CodeBlock code={`POST /v1/payments
Idempotency-Key: 4b0c6f9e-6a37-4d0a-9d0b-2f7a5d1c9e11`} />

      <h2>What happens on a retry</h2>
      <table>
        <thead><tr><th>Situation</th><th>Result</th></tr></thead>
        <tbody>
          <tr><td>Same key, same body, first request completed</td><td>The original status and body are returned again with <code>Idempotent-Replayed: true</code>. Nothing is executed twice.</td></tr>
          <tr><td>Same key, first request still running</td><td><code>409 request_in_progress</code> — wait and retry with the same key.</td></tr>
          <tr><td>Same key, different body</td><td><code>422 idempotency_key_reused</code> — a key identifies one logical request.</td></tr>
          <tr><td>First request failed with an error</td><td>The key is released; a retry executes again.</td></tr>
        </tbody>
      </table>
      <p>Keys are scoped to your tenant and remembered for 24 hours. Generate the key <em>once</em> per business operation (e.g. when
        the customer presses &quot;Pay&quot;) and persist it with your own record, so a crash-and-restart retries with the same key.</p>
      <CodeTabs tabs={RETRY} />
      <Callout tone="warn" title="Don't generate a new key per attempt">
        <p>A fresh key on each retry defeats the mechanism and can execute the operation twice.</p>
      </Callout>

      <h2>Optimistic locking with If-Match</h2>
      <p>
        Updates (<code>PATCH</code>) use optimistic concurrency instead. Every resource has a <code>row_version</code>, also returned
        as the <code>ETag</code> header. Send it back in <code>If-Match</code>; if someone changed the resource in between you get{" "}
        <code>409 version_conflict</code> — re-read, re-apply your change and retry.
      </p>
      <CodeBlock code={`GET /v1/webhook-endpoints/0199…            -> 200, ETag: 3, { ..., "row_version": 3 }
PATCH /v1/webhook-endpoints/0199…
If-Match: 3
{ "description": "Orders service" }        -> 200, { ..., "row_version": 4 }`} />
      <table>
        <thead><tr><th>Code</th><th>Meaning</th></tr></thead>
        <tbody>
          <tr><td><code>400 if_match_required</code></td><td>PATCH without <code>If-Match</code></td></tr>
          <tr><td><code>400 invalid_if_match</code></td><td><code>If-Match</code> is not a row version</td></tr>
          <tr><td><code>409 version_conflict</code></td><td>The resource changed since you read it</td></tr>
        </tbody>
      </table>
    </Guide>
  );
}
