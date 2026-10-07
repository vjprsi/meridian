/**
 * Encryption at rest for the offline outbox (WebCrypto only, no dependencies).
 *
 * The AES-GCM-256 key is derived with PBKDF2-SHA-256 from the agent's app PIN and a per-device random salt that is
 * also bound to the tenant and agent (so the same PIN on another agent profile yields a different key). The key is
 * created non-extractable and lives only in memory while the app is unlocked; nothing that could rebuild it is
 * stored. Every record is encrypted with a fresh 96-bit IV and its record id as additional authenticated data, so a
 * ciphertext cannot be moved onto another record unnoticed.
 */

export const PBKDF2_ITERATIONS = 210_000;
const enc = new TextEncoder();
const dec = new TextDecoder();

export interface Sealed {
  iv: string; // base64
  ct: string; // base64 (ciphertext + GCM tag)
}

function subtle(): SubtleCrypto {
  const c = globalThis.crypto;
  if (!c?.subtle) throw new Error("WebCrypto is not available (the app must run on https or localhost)");
  return c.subtle;
}

export function toB64(bytes: ArrayBuffer | Uint8Array): string {
  const u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let s = "";
  for (let i = 0; i < u8.length; i++) s += String.fromCharCode(u8[i]);
  return btoa(s);
}

export function fromB64(b64: string): Uint8Array<ArrayBuffer> {
  const s = atob(b64);
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}

export function randomBytes(n: number): Uint8Array<ArrayBuffer> {
  const b = new Uint8Array(n);
  globalThis.crypto.getRandomValues(b);
  return b;
}

/** Derives the outbox key from the PIN, the stored random salt and the agent binding (tenant + agent). */
export async function deriveKey(pin: string, salt: Uint8Array<ArrayBuffer>, binding: string,
  iterations = PBKDF2_ITERATIONS): Promise<CryptoKey> {
  if (!pin) throw new Error("PIN required");
  const material = await subtle().importKey("raw", enc.encode(pin), "PBKDF2", false, ["deriveKey"]);
  const bound = new Uint8Array(salt.length + enc.encode(binding).length);
  bound.set(salt, 0);
  bound.set(enc.encode(binding), salt.length);
  return subtle().deriveKey({ name: "PBKDF2", hash: "SHA-256", salt: bound, iterations }, material,
    { name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
}

export async function seal(key: CryptoKey, value: unknown, aad: string): Promise<Sealed> {
  const iv = randomBytes(12);
  const ct = await subtle().encrypt({ name: "AES-GCM", iv, additionalData: enc.encode(aad) }, key,
    enc.encode(JSON.stringify(value)));
  return { iv: toB64(iv), ct: toB64(ct) };
}

/** Decrypts; throws if the key is wrong, the data was altered, or the record id (AAD) does not match. */
export async function open<T>(key: CryptoKey, sealed: Sealed, aad: string): Promise<T> {
  const pt = await subtle().decrypt({ name: "AES-GCM", iv: fromB64(sealed.iv), additionalData: enc.encode(aad) }, key,
    fromB64(sealed.ct));
  return JSON.parse(dec.decode(pt)) as T;
}

/** Hex SHA-256 (document checksums, offline batch payload digests). */
export async function sha256Hex(data: ArrayBuffer | Uint8Array<ArrayBuffer> | string): Promise<string> {
  const bytes = typeof data === "string" ? enc.encode(data) : data;
  const h = new Uint8Array(await subtle().digest("SHA-256", bytes));
  return Array.from(h, (b) => b.toString(16).padStart(2, "0")).join("");
}
