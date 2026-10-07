"use client";

import { useQuery } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { useMe, usePermissions } from "@/api/merchant";
import { api, unwrap } from "@/api/client";
import { useT } from "@/lib/i18n";
import { useRun } from "@/components/step-up";
import {
  Async, Badge, Button, Card, DateTime, Empty, ErrorBanner, Field, Input, Money, PageHeader,
} from "@/components/ui";

/** Payment links of the merchant: OWNER / MANAGER create and cancel; VIEWER reads. */
export default function LinksPage() {
  const t = useT();
  const me = useMe();
  const perms = usePermissions();
  const create = useRun();
  const cancel = useRun();
  const [open, setOpen] = useState(false);
  const [amount, setAmount] = useState("");
  const [description, setDescription] = useState("");
  const [reference, setReference] = useState("");
  const [maxUses, setMaxUses] = useState("");
  const [expires, setExpires] = useState("");
  const [copied, setCopied] = useState<string | null>(null);
  const links = useQuery({
    queryKey: ["links"],
    queryFn: async () => unwrap(await api.GET("/v1/merchant/payment-links", { params: { query: { limit: 100 } } })).data,
  });
  const currency = me.data?.merchant?.currency ?? "";

  async function submit(e: FormEvent) {
    e.preventDefault();
    const r = await create.run(async (key) => unwrap(await api.POST("/v1/merchant/payment-links", {
      params: { header: { "Idempotency-Key": key } },
      body: { amount: amount || undefined, description: description.trim(), reference: reference.trim() || undefined,
        max_uses: maxUses ? Number(maxUses) : undefined, expires_at: expires ? new Date(expires).toISOString() : undefined },
    })));
    if (r.ok) {
      setOpen(false);
      setAmount(""); setDescription(""); setReference(""); setMaxUses(""); setExpires("");
    }
  }

  return (
    <>
      <PageHeader title={t("paymentLinks")}
        actions={perms.isManager && !open ? <Button onClick={() => { create.newKey(); setOpen(true); }}>{t("newLink")}</Button> : undefined} />
      {open && (
        <Card title={t("newLink")} className="mb-4">
          <form onSubmit={submit} className="grid gap-4 sm:grid-cols-2">
            <Field label={t("description")}>
              <Input required maxLength={255} value={description} onChange={(e) => setDescription(e.target.value)} />
            </Field>
            <Field label={`${t("amount")}${currency ? ` (${currency})` : ""}`} hint={t("amountOptionalHint")} optional>
              <Input inputMode="decimal" pattern="^\d+(\.\d{1,2})?$" value={amount} onChange={(e) => setAmount(e.target.value)} />
            </Field>
            <Field label={t("reference")} optional>
              <Input maxLength={64} value={reference} onChange={(e) => setReference(e.target.value)} />
            </Field>
            <Field label={t("maxUses")} optional>
              <Input type="number" min={1} value={maxUses} onChange={(e) => setMaxUses(e.target.value)} />
            </Field>
            <Field label={t("expiresAtField")} optional>
              <Input type="datetime-local" value={expires} onChange={(e) => setExpires(e.target.value)} />
            </Field>
            <div className="sm:col-span-2"><ErrorBanner error={create.error} /></div>
            <div className="flex gap-2 sm:col-span-2">
              <Button type="submit" disabled={create.pending}>{create.pending ? t("working") : t("createLink")}</Button>
              <Button variant="secondary" onClick={() => setOpen(false)}>{t("cancel")}</Button>
            </div>
          </form>
        </Card>
      )}
      <ErrorBanner error={cancel.error} className="mb-4" />
      <Async q={links} empty={<Empty title={t("noLinks")} />}>
        {(rows) => (
          <ul className="flex flex-col gap-3">
            {rows.map((l) => (
              <li key={l.id} className="rounded-[var(--radius)] border border-border bg-surface p-4">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="font-semibold">{l.description ?? l.link_code}</p>
                    <p className="text-sm">{l.amount ? <Money amount={l.amount} currency={l.currency} /> : "—"}
                      {" · "}{t("uses")}: {l.use_count}{l.max_uses ? ` / ${l.max_uses}` : ""}</p>
                    {l.url && <p className="truncate text-xs text-muted">{l.url}</p>}
                    {l.expires_at && <p className="text-xs text-muted">{t("expiresAt")}: <DateTime value={l.expires_at} /></p>}
                  </div>
                  <Badge value={l.is_active ? t("active") : t("inactive")} tone={l.is_active ? "good" : "muted"} />
                </div>
                <div className="mt-3 flex flex-wrap gap-2">
                  {l.url && (
                    <Button variant="secondary" size="sm" onClick={() => {
                      void navigator.clipboard?.writeText(l.url ?? "").then(() => setCopied(l.id), () => {});
                    }}>{copied === l.id ? t("copied") : t("copyLink")}</Button>
                  )}
                  {perms.isManager && l.is_active && (
                    <Button variant="danger" size="sm" disabled={cancel.pending} onClick={() => { cancel.newKey(); void cancel.run(async (key) =>
                      unwrap(await api.POST("/v1/merchant/payment-links/{link_id}/cancel",
                        { params: { path: { link_id: l.id }, header: { "Idempotency-Key": key } } }))); }}>
                      {t("cancelLink")}
                    </Button>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </Async>
    </>
  );
}
