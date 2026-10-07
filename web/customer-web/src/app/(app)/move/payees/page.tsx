"use client";

import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { idem, me, unwrap } from "@/api/client";
import { qk, useCustomerId, useMyPayees } from "@/api/me";
import type { MyPayee, Payee } from "@/api/types";
import { StepUpConfirm } from "@/components/step-up";
import {
  Async, Badge, Button, Card, Empty, ErrorBanner, Field, Input, LinkButton, Notice, PageHeader, Select, masked,
} from "@/components/ui";
import { useT } from "@/lib/i18n";

const EMPTY = { name: "", nickname: "", account_id_type: "ACCOUNT_NUMBER" as MyPayee["account_id_type"],
  account_number: "", bank_name: "", agent_clearing_id: "", country: "KE", currency: "" };

export default function Payees() {
  const t = useT();
  const c = useCustomerId();
  const qc = useQueryClient();
  const list = useMyPayees();
  const [form, setForm] = useState(EMPTY);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [confirmAdd, setConfirmAdd] = useState(false);
  const [removing, setRemoving] = useState<Payee | null>(null);
  const [trusting, setTrusting] = useState<Payee | null>(null);
  const refresh = () => qc.invalidateQueries({ queryKey: qk.payees(c) });

  const add = useMutation({
    mutationFn: async () => unwrap(await me.POST("/v1/me/payees", {
      params: { header: idem() },
      body: { beneficiary_type: "INDIVIDUAL", name: form.name.trim(),
        nickname: form.nickname || undefined, account_id_type: form.account_id_type,
        account_number: form.account_number.replace(/\s+/g, "").toUpperCase(), bank_name: form.bank_name || undefined,
        agent_clearing_id: form.agent_clearing_id || undefined, country: form.country.toUpperCase(),
        currency: form.currency ? form.currency.toUpperCase() : undefined },
    })),
    onSuccess: () => { setConfirmAdd(false); setForm(EMPTY); refresh(); },
  });
  const verify = useMutation({
    mutationFn: async (id: string) => unwrap(await me.POST("/v1/me/payees/{payee_id}/verify", { params: { path: { payee_id: id }, header: idem() } })),
    onSuccess: refresh,
  });
  const trust = useMutation({
    mutationFn: async (id: string) => unwrap(await me.POST("/v1/me/payees/{payee_id}/trust", { params: { path: { payee_id: id }, header: idem() } })),
    onSuccess: () => { setTrusting(null); refresh(); },
  });
  const remove = useMutation({
    mutationFn: async (id: string) => unwrap(await me.DELETE("/v1/me/payees/{payee_id}", { params: { path: { payee_id: id }, header: idem() } })),
    onSuccess: () => { setRemoving(null); refresh(); },
  });

  function submit(e: React.FormEvent) {
    e.preventDefault();
    const er: Record<string, string> = {};
    if (!form.name.trim()) er.name = t("form.required");
    if (!/^[A-Za-z0-9 ]{4,34}$/.test(form.account_number)) er.account_number = t("payees.accountInvalid");
    if (!/^[A-Za-z]{2}$/.test(form.country)) er.country = t("payees.countryInvalid");
    setErrors(er);
    if (Object.keys(er).length === 0) setConfirmAdd(true);
  }

  return (
    <>
      <PageHeader title={t("move.payees")} back={{ href: "/move", label: t("nav.move") }} />
      <div className="grid gap-5 lg:grid-cols-[1fr_24rem]">
        <Card title={t("payees.saved")}>
          <ErrorBanner error={verify.error ?? remove.error} className="mb-3" />
          <Async q={list} empty={<Empty title={t("payees.none")}>{t("payees.noneHint")}</Empty>}>
            {(rows) => (
              <ul className="divide-y divide-border">
                {rows.map((p) => (
                  <li key={p.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
                    <span className="min-w-0">
                      <span className="block font-medium">{p.nickname || p.name}</span>
                      <span className="block text-xs text-muted">
                        {p.name} · {masked(p.account_number)}{p.bank_name ? ` · ${p.bank_name}` : ""}
                      </span>
                      <span className="mt-1 flex flex-wrap gap-1">
                        <Badge value={p.status} />
                        {p.verification_result && <Badge value={p.verification_result} />}
                        {p.trusted && <Badge value="TRUSTED" tone="good" />}
                      </span>
                    </span>
                    <span className="flex flex-wrap gap-2">
                      {p.status === "ACTIVE" && <LinkButton href={`/move/pay?payee=${p.id}`}>{t("payees.pay")}</LinkButton>}
                      {!p.verification_result && (
                        <Button variant="secondary" disabled={verify.isPending} onClick={() => verify.mutate(p.id!)}>{t("payees.verify")}</Button>
                      )}
                      {!p.trusted && p.status === "ACTIVE" && (
                        <Button variant="secondary" onClick={() => setTrusting(p)}>{t("payees.trust")}</Button>
                      )}
                      <Button variant="ghost" onClick={() => setRemoving(p)} aria-label={`${t("payees.remove")} ${p.nickname || p.name}`}>
                        {t("payees.remove")}
                      </Button>
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </Async>
        </Card>

        <Card title={t("payees.add")}>
          <form onSubmit={submit} noValidate className="flex flex-col gap-4">
            <Field label={t("payees.name")} hint={t("payees.nameHint")} error={errors.name}>
              <Input value={form.name} autoComplete="off" onChange={(e) => setForm({ ...form, name: e.target.value })} />
            </Field>
            <Field label={t("payees.nickname")} optional>
              <Input value={form.nickname} onChange={(e) => setForm({ ...form, nickname: e.target.value })} />
            </Field>
            <Field label={t("payees.idType")}>
              <Select value={form.account_id_type} onChange={(e) => setForm({ ...form, account_id_type: e.target.value as MyPayee["account_id_type"] })}
                options={[{ value: "ACCOUNT_NUMBER", label: t("payees.accountNumber") }, { value: "IBAN", label: "IBAN" }]} />
            </Field>
            <Field label={form.account_id_type === "IBAN" ? "IBAN" : t("payees.accountNumber")} error={errors.account_number}>
              <Input value={form.account_number} autoComplete="off" spellCheck={false}
                onChange={(e) => setForm({ ...form, account_number: e.target.value })} />
            </Field>
            <Field label={t("payees.bank")} optional>
              <Input value={form.bank_name} onChange={(e) => setForm({ ...form, bank_name: e.target.value })} />
            </Field>
            <Field label={t("payees.bankCode")} hint={t("payees.bankCodeHint")} optional>
              <Input value={form.agent_clearing_id} onChange={(e) => setForm({ ...form, agent_clearing_id: e.target.value })} />
            </Field>
            <div className="grid grid-cols-2 gap-3">
              <Field label={t("payees.country")} error={errors.country}>
                <Input value={form.country} maxLength={2} onChange={(e) => setForm({ ...form, country: e.target.value.toUpperCase() })} />
              </Field>
              <Field label={t("payees.currency")} optional>
                <Input value={form.currency} maxLength={3} onChange={(e) => setForm({ ...form, currency: e.target.value.toUpperCase() })} />
              </Field>
            </div>
            <Button type="submit">{t("payees.addSubmit")}</Button>
            {add.isSuccess && <Notice tone="good">{t("payees.added")}</Notice>}
          </form>
        </Card>
      </div>

      <StepUpConfirm open={confirmAdd} onClose={() => setConfirmAdd(false)} title={t("payees.confirmAdd")}
        confirmLabel={t("payees.addSubmit")} onConfirm={() => add.mutate()} pending={add.isPending} error={add.error}
        warning={t("payees.fraudWarning")}
        items={[
          { label: t("payees.name"), value: form.name },
          { label: form.account_id_type === "IBAN" ? "IBAN" : t("payees.accountNumber"), value: form.account_number },
          ...(form.bank_name ? [{ label: t("payees.bank"), value: form.bank_name }] : []),
          { label: t("payees.country"), value: form.country },
        ]} />
      <StepUpConfirm open={!!trusting} onClose={() => setTrusting(null)} title={t("payees.trust")}
        confirmLabel={t("payees.trust")} onConfirm={() => trusting && trust.mutate(trusting.id!)} pending={trust.isPending}
        error={trust.error} warning={t("payees.trustWarning")}
        items={trusting ? [{ label: t("payees.name"), value: trusting.name }, { label: t("payees.accountNumber"), value: masked(trusting.account_number) }] : []} />
      <StepUpConfirm open={!!removing} onClose={() => setRemoving(null)} title={t("payees.removeTitle")}
        confirmLabel={t("payees.remove")} onConfirm={() => removing && remove.mutate(removing.id!)} pending={remove.isPending}
        error={remove.error}
        items={removing ? [{ label: t("payees.name"), value: removing.name }, { label: t("payees.accountNumber"), value: masked(removing.account_number) }] : []} />
    </>
  );
}
