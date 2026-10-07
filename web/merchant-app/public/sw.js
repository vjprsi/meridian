/* Meridian Merchant service worker: installable PWA with an app-shell cache ONLY.
 *
 * Cached: the hashed build assets (/_next/static/*), the icons, the web manifest and the /offline page.
 * Never cached: anything under /v1 (sales, payments, refunds, settlements, staff...) and every page's HTML, which
 * may render signed-in data. Navigations always go to the network; only when the device is offline is the static
 * /offline page shown. Payments need a live connection, so there is no offline queue here.
 */
// The page registers /sw.js?v=<build id>: every release installs a fresh worker and drops the old caches.
const VERSION = new URL(self.location.href).searchParams.get("v") || "dev";
const SHELL = `merchant-shell-${VERSION}`;
const STATIC = "merchant-static-v1";
const SHELL_ASSETS = ["/offline", "/manifest.webmanifest", "/icon.svg", "/icons/icon-192.png", "/icons/icon-512.png",
  "/icons/maskable-512.png"];

self.addEventListener("install", (event) => {
  event.waitUntil((async () => {
    const shell = await caches.open(SHELL);
    const stat = await caches.open(STATIC);
    await Promise.all(SHELL_ASSETS.map(async (u) => {
      try {
        const r = await fetch(u, { cache: "no-store", credentials: "omit" });
        if (!r.ok) return;
        if (u === "/offline") {
          // the offline page's own static assets, so it renders with styles while offline
          const html = await r.clone().text();
          for (const m of html.matchAll(/["'(](\/_next\/static\/[^"'()\s]+)["')]/g)) {
            const a = m[1].replace(/\\u0026/g, "&");
            try {
              if (!(await stat.match(a))) {
                const ar = await fetch(a);
                if (ar.ok) await stat.put(a, ar);
              }
            } catch { /* ignore */ }
          }
        }
        await shell.put(u, r);
      } catch { /* offline during install: the next install retries */ }
    }));
    await self.skipWaiting();
  })());
});

self.addEventListener("activate", (event) => {
  event.waitUntil((async () => {
    for (const k of await caches.keys()) {
      if (k !== SHELL && k !== STATIC) await caches.delete(k);
    }
    await self.clients.claim();
  })());
});

self.addEventListener("fetch", (event) => {
  const req = event.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;

  // API: network only, never cached (the server also sends Cache-Control: no-store).
  if (url.pathname.startsWith("/v1/")) return;

  // Hashed, immutable build assets: cache first.
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

  // Pages: network only; the static offline page when the network is unreachable. Page HTML is never stored.
  if (req.mode === "navigate") {
    event.respondWith(fetch(req).catch(async () =>
      (await caches.match("/offline", { cacheName: SHELL })) ?? Response.error()));
    return;
  }

  // Icons and manifest: from the shell cache, else the network.
  if (SHELL_ASSETS.includes(url.pathname)) {
    event.respondWith(caches.match(url.pathname, { cacheName: SHELL }).then((hit) => hit ?? fetch(req)));
  }
  // Everything else (RSC payloads, other files): straight to the network, not cached.
});
