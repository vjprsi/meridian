"use client";

import { useState, type FormEvent } from "react";
import { useMe, usePermissions, useStores, useTerminals } from "@/api/merchant";
import { api, unwrap } from "@/api/client";
import { useT } from "@/lib/i18n";
import { SaleView } from "@/components/sales";
import { useRun } from "@/components/step-up";
import { Button, Card, Empty, ErrorBanner, Field, Input, LinkButton, PageHeader, Select } from "@/components/ui";

/** New sale: amount (+ reference, terminal) -> dynamic EMVCo QR for the selected store, then live status. */
export default function NewSalePage() {
  const t = useT();
  const me = useMe();
  const perms = usePermissions();
  const { store, selectedId } = useStores();
  const terminals = useTerminals(selectedId);
  const create = useRun();
  const [amount, setAmount] = useState("");
  const [reference, setReference] = useState("");
  const [terminal, setTerminal] = useState("");
  const [saleId, setSaleId] = useState<string | null>(null);
  const currency = me.data?.merchant?.currency ?? "";

  async function submit(e: FormEvent) {
    e.preventDefault();
    const r = await create.run(async (key) => unwrap(await api.POST("/v1/merchant/sales", {
      params: { header: { "Idempotency-Key": key } },
      body: { store_id: selectedId, terminal_id: terminal || undefined, amount, reference: reference.trim() || undefined },
    })));
    if (r.ok) setSaleId(r.data.id);
  }

  function reset() {
    setSaleId(null);
    setAmount("");
    setReference("");
    create.newKey();
  }

  if (perms.loaded && !perms.canSell) {
    return <Empty title={t("code_role_not_allowed")}>{t("code_role_not_allowed_hint")}</Empty>;
  }
  if (!store) {
    return <Empty title={t("selectStore")} action={<LinkButton href="/stores">{t("navStores")}</LinkButton>} />;
  }
  if (saleId) {
    return (
      <>
        <PageHeader title={t("sale")} subtitle={store.name} />
        <SaleView saleId={saleId} onNew={reset} />
      </>
    );
  }
  const active = (terminals.data ?? []).filter((x) => x.status === "ACTIVE");
  return (
    <>
      <PageHeader title={t("newSale")} subtitle={store.name} />
      <Card className="mx-auto max-w-md">
        <form onSubmit={submit} className="flex flex-col gap-4">
          <Field label={`${t("amount")}${currency ? ` (${currency})` : ""}`}>
            <Input name="amount" inputMode="decimal" autoFocus required value={amount} pattern="^\d+(\.\d{1,2})?$"
              onChange={(e) => setAmount(e.target.value)} className="text-2xl font-semibold" placeholder="0.00" />
          </Field>
          <Field label={t("reference")} hint={t("referenceHint")} optional>
            <Input name="reference" maxLength={25} value={reference} onChange={(e) => setReference(e.target.value)} />
          </Field>
          {active.length > 0 && (
            <Field label={t("terminal")} optional>
              <Select value={terminal} onChange={(e) => setTerminal(e.target.value)} placeholder={t("anyTerminal")}
                options={active.map((x) => ({ value: x.id, label: `${x.terminal_id} · ${x.terminal_type}` }))} />
            </Field>
          )}
          <ErrorBanner error={create.error} />
          <Button type="submit" size="lg" disabled={create.pending}>{create.pending ? t("working") : t("createQr")}</Button>
        </form>
      </Card>
    </>
  );
}
