"use client";

import { useParams } from "next/navigation";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { idem, me, unwrap } from "@/api/client";
import { mine, qk, useCustomerId } from "@/api/me";
import type { Card as CardT, CardControl, CardSpendLimit } from "@/api/types";
import { AMOUNT } from "@/components/payment-flow";
import { StepUpConfirm } from "@/components/step-up";
import {
  Async, Badge, Button, Card, Dialog, Empty, ErrorBanner, Field, Input, Money, Notice, PageHeader, Select, Summary,
} from "@/components/ui";
import { useT } from "@/lib/i18n";
import type { MessageKey } from "@/messages/en";

const CHANNELS: { channel: CardControl["channel"]; label: MessageKey }[] = [
  { channel: "ECOM", label: "cards.chEcom" },
  { channel: "CONTACTLESS", label: "cards.chContactless" },
  { channel: "ATM", label: "cards.chAtm" },
  { channel: "POS", label: "cards.chPos" },
];

export default function CardDetail() {
  const t = useT();
  const c = useCustomerId();
  const qc = useQueryClient();
  const { id } = useParams<{ id: string }>();
  const card = useQuery({
    queryKey: ["card", id], enabled: !!c,
    queryFn: () => mine(async () => unwrap(await me.GET("/v1/me/cards/{card_id}", { params: { path: { card_id: id } } }))),
  });
  const refreshCard = (next?: CardT) => {
    if (next) qc.setQueryData(["card", id], next);
    qc.invalidateQueries({ queryKey: qk.cards(c) });
  };

  const [confirmUnfreeze, setConfirmUnfreeze] = useState(false);
  const [lost, setLost] = useState(false);
  const [lostReason, setLostReason] = useState<"LOST" | "STOLEN">("LOST");
  const [replace, setReplace] = useState(true);

  const freeze = useMutation({
    mutationFn: async () => unwrap(await me.POST("/v1/me/cards/{card_id}/freeze", { params: { path: { card_id: id }, header: idem() } })),
    onSuccess: refreshCard,
  });
  const unfreeze = useMutation({
    mutationFn: async () => unwrap(await me.POST("/v1/me/cards/{card_id}/unfreeze", { params: { path: { card_id: id }, header: idem() } })),
    onSuccess: (n) => { setConfirmUnfreeze(false); refreshCard(n); },
  });
  const report = useMutation({
    // one call: the channel blocks the card as lost / stolen and, if asked, orders the replacement
    mutationFn: async () => ({
      blocked: unwrap(await me.POST("/v1/me/cards/{card_id}/report-lost", {
        params: { path: { card_id: id }, header: idem() }, body: { reason: lostReason, replace } })),
      replacement: replace,
    }),
    onSuccess: (r) => { setLost(false); refreshCard(r.blocked); },
  });

  return (
    <>
      <PageHeader title={t("cards.detail")} back={{ href: "/cards", label: t("nav.cards") }} />
      <Async q={card}>
        {(cd) => {
          const usable = cd.status === "ACTIVE" || cd.status === "FROZEN";
          return (
            <div className="flex flex-col gap-5">
              <Card>
                <div className="mb-4 flex aspect-[1.586] w-full max-w-sm flex-col justify-between rounded-2xl bg-gradient-to-br from-[#0b5cad] to-[#06345f] p-5 text-white"
                  role="img" aria-label={t("cards.visual", { last4: cd.last4 ?? "", name: cd.cardholder_name })}>
                  <span className="text-sm font-semibold">Meridian · {cd.form_factor.toLowerCase()}</span>
                  <span className="font-mono text-xl tracking-widest">•••• •••• •••• {cd.last4 ?? "····"}</span>
                  <span className="flex justify-between text-sm">
                    <span>{cd.cardholder_name}</span>
                    <span className="font-mono">{String(cd.expiry_month ?? "").padStart(2, "0")}/{String(cd.expiry_year ?? "").slice(-2)}</span>
                  </span>
                </div>
                <Summary items={[
                  { label: t("cards.status"), value: <Badge value={cd.status} /> },
                  { label: t("cards.type"), value: cd.form_factor.toLowerCase() },
                  { label: t("cards.linked"), value: cd.linked_account_type.replaceAll("_", " ").toLowerCase() },
                  { label: t("cards.pin"), value: cd.pin_set ? t("cards.pinSet") : t("cards.pinNotSet") },
                ]} />
                <p className="mt-3 text-xs text-muted">{t("cards.panNote")}</p>
                <div className="mt-4 flex flex-wrap gap-2">
                  {cd.status === "ACTIVE" && (
                    <Button onClick={() => freeze.mutate()} disabled={freeze.isPending}>{freeze.isPending ? t("common.working") : t("cards.freeze")}</Button>
                  )}
                  {cd.status === "FROZEN" && <Button onClick={() => setConfirmUnfreeze(true)}>{t("cards.unfreeze")}</Button>}
                  {usable && <Button variant="danger" onClick={() => setLost(true)}>{t("cards.reportLost")}</Button>}
                </div>
                <div className="mt-3 space-y-2">
                  <ErrorBanner error={freeze.error} />
                  {report.data?.replacement && <Notice tone="good">{t("cards.replacementOrdered")}</Notice>}
                </div>
              </Card>
              {usable && <Controls cardId={cd.id!} />}
              {usable && <Limits cardId={cd.id!} />}
              <StepUpConfirm open={confirmUnfreeze} onClose={() => setConfirmUnfreeze(false)} title={t("cards.unfreeze")}
                confirmLabel={t("cards.unfreeze")} onConfirm={() => unfreeze.mutate()} pending={unfreeze.isPending} error={unfreeze.error}
                items={[{ label: t("cards.card"), value: `•••• ${cd.last4 ?? ""}` }]} />
              <Dialog open={lost} onClose={() => setLost(false)} title={t("cards.reportLost")}
                footer={<>
                  <Button variant="secondary" onClick={() => setLost(false)}>{t("common.cancel")}</Button>
                  <Button variant="danger" disabled={report.isPending} onClick={() => report.mutate()}>
                    {report.isPending ? t("common.working") : t("cards.blockNow")}
                  </Button>
                </>}>
                <p>{t("cards.reportBody")}</p>
                <fieldset className="mt-4">
                  <legend className="font-medium">{t("cards.what")}</legend>
                  <div className="mt-2 flex gap-4">
                    {(["LOST", "STOLEN"] as const).map((r) => (
                      <label key={r} className="flex min-h-11 items-center gap-2">
                        <input type="radio" name="lost-reason" value={r} checked={lostReason === r} onChange={() => setLostReason(r)}
                          className="h-5 w-5 accent-[var(--accent)]" />
                        {t(r === "LOST" ? "cards.lost" : "cards.stolen")}
                      </label>
                    ))}
                  </div>
                </fieldset>
                <label className="mt-3 flex min-h-11 items-center gap-2">
                  <input type="checkbox" checked={replace} onChange={(e) => setReplace(e.target.checked)} className="h-5 w-5 accent-[var(--accent)]" />
                  {t("cards.orderReplacement")}
                </label>
                <ErrorBanner error={report.error} className="mt-3" />
              </Dialog>
            </div>
          );
        }}
      </Async>
    </>
  );
}

