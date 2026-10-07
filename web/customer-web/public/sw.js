/*
 * Meridian customer web: app-shell service worker.
 *
 * Caches ONLY the application shell: build assets under /_next/static (content-hashed, immutable), icons, the
 * manifest and the offline page. Account data is never cached: /v1/* (the API), /auth/*, and every non-GET request
 * bypass the worker entirely, and page navigations are network-only with the offline page as the fallback.
 */
const VERSION = "meridian-shell-v1";
const SHELL = ["/offline", "/icon.svg", "/icon-192.png", "/icon-512.png", "/manifest.webmanifest"];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(VERSION).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== VERSION).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

function isShellAsset(url) {
  return url.pathname.startsWith("/_next/static/") || SHELL.includes(url.pathname);
}

self.addEventListener("fetch", (event) => {
  const req = event.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;
  // Never touch API calls, auth callbacks or anything carrying credentials.
  if (url.pathname.startsWith("/v1/") || url.pathname.startsWith("/auth/") || req.headers.has("Authorization")) return;

  if (req.mode === "navigate") {
    // Pages: always from the network (they render customer data client-side); offline page when unreachable.
    event.respondWith(fetch(req).catch(() => caches.match("/offline").then((r) => r || Response.error())));
    return;
  }

  if (isShellAsset(url)) {
    // Immutable build assets: cache first, fill on miss.
    event.respondWith(
      caches.match(req).then((hit) => hit || fetch(req).then((res) => {
        if (res.ok && res.type === "basic") {
          const copy = res.clone();
          caches.open(VERSION).then((c) => c.put(req, copy));
        }
        return res;
      })),
    );
  }
});
