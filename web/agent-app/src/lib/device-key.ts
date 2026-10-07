"use client";

import { fromB64, sha256Hex, toB64 } from "./outbox/crypto";

/**
 * Device binding. At setup the app generates an ECDSA P-256 key pair with WebCrypto (ES256). The private key is
 * created non-extractable and kept as a CryptoKey object in IndexedDB: script can ask the browser to sign with it, but
 * the key material can never be read, exported or copied to another device. Only the public key (SubjectPublicKeyInfo,
 * base64) is registered with the agent channel (POST /v1/agent/devices).
 *
 * Uses: offline onboarding batches are signed at capture over {@link batchPayload}; money operations replayed by the
 * outbox (when the sign-in is older than the step-up window) carry an X-Agent-Device-Proof over {@link proofPayload}.
 * Both texts must match agent-channel-service (DeviceKeys) byte for byte. The app signs only while it is unlocked with
 * the app PIN ({@link setSigningUnlocked}).
 */
export type KeyAlgorithm = "ES256";

const DB = "meridian-agent-keys";
const STORE = "keys";
const SLOT = "device";
const ECDSA = { name: "ECDSA", namedCurve: "P-256" } as const;

interface Stored { pair: CryptoKeyPair; publicKey: string; algorithm: KeyAlgorithm; createdAt: string }

let unlocked = false;

/** The outbox runtime opens and closes the signing gate together with the app PIN. */
export function setSigningUnlocked(v: boolean) {
  unlocked = v;
}

function db(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const r = indexedDB.open(DB, 1);
    r.onupgradeneeded = () => r.result.createObjectStore(STORE);
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}

async function read(): Promise<Stored | null> {
  const d = await db();
  return new Promise((resolve, reject) => {
    const g = d.transaction(STORE).objectStore(STORE).get(SLOT);
    g.onsuccess = () => resolve((g.result as Stored | undefined) ?? null);
    g.onerror = () => reject(g.error);
  });
}

async function write(v: Stored): Promise<void> {
  const d = await db();
  return new Promise((resolve, reject) => {
    const tx = d.transaction(STORE, "readwrite");
    tx.objectStore(STORE).put(v, SLOT);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}

/** Generates a fresh key pair (non-extractable private key) and stores it; returns the public key to register. */
export async function newDeviceKey(): Promise<{ publicKey: string; algorithm: KeyAlgorithm }> {
  const pair = await crypto.subtle.generateKey(ECDSA, false, ["sign", "verify"]) as CryptoKeyPair;
  const spki = await crypto.subtle.exportKey("spki", pair.publicKey);   // public keys are always exportable
  const stored: Stored = { pair, publicKey: toB64(spki), algorithm: "ES256", createdAt: new Date().toISOString() };
  await write(stored);
  return { publicKey: stored.publicKey, algorithm: stored.algorithm };
}

/** The stored device key's public half, or a new key when this installation has none yet. */
export async function deviceKey(): Promise<{ publicKey: string; algorithm: KeyAlgorithm }> {
  const s = await read().catch(() => null);
  return s ? { publicKey: s.publicKey, algorithm: s.algorithm } : newDeviceKey();
}

function b64url(bytes: ArrayBuffer): string {
  return toB64(bytes).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Signs text with the device key (IEEE P1363 r||s, base64url). Refused while the app is locked. */
export async function sign(text: string): Promise<string> {
  if (!unlocked) throw new Error("The app is locked; unlock it with the app PIN to sign");
  const s = await read();
  if (!s) throw new Error("This device has no signing key; register the device again in Settings");
  const sig = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, s.pair.privateKey,
    new TextEncoder().encode(text));
  return b64url(sig);
}

/** Canonical text of an offline batch (agent-channel-service DeviceKeys.batchPayload). */
export async function batchPayload(o: { tenant: string; agent: string; device: string; idempotencyKey: string;
  capturedFrom: string; capturedTo: string; applicationCount: number; payloadUri: string }): Promise<string> {
  return [
    "MERIDIAN-AGENT-OFFLINE-BATCH-V1",
    `tenant=${o.tenant}`,
    `agent=${o.agent}`,
    `device=${o.device}`,
    `idempotency_key=${o.idempotencyKey}`,
    `captured_from=${Date.parse(o.capturedFrom)}`,
    `captured_to=${Date.parse(o.capturedTo)}`,
    `application_count=${o.applicationCount}`,
    `payload_sha256=${await sha256Hex(o.payloadUri)}`,
  ].join("\n");
}

/** Canonical text of a device proof (agent-channel-service DeviceKeys.proofPayload). */
export function proofPayload(o: { tenant: string; agent: string; device: string; method: string; path: string;
  idempotencyKey: string; ts: number }): string {
  return [
    "MERIDIAN-AGENT-DEVICE-PROOF-V1",
    `tenant=${o.tenant}`,
    `agent=${o.agent}`,
    `device=${o.device}`,
    `method=${o.method}`,
    `path=${o.path}`,
    `idempotency_key=${o.idempotencyKey}`,
    `timestamp=${o.ts}`,
  ].join("\n");
}

/** X-Agent-Device-Proof header value for one request, or null when the device cannot sign (no key, locked). */
export async function deviceProof(o: { tenant: string; agent: string; device: string; method: string; path: string;
  idempotencyKey: string }): Promise<string | null> {
  try {
    const ts = Math.floor(Date.now() / 1000);
    return `v1.${o.device}.${ts}.${await sign(proofPayload({ ...o, ts }))}`;
  } catch {
    return null;
  }
}

/** Test hook: whether a base64 public key parses (used by the settings screen to show the key fingerprint). */
export async function fingerprint(publicKey: string): Promise<string> {
  return (await sha256Hex(fromB64(publicKey))).slice(0, 16);
}
