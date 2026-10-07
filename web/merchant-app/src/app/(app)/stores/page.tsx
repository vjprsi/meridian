"use client";

import { useRouter } from "next/navigation";
import { useStores } from "@/api/merchant";
import { useT } from "@/lib/i18n";
import { setSelectedStore } from "@/lib/store";
import { Badge, Empty, Loading, PageHeader, cx, focusRing } from "@/components/ui";

/** Store picker: only the stores the server returned for this user (GET /v1/merchant/me). */
export default function StoresPage() {
  const t = useT();
  const router = useRouter();
  const { stores, selectedId, loaded } = useStores();
  if (!loaded) return <Loading rows={3} />;
  return (
    <>
      <PageHeader title={t("storePicker")} subtitle={t("storePickerHint")} />
      {stores.length === 0 ? <Empty title={t("noStores")}>{t("noStoresHint")}</Empty> : (
        <ul className="grid gap-3 sm:grid-cols-2" data-testid="store-list">
          {stores.map((s) => (
            <li key={s.id}>
              <button type="button" onClick={() => { setSelectedStore(s.id); router.replace("/"); }}
                aria-pressed={s.id === selectedId}
                className={cx("flex min-h-16 w-full items-center justify-between gap-3 rounded-[var(--radius)] border bg-surface px-4 py-3 text-left",
                  focusRing, s.id === selectedId ? "border-accent ring-2 ring-accent" : "border-border hover:bg-surface-2")}>
                <span>
                  <span className="block font-semibold">{s.name}</span>
                  <span className="block text-xs text-muted">{s.store_code}{s.city ? ` · ${s.city}` : ""}</span>
                </span>
                <Badge value={s.status} />
              </button>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
