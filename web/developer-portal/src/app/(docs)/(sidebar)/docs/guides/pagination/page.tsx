import type { Metadata } from "next";
import { Callout, CodeBlock, CodeTabs } from "@/components/code";
import { Guide } from "@/components/guide";

export const metadata: Metadata = { title: "Pagination" };

const WALK = [
  { label: "Node", code: `async function* all(path, token) {
  let cursor;
  do {
    const url = new URL(path, "https://sandbox.api.meridian.example");
    url.searchParams.set("limit", "200");
    if (cursor) url.searchParams.set("cursor", cursor);
    const page = await (await fetch(url, { headers: { Authorization: \`Bearer \${token}\` } })).json();
    yield* page.data;
    cursor = page.has_more ? page.next_cursor : undefined;
  } while (cursor);
}

for await (const acct of all("/v1/deposit-accounts?status=ACTIVE", token)) console.log(acct.id);` },
  { label: "Python", code: `def all_items(session, path, **params):
    params = {**params, "limit": 200}
    while True:
        page = session.get(f"https://sandbox.api.meridian.example{path}", params=params).json()
        yield from page["data"]
        if not page["has_more"]:
            return
        params["cursor"] = page["next_cursor"]

for acct in all_items(s, "/v1/deposit-accounts", status="ACTIVE"):
    print(acct["id"])` },
  { label: "Java", code: `String cursor = null;
do {
    var uri = URI.create(base + "/v1/deposit-accounts?status=ACTIVE&limit=200" + (cursor == null ? "" : "&cursor=" + cursor));
    var page = mapper.readTree(http.send(get(uri), BodyHandlers.ofString()).body());
    page.get("data").forEach(a -> System.out.println(a.get("id").asText()));
    cursor = page.get("has_more").asBoolean() ? page.get("next_cursor").asText() : null;
} while (cursor != null);` },
];

export default function Pagination() {
  return (
    <Guide slug="pagination" lead="List endpoints use keyset (cursor) pagination: stable under concurrent inserts and fast at any depth.">
      <h2>Request</h2>
      <table>
        <thead><tr><th>Parameter</th><th>Description</th></tr></thead>
        <tbody>
          <tr><td><code>limit</code></td><td>Page size, 1–200 (default 50)</td></tr>
          <tr><td><code>cursor</code></td><td>The <code>next_cursor</code> of the previous page; omit for the first page</td></tr>
          <tr><td><code>created_after</code>, <code>created_before</code></td><td>Optional ISO-8601 time window on <code>created_at</code></td></tr>
          <tr><td>filters</td><td>Resource-specific filters (status, type, parent id …) — see each list operation</td></tr>
        </tbody>
      </table>

      <h2>Response</h2>
      <CodeBlock code={`{
  "data": [ { "id": "0199a6f2-…", "created_at": "2026-01-01T12:00:00Z", … } ],
  "has_more": true,
  "next_cursor": "eyJpZCI6IjAxOTlhNmYyLi4uIn0"
}`} />
      <p>Items are ordered newest first (by their time-ordered UUIDv7 id). Keep requesting with <code>cursor=next_cursor</code> while{" "}
        <code>has_more</code> is <code>true</code>.</p>
      <CodeTabs tabs={WALK} />
      <Callout title="Cursors are opaque">
        <p>Don&apos;t parse or construct cursors, and keep the same filters while walking the pages. There are no page numbers or total
          counts: to sync incrementally, store the newest <code>created_at</code> you have seen and use <code>created_after</code> next time —
          or better, subscribe to <a href="/docs/guides/webhooks">webhooks</a>.</p>
      </Callout>
    </Guide>
  );
}
