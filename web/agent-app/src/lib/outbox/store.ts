import type { OutboxRecord } from "./types";

/** Persistence for the outbox: encrypted records plus a small metadata area (salt, verifier, sequence). */
export interface OutboxStore {
  getMeta<T>(key: string): Promise<T | undefined>;
  setMeta(key: string, value: unknown): Promise<void>;
  /** Atomically allocates the next sequence number (strictly increasing, never reused). */
  nextSeq(): Promise<number>;
  put(rec: OutboxRecord): Promise<void>;
  get(id: string): Promise<OutboxRecord | undefined>;
  /** All records in sequence order. */
  all(): Promise<OutboxRecord[]>;
  delete(id: string): Promise<void>;
}

const DB = "meridian-agent";
const VERSION = 1;
const RECORDS = "outbox";
const META = "meta";

function req<T>(r: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}

function done(tx: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error ?? new Error("transaction aborted"));
  });
}

/** IndexedDB-backed store (the browser's origin-scoped database; survives restarts and offline reloads). */
export class IdbOutboxStore implements OutboxStore {
  private db: Promise<IDBDatabase> | null = null;

  constructor(private readonly name = DB, private readonly factory: IDBFactory = globalThis.indexedDB) {}

  private open(): Promise<IDBDatabase> {
    if (!this.db) {
      this.db = new Promise((resolve, reject) => {
        const r = this.factory.open(this.name, VERSION);
        r.onupgradeneeded = () => {
          const db = r.result;
          if (!db.objectStoreNames.contains(RECORDS)) {
            const s = db.createObjectStore(RECORDS, { keyPath: "id" });
            s.createIndex("seq", "seq", { unique: true });
          }
          if (!db.objectStoreNames.contains(META)) db.createObjectStore(META);
        };
        r.onsuccess = () => resolve(r.result);
        r.onerror = () => reject(r.error);
      });
    }
    return this.db;
  }

  async getMeta<T>(key: string): Promise<T | undefined> {
    const db = await this.open();
    return (await req(db.transaction(META).objectStore(META).get(key))) as T | undefined;
  }

  async setMeta(key: string, value: unknown): Promise<void> {
    const db = await this.open();
    const tx = db.transaction(META, "readwrite");
    tx.objectStore(META).put(value, key);
    await done(tx);
  }

  async nextSeq(): Promise<number> {
    const db = await this.open();
    const tx = db.transaction(META, "readwrite");
    const store = tx.objectStore(META);
    let next = 0;
    const r = store.get("seq");
    r.onsuccess = () => {
      next = (typeof r.result === "number" ? r.result : 0) + 1;
      store.put(next, "seq");
    };
    await done(tx);
    return next;
  }

  async put(rec: OutboxRecord): Promise<void> {
    const db = await this.open();
    const tx = db.transaction(RECORDS, "readwrite");
    tx.objectStore(RECORDS).put(rec);
    await done(tx);
  }

  async get(id: string): Promise<OutboxRecord | undefined> {
    const db = await this.open();
    return (await req(db.transaction(RECORDS).objectStore(RECORDS).get(id))) as OutboxRecord | undefined;
  }

  async all(): Promise<OutboxRecord[]> {
    const db = await this.open();
    return (await req(db.transaction(RECORDS).objectStore(RECORDS).index("seq").getAll())) as OutboxRecord[];
  }

  async delete(id: string): Promise<void> {
    const db = await this.open();
    const tx = db.transaction(RECORDS, "readwrite");
    tx.objectStore(RECORDS).delete(id);
    await done(tx);
  }
}

/** In-memory store (tests, and browsers without IndexedDB where offline queuing is then disabled). */
export class MemoryOutboxStore implements OutboxStore {
  readonly records = new Map<string, OutboxRecord>();
  readonly meta = new Map<string, unknown>();

  async getMeta<T>(key: string) { return structuredClone(this.meta.get(key)) as T | undefined; }
  async setMeta(key: string, value: unknown) { this.meta.set(key, structuredClone(value)); }
  async nextSeq() {
    const n = ((this.meta.get("seq") as number | undefined) ?? 0) + 1;
    this.meta.set("seq", n);
    return n;
  }
  async put(rec: OutboxRecord) { this.records.set(rec.id, structuredClone(rec)); }
  async get(id: string) { const r = this.records.get(id); return r && structuredClone(r); }
  async all() { return [...this.records.values()].sort((a, b) => a.seq - b.seq).map((r) => structuredClone(r)); }
  async delete(id: string) { this.records.delete(id); }
}
