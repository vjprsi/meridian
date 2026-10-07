import { describe, expect, it } from "vitest";
import { batchPayload, proofPayload } from "../src/lib/device-key";

/**
 * The canonical texts the device signs must match agent-channel-service (DeviceKeys) byte for byte; the same vectors
 * are asserted in services/agent-channel-service DeviceKeysTest.
 */
describe("device signature payloads", () => {
  it("offline batch text matches the server's canonical form", async () => {
    expect(await batchPayload({
      tenant: "0199a000-0000-7000-8000-00000000000a", agent: "0199a000-0000-7000-8000-0000000a0001",
      device: "0199a000-0000-7000-8000-0000000d0001", idempotencyKey: "agentapp-1",
      capturedFrom: "2026-10-07T09:30:15.123Z", capturedTo: "2026-10-07T09:30:15.123Z", applicationCount: 1,
      payloadUri: "data:application/gzip;base64,AAAA",
    })).toBe([
      "MERIDIAN-AGENT-OFFLINE-BATCH-V1",
      "tenant=0199a000-0000-7000-8000-00000000000a",
      "agent=0199a000-0000-7000-8000-0000000a0001",
      "device=0199a000-0000-7000-8000-0000000d0001",
      "idempotency_key=agentapp-1",
      "captured_from=1791365415123",
      "captured_to=1791365415123",
      "application_count=1",
      "payload_sha256=1bd2859158185eecd7d4bf270d3191f614e04db90383261a894328f96e5dcebe",
    ].join("\n"));
  });

  it("device proof text matches the server's canonical form", () => {
    expect(proofPayload({
      tenant: "0199a000-0000-7000-8000-00000000000a", agent: "0199a000-0000-7000-8000-0000000a0001",
      device: "0199a000-0000-7000-8000-0000000d0001", method: "POST", path: "/v1/agent/cash-in",
      idempotencyKey: "agentapp-2", ts: 1791365415,
    })).toBe("MERIDIAN-AGENT-DEVICE-PROOF-V1\ntenant=0199a000-0000-7000-8000-00000000000a\n"
      + "agent=0199a000-0000-7000-8000-0000000a0001\ndevice=0199a000-0000-7000-8000-0000000d0001\nmethod=POST\n"
      + "path=/v1/agent/cash-in\nidempotency_key=agentapp-2\ntimestamp=1791365415");
  });

  it("an ES256 signature made with WebCrypto is IEEE P1363 (r||s, 64 bytes)", async () => {
    const pair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, false, ["sign", "verify"]) as CryptoKeyPair;
    const text = new TextEncoder().encode(proofPayload({ tenant: "t", agent: "a", device: "d", method: "POST",
      path: "/v1/agent/cash-in", idempotencyKey: "k", ts: 1 }));
    const sig = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, pair.privateKey, text);
    expect(sig.byteLength).toBe(64);
    expect(await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, pair.publicKey, sig, text)).toBe(true);
    expect(pair.privateKey.extractable).toBe(false);
  });
});
