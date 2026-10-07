"use client";

import { Icon, PageTitle, Tile } from "@/components/ui";
import { useT } from "@/lib/i18n";
import { useOutbox } from "@/lib/outbox/runtime";

export default function MorePage() {
  const t = useT();
  const ob = useOutbox();
  return (
    <div className="flex flex-col gap-4">
      <PageTitle>{t("more")}</PageTitle>
      <div className="grid grid-cols-2 gap-3">
        <Tile href="/bills" label={t("bills")} icon={Icon.bill} />
        <Tile href="/airtime" label={t("airtime")} icon={Icon.phone} />
        <Tile href="/onboarding" label={t("onboarding")} icon={Icon.user} />
        <Tile href="/float" label={t("float")} icon={Icon.float} />
        <Tile href="/till" label={t("till")} icon={Icon.till} />
        <Tile href="/commissions" label={t("commissions")} icon={Icon.coin} />
        <Tile href="/sync" label={t("sync")} icon={Icon.sync}
          badge={ob.pending > 0 ? <span className="absolute right-2 top-2 rounded-full bg-warn px-2 text-sm font-bold text-white">{ob.pending}</span> : undefined} />
        <Tile href="/settings" label={t("settings")} icon={Icon.gear} />
      </div>
    </div>
  );
}