function Controls({ cardId }: { cardId: string }) {
  const t = useT();
  const qc = useQueryClient();
  const q = useQuery({
    queryKey: ["card-controls", cardId],
    queryFn: async () => unwrap(await me.GET("/v1/me/cards/{card_id}/controls", {
      params: { path: { card_id: cardId }, query: { limit: 100 } } })).data,
  });
  const toggle = useMutation({
    mutationFn: async ({ channel, block }: { channel: CardControl["channel"]; block: boolean }) =>
      unwrap(await me.PUT("/v1/me/cards/{card_id}/channels/{channel}", {
        params: { path: { card_id: cardId, channel }, header: idem() }, body: { blocked: block } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["card-controls", cardId] }),
  });
  return (
    <Card title={t("cards.controls")}>
      <Async q={q}>
        {(rows) => (
          <fieldset>
            <legend className="sr-only">{t("cards.controls")}</legend>
            <ul className="divide-y divide-border">
              {CHANNELS.map(({ channel, label }) => {
                const blocked = rows.some((x) => x.control_type === "CHANNEL_DENY" && x.channel === channel && x.is_active);
                return (
                  <li key={channel} className="flex min-h-14 items-center justify-between gap-3">
                    <span id={`ch-${channel}`}>{t(label)}</span>
                    <button type="button" role="switch" aria-checked={!blocked} aria-labelledby={`ch-${channel}`}
                      disabled={toggle.isPending} onClick={() => toggle.mutate({ channel, block: !blocked })}
                      className="relative inline-flex h-8 w-14 shrink-0 items-center rounded-full border border-border-strong bg-surface-2 transition aria-checked:bg-accent focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-focus disabled:opacity-60">
                      <span aria-hidden="true" className={`inline-block h-6 w-6 rounded-full bg-surface shadow transition ${blocked ? "translate-x-1" : "translate-x-7"}`} />
                    </button>
                  </li>
                );
              })}
            </ul>
          </fieldset>
        )}
      </Async>
      <ErrorBanner error={toggle.error} className="mt-3" />
    </Card>
  );
}

function Limits({ cardId }: { cardId: string }) {
  const t = useT();
  const qc = useQueryClient();
  const q = useQuery({
    queryKey: ["card-limits", cardId],
    queryFn: async () => unwrap(await me.GET("/v1/me/cards/{card_id}/limits", {
      params: { path: { card_id: cardId }, query: { limit: 100 } } })).data,
  });
  const [editing, setEditing] = useState<CardSpendLimit | null>(null);
  const [amount, setAmount] = useState("");
  const [adding, setAdding] = useState(false);
  const [newLimit, setNewLimit] = useState({ period: "DAILY" as CardSpendLimit["period"], channel: "ANY" as CardSpendLimit["channel"], max_amount: "", currency: "KES" });
  const done = () => { setEditing(null); setAdding(false); qc.invalidateQueries({ queryKey: ["card-limits", cardId] }); };
  const save = useMutation({
    mutationFn: async () => unwrap(await me.PUT("/v1/me/cards/{card_id}/limits", {
      params: { path: { card_id: cardId }, header: idem() },
      body: { period: editing!.period, channel: editing!.channel, max_amount: amount } })),
    onSuccess: done,
  });
  const create = useMutation({
    mutationFn: async () => unwrap(await me.PUT("/v1/me/cards/{card_id}/limits", {
      params: { path: { card_id: cardId }, header: idem() }, body: { ...newLimit, currency: newLimit.currency.toUpperCase() } })),
    onSuccess: done,
  });
  return (
    <Card title={t("cards.limits")} actions={<Button variant="secondary" onClick={() => setAdding(true)}>{t("cards.addLimit")}</Button>}>
      <Async q={q} empty={<Empty title={t("cards.noLimits")} />}>
        {(rows) => (
          <ul className="divide-y divide-border">
            {rows.map((l) => (
              <li key={l.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
                <span>
                  <span className="block font-medium">{t(`period.${l.period}`)} · {l.channel.toLowerCase()}</span>
                  <span className="block text-sm text-muted">
                    {t("cards.used")} <Money amount={l.used_amount ?? "0"} currency={l.currency} /> / <Money amount={l.max_amount} currency={l.currency} />
                  </span>
                </span>
                <Button variant="secondary" onClick={() => { setEditing(l); setAmount(l.max_amount ?? ""); }}>{t("common.edit")}</Button>
              </li>
            ))}
          </ul>
        )}
      </Async>
      <StepUpConfirm open={!!editing} onClose={() => setEditing(null)} title={t("cards.editLimit")} confirmLabel={t("common.save")}
        onConfirm={() => { if (AMOUNT.test(amount)) save.mutate(); }} pending={save.isPending} error={save.error}
        items={editing ? [
          { label: t("cards.period"), value: t(`period.${editing.period}`) },
          { label: t("cards.newMax"), value: (
            <Field label={t("cards.newMax")} error={amount && !AMOUNT.test(amount) ? t("form.amount") : undefined}>
              <Input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value.replace(",", ".").trim())} />
            </Field>
          ) },
        ] : []} />
      <StepUpConfirm open={adding} onClose={() => setAdding(false)} title={t("cards.addLimit")} confirmLabel={t("common.save")}
        onConfirm={() => { if (AMOUNT.test(newLimit.max_amount)) create.mutate(); }} pending={create.isPending} error={create.error}
        items={[
          { label: t("cards.period"), value: (
            <Select aria-label={t("cards.period")} value={newLimit.period}
              onChange={(e) => setNewLimit({ ...newLimit, period: e.target.value as CardSpendLimit["period"] })}
              options={(["PER_TRANSACTION", "DAILY", "WEEKLY", "MONTHLY"] as const).map((p) => ({ value: p, label: t(`period.${p}`) }))} />
          ) },
          { label: t("cards.channel"), value: (
            <Select aria-label={t("cards.channel")} value={newLimit.channel}
              onChange={(e) => setNewLimit({ ...newLimit, channel: e.target.value as CardSpendLimit["channel"] })}
              options={(["ANY", "ECOM", "POS", "ATM", "CONTACTLESS"] as const).map((c) => ({ value: c, label: c.toLowerCase() }))} />
          ) },
          { label: t("cards.newMax"), value: (
            <Input aria-label={t("cards.newMax")} inputMode="decimal" value={newLimit.max_amount}
              onChange={(e) => setNewLimit({ ...newLimit, max_amount: e.target.value.replace(",", ".").trim() })} />
          ) },
          { label: t("payees.currency"), value: (
            <Input aria-label={t("payees.currency")} maxLength={3} value={newLimit.currency}
              onChange={(e) => setNewLimit({ ...newLimit, currency: e.target.value.toUpperCase() })} />
          ) },
        ]} />
    </Card>
  );
}
