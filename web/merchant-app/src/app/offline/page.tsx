"use client";

import { useT } from "@/lib/i18n";

/** Shown by the service worker when a page is opened without a connection (the only cached page). */
export default function OfflinePage() {
  const t = useT();
  return (
    <main id="main" className="mx-auto flex min-h-screen max-w-md flex-col justify-center gap-3 px-4 text-center">
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src="/icon.svg" alt="" width={56} height={56} className="mx-auto" />
      <h1 className="text-xl font-semibold">{t("offlineTitle")}</h1>
      <p className="text-sm text-muted">{t("offlineBody")}</p>
    </main>
  );
}
