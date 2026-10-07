import Link from "next/link";
import type { Metadata } from "next";
import { Callout, CodeBlock, CodeTabs } from "@/components/code";
import { Guide } from "@/components/guide";

export const metadata: Metadata = { title: "Webhooks" };

const VERIFY = [
  { label: "Node", code: `import crypto from "node:crypto";
import express from "express";

const SECRET = process.env.MERIDIAN_WEBHOOK_SECRET;   // whsec_… shown once when the endpoint was created
const TOLERANCE_SECONDS = 300;

function verify(header, rawBody, secret, now = Math.floor(Date.now() / 1000)) {
  if (!header) return false;
  const parts = header.split(",").map((p) => p.trim());
  const t = Number(parts.find((p) => p.startsWith("t="))?.slice(2));
  if (!Number.isFinite(t) || Math.abs(now - t) > TOLERANCE_SECONDS) return false;
  const expected = crypto.createHmac("sha256", secret).update(\`\${t}.\${rawBody}\`, "utf8").digest("hex");
  // During a secret rotation several v1= values are sent (newest first): accept any match.
  return parts.filter((p) => p.startsWith("v1=")).some((p) => {
    const sig = Buffer.from(p.slice(3), "utf8");
    const exp = Buffer.from(expected, "utf8");
    return sig.length === exp.length && crypto.timingSafeEqual(sig, exp);
  });
}

const app = express();
// Verify against the exact bytes received: parse JSON only after the signature check.
app.post("/webhooks/meridian", express.raw({ type: "application/json" }), (req, res) => {
  const body = req.body.toString("utf8");
  if (!verify(req.get("Meridian-Signature"), body, SECRET)) return res.sendStatus(400);
  const event = JSON.parse(body);
  // Deduplicate on event.id (deliveries are at-least-once), then process asynchronously.
  console.log(event.type, event.id, req.get("Meridian-Attempt"));
  res.sendStatus(204);
});
app.listen(8080);` },
  { label: "Python", code: `import hashlib, hmac, os, time
from flask import Flask, request, abort

SECRET = os.environ["MERIDIAN_WEBHOOK_SECRET"]
TOLERANCE_SECONDS = 300
app = Flask(__name__)

def verify(header: str | None, raw_body: bytes, secret: str, now: int | None = None) -> bool:
    if not header:
        return False
    parts = [p.strip() for p in header.split(",")]
    try:
        t = int(next(p[2:] for p in parts if p.startswith("t=")))
    except (StopIteration, ValueError):
        return False
    if abs((now or int(time.time())) - t) > TOLERANCE_SECONDS:
        return False
    expected = hmac.new(secret.encode(), f"{t}.".encode() + raw_body, hashlib.sha256).hexdigest()
    # During a secret rotation several v1= values are sent (newest first): accept any match.
    return any(hmac.compare_digest(expected, p[3:]) for p in parts if p.startswith("v1="))

@app.post("/webhooks/meridian")
def meridian_webhook():
    raw = request.get_data()                       # exact bytes, before any JSON parsing
    if not verify(request.headers.get("Meridian-Signature"), raw, SECRET):
        abort(400)
    event = request.get_json()
    # Deduplicate on event["id"] (at-least-once delivery), then hand off to a queue.
    print(event["type"], event["id"], request.headers.get("Meridian-Attempt"))
    return "", 204` },
  { label: "Java", code: `import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class MeridianWebhookVerifier {
    private static final long TOLERANCE_SECONDS = 300;

    /** header = value of Meridian-Signature, body = raw request body exactly as received. */
    public static boolean verify(String secret, String header, String body, long nowSeconds) throws Exception {
        if (header == null) return false;
        Long t = null;
        for (String part : header.split(",")) {
            if (part.strip().startsWith("t=")) {
                try { t = Long.parseLong(part.strip().substring(2)); } catch (NumberFormatException e) { return false; }
            }
        }
        if (t == null || Math.abs(nowSeconds - t) > TOLERANCE_SECONDS) return false;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expected = HexFormat.of().formatHex(mac.doFinal((t + "." + body).getBytes(StandardCharsets.UTF_8)))
                .getBytes(StandardCharsets.US_ASCII);
        // During a secret rotation several v1= values are sent (newest first): accept any match.
        for (String part : header.split(",")) {
            String p = part.strip();
            if (p.startsWith("v1=") && MessageDigest.isEqual(expected, p.substring(3).getBytes(StandardCharsets.US_ASCII))) {
                return true;
            }
        }
        return false;
    }
}

// Spring MVC: take the body as a String so the bytes are not re-serialised before verification.
// @PostMapping("/webhooks/meridian")
// ResponseEntity<Void> receive(@RequestHeader("Meridian-Signature") String sig, @RequestBody String body) { … }` },
];

