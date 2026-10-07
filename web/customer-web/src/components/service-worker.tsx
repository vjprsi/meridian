"use client";

import { useEffect } from "react";

/**
 * Registers public/sw.js in production builds. The worker caches only the app shell (static assets, icons, the
 * offline page); API responses under /v1 are never cached (see sw.js).
 */
export function ServiceWorker() {
  useEffect(() => {
    if (process.env.NODE_ENV !== "production" || !("serviceWorker" in navigator)) return;
    navigator.serviceWorker.register("/sw.js", { scope: "/", updateViaCache: "none" }).catch(() => {
      /* the app works without it */
    });
  }, []);
  return null;
}
