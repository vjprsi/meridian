/* Meridian Agent service worker: app shell offline, reference data cache, background-sync wake-up.
 *
 * - App shell: every route's HTML plus the hashed /_next/static assets it references are precached at install, so
 *   the app starts and navigates with no network. Navigations are network-first (fresh release when online) with the
 *   cached shell as fallback.
 * - Reference data (biller catalogue, products, onboarding journeys, the agent's own profile/outlets) is
 *   network-first with a cached fallback, so bills, airtime and onboarding can be captured offline.
 * - Nothing else from /v1 is cached: balances, customers and transactions never touch Cache Storage.
 * - Money operations are NOT handled here. They live in the page's encrypted IndexedDB outbox, whose key exists
 *   only in the unlocked page; a background "sync" event therefore wakes open windows to replay it.
 */
// The page registers /sw.js?v=<build id>: every release installs a fresh worker and drops the old caches.
const VERSION = new URL(self.location.href).searchParams.get("v") || "dev";
const SHELL = `shell-${VERSION}`;
const RSC = `rsc-${VERSION}`;
const STATIC = "static-v1";
const REF = "ref-v2";
const ROUTES = ["/", "/cash-in", "/cash-out", "/bills", "/airtime", "/onboarding", "/float", "/till", "/commissions",
  "/history", "/receipt", "/sync", "/settings", "/more", "/login", "/setup", "/auth/callback"];
const ASSETS = ["/manifest.webmanifest", "/icons/icon-192.png", "/icons/icon-512.png", "/icons/maskable-512.png"];
// Agent channel (/v1/agent) reference data only.
const REF_PATTERNS = [
  /^\/v1\/agent\/billers(\/[^/]+\/products)?$/,
  /^\/v1\/agent\/onboarding\/journeys$/,
  /^\/v1\/agent\/me(\/outlets|\/floats)?$/,
];

async function precache() {
  const shell = await caches.open(SHELL);
  const stat = await caches.open(STATIC);
  const assets = new Set();
  await Promise.all(ROUTES.map(async (route) => {
    try {
      const res = await fetch(route, { cache: "no-store", credentials: "same-origin" });
      if (!res.ok) return;
      const html = await res.clone().text();
      await shell.put(route, res);
      for (const m of html.matchAll(/["'(](\/_next\/static\/[^"'()\s]+)["')]/g)) assets.add(m[1].replace(/\\u0026/g, "&"));
    } catch { /* offline during install: next install retries */ }
  }));
  await Promise.all([...assets].map(async (u) => {
    if (await stat.match(u)) return;
    try {
      const r = await fetch(u);
      if (r.ok) await stat.put(u, r);
    } catch { /* ignore */ }
  }));
  await Promise.all(ASSETS.map(async (u) => {
    try {
      const r = await fetch(u);
      if (r.ok) await shell.put(u, r);
    } catch { /* ignore */ }
  }));
}

self.addEventListener("install", (event) => {
  event.waitUntil(precache().then(() => self.skipWaiting()));
});

self.addEventListener("activate", (event) => {
  event.waitUntil((async () => {
    for (const k of await caches.keys()) {
      if ((k.startsWith("shell-") && k !== SHELL) || (k.startsWith("rsc-") && k !== RSC)
        || (k.startsWith("ref-") && k !== REF)) await caches.delete(k);
    }
    await self.clients.claim();
  })());
});

async function networkFirst(req, cacheName, key, timeoutMs) {
  const cache = await caches.open(cacheName);
  try {
    const res = await (timeoutMs
      ? Promise.race([fetch(req), new Promise((_, rej) => setTimeout(() => rej(new Error("timeout")), timeoutMs))])
      : fetch(req));
    if (res.ok) await cache.put(key ?? req, res.clone());
    return res;
  } catch (e) {
    const hit = await cache.match(key ?? req, { ignoreVary: true });
    if (hit) return hit;
    throw e;
  }
}

self.addEventListener("fetch", (event) => {
  const req = event.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;

  if (url.pathname.startsWith("/_next/static/")) {
    event.respondWith(caches.open(STATIC).then(async (c) => {
      const hit = await c.match(req);
      if (hit) return hit;
      const res = await fetch(req);
      if (res.ok) await c.put(req, res.clone());
      return res;
    }));
    return;
  }

  if (url.pathname.startsWith("/v1/")) {
    if (req.headers.get("X-Probe")) return;
    if (REF_PATTERNS.some((p) => p.test(url.pathname))) {
      event.respondWith(networkFirst(req, REF, url.pathname + url.search, 6000).catch(() => Response.error()));
    }
    return; // everything else from the API: network only, never cached
  }

  // React Server Component payloads (client-side navigations and prefetches). Their URL carries an _rsc hash of
  // the router-state headers, so a cached copy is only ever reused for the identical request. Caching them keeps
  // in-app navigation working offline without a full reload (which would lock the outbox again).
  if (req.headers.get("RSC") === "1" || url.searchParams.has("_rsc")) {
    event.respondWith(networkFirst(req, RSC, req.url, 5000).catch(() => Response.error()));
    return;
  }

  if (req.mode === "navigate") {
    event.respondWith((async () => {
      try {
        return await networkFirst(req, SHELL, url.pathname, 5000);
      } catch {
        const shell = await caches.open(SHELL);
        return (await shell.match(url.pathname)) ?? (await shell.match("/")) ?? Response.error();
      }
    })());
    return;
  }

  // Icons, manifest and other small same-origin files: cache, refresh in the background.
  event.respondWith(caches.open(SHELL).then(async (c) => {
    const hit = await c.match(req, { ignoreSearch: true });
    const refresh = fetch(req).then((r) => { if (r.ok) void c.put(req, r.clone()); return r; }).catch(() => hit ?? Response.error());
    return hit ?? refresh;
  }));
});

self.addEventListener("message", (event) => {
  if (event.data?.type === "clear-ref") event.waitUntil(caches.delete(REF));
});

// Background Sync (where supported): the outbox key lives only in the unlocked page, so wake open windows.
self.addEventListener("sync", (event) => {
  if (event.tag !== "meridian-outbox") return;
  event.waitUntil((async () => {
    const wins = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    for (const w of wins) w.postMessage({ type: "meridian:sync" });
  })());
});