export default function Webhooks() {
  return (
    <Guide slug="webhooks" lead="Receive domain events on your HTTPS endpoint as they happen — signed, retried and replayable.">
      <h2>Concepts</h2>
      <table>
        <thead><tr><th>Resource</th><th>API</th><th>What it is</th></tr></thead>
        <tbody>
          <tr><td>Endpoint</td><td><code>/v1/webhook-endpoints</code></td><td>Your HTTPS URL, its signing secret, rate limit and optional custom headers / mTLS</td></tr>
          <tr><td>Subscription</td><td><code>/v1/webhook-subscriptions</code></td><td>An event type for an endpoint: exact (<code>payments.payment.settled</code>), prefix (<code>payments.*</code>) or <code>*</code>, plus an optional filter</td></tr>
          <tr><td>Delivery</td><td><code>/v1/webhook-deliveries</code></td><td>One event to one endpoint, with its HTTP attempts and retry state</td></tr>
          <tr><td>Replay</td><td><code>/v1/event-replays</code></td><td>Re-delivery of past events for a time window (≤ 31 days)</td></tr>
        </tbody>
      </table>
      <p>Set everything up in <Link href="/console/webhooks">Console → Webhooks</Link>; pick event types from the{" "}
        <Link href="/events">event catalogue</Link>.</p>

      <h2>Request format</h2>
      <CodeBlock code={`POST /webhooks/meridian HTTP/1.1
Content-Type: application/json
User-Agent: Meridian-Webhooks/1.0
Meridian-Event-Id: 0199a6f2-7c1e-7b55-9a0e-3f2d4c5b6a71
Meridian-Event-Type: payments.payment.settled
Meridian-Delivery-Id: 0199a6f2-7c1f-7a10-8c3d-5e6f7a8b9c0d
Meridian-Attempt: 1
Meridian-Signature: t=1767268800,v1=5f8c0b1e…

{
  "id": "0199a6f2-7c1e-7b55-9a0e-3f2d4c5b6a71",
  "type": "payments.payment.settled",
  "api_version": "v1",
  "created_at": "2026-01-01T12:00:00Z",
  "delivery_id": "0199a6f2-7c1f-7a10-8c3d-5e6f7a8b9c0d",
  "data": { …the resource as returned by the API… }
}`} />
      <p>Respond with any <strong>2xx</strong> within 10 seconds. Do the real work asynchronously.</p>

      <h2>Verifying signatures</h2>
      <p>
        <code>Meridian-Signature</code> is <code>t=&lt;unix seconds&gt;,v1=&lt;hex HMAC-SHA256(secret, t + &quot;.&quot; + body)&gt;</code>.
        Compute the HMAC over the <em>raw</em> body bytes, compare in constant time and reject timestamps older than a few minutes to stop
        replay attacks. When you rotate the secret (<code>POST /v1/webhook-endpoints/&#123;id&#125;/rotate-secret</code>) the old secret stays valid
        for 24 hours and each request carries one <code>v1=</code> per valid secret, newest first.
      </p>
      <CodeTabs tabs={VERIFY} />

      <h2>Filters</h2>
      <p>A subscription&apos;s <code>filter_expression</code> is a small CEL subset: comparisons <code>path == literal</code> /{" "}
        <code>path != literal</code> joined by <code>&amp;&amp;</code>. Paths are payload fields (a leading <code>data.</code> is optional);
        literals are quoted strings, numbers, <code>true</code>, <code>false</code> or <code>null</code>.</p>
      <CodeBlock code={`data.currency == "USD" && status != "FAILED"`} />

      <h2>Retries, failures and replays</h2>
      <ul>
        <li>Delivery is <strong>at-least-once</strong>; deduplicate on the event <code>id</code>. Events of one aggregate keep their order.</li>
        <li>Failed attempts (non-2xx, timeout, connection error) are retried after 1 m, 5 m, 30 m, 2 h, 5 h, then every 10 h, up to 72 hours; then the delivery is <code>ABANDONED</code>.</li>
        <li>An endpoint with failures is marked <code>FAILING</code>; after 20 consecutive failed attempts it is <code>DISABLED</code>. Fix it, then <code>POST …/enable</code>.</li>
        <li>Retry one delivery now with <code>POST /v1/webhook-deliveries/&#123;id&#125;/retry</code>, inspect attempts (status, latency, response body) under <code>…/attempts</code>.</li>
        <li>Recover from an outage with a replay: <code>POST /v1/event-replays</code> with an endpoint, a time window and optional event types.</li>
      </ul>
      <Callout title="Test before you go live">
        <p><code>POST /v1/webhook-endpoints/&#123;id&#125;/test</code> with an <code>event_type</code> sends a synthetic event
          (<code>{"{ \"test\": true, … }"}</code>) once, synchronously — the console&apos;s <em>Send test event</em> button does exactly that.</p>
      </Callout>
    </Guide>
  );
}
