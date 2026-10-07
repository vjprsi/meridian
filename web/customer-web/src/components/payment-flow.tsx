"use client";

import { useRouter } from "next/navigation";
import { useState, type ReactNode } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { idem, payments, unwrap } from "@/api/client";
import { qk, useCustomerId } from "@/api/me";
import type { PaymentCreate, PaymentValidation } from "@/api/types";
import { useT } from "@/lib/i18n";
import { StepUpConfirm } from "./step-up";
import { Button, ErrorBanner, Money } from "./ui";

export const AMOUNT = /^(?!0+(\.0+)?$)\d{1,13}(\.\d{1,2})?$/;

/**
 * Money movement: Review (POST /v1/payments/validate: account checks, route, cut-off, fees, Confirmation of Payee),
 * then step-up confirmation, then POST /v1/payments with one Idempotency-Key kept for this confirmation, so a retry
 * after a timeout cannot create a second payment. On success the customer lands on the receipt.
 */
export function PaymentFlow({ build, summary, disabled, submitLabel }:
  { build: () => PaymentCreate | null; summary: (body: PaymentCreate) => { label: ReactNode; value: ReactNode }[];
    disabled?: boolean; submitLabel: string }) {
  const t = useT();
  const router = useRouter();
  const qc = useQueryClient();
  const c = useCustomerId();
  const [body, setBody] = useState<PaymentCreate | null>(null);
  const [validation, setValidation] = useState<PaymentValidation | null>(null);
  const [key, setKey] = useState<string>("");

  const validate = useMutation({
    mutationFn: async (b: PaymentCreate) => unwrap(await payments.POST("/v1/payments/validate", {
      params: { header: idem() }, body: { payment: b as unknown as Record<string, never> },
    })) as PaymentValidation,
    onSuccess: (v, b) => {
      setValidation(v);
      if (v.valid !== false) {
        setBody(b);
        setKey(crypto.randomUUID());
      }
    },
  });

  const create = useMutation({
    mutationFn: async () => unwrap(await payments.POST("/v1/payments", { params: { header: idem(key) }, body: body! })),
    onSuccess: (p) => {
      qc.invalidateQueries({ queryKey: qk.payments(c) });
      qc.invalidateQueries({ queryKey: qk.accounts(c) });
      qc.invalidateQueries({ queryKey: ["balance"] });
      router.push(`/move/receipt/${p.id}?new=1`);
    },
  });

  function review() {
    setValidation(null);
    const b = build();
    if (b) validate.mutate(b);
  }

  const fee = validation?.fee;
  return (
    <>
      <div className="flex flex-col gap-3">
        <ErrorBanner error={validate.error} />
        {validation?.valid === false && (
          <div role="alert" className="rounded-[var(--radius)] bg-bad-soft px-4 py-3 text-sm text-bad">
            <p className="font-semibold">{t("pay.invalid")}</p>
            <ul className="mt-1 list-disc pl-5">{(validation.errors ?? []).map((e) => <li key={e.code}>{e.message}</li>)}</ul>
          </div>
        )}
        <Button type="submit" onClick={(e) => { e.preventDefault(); review(); }} disabled={disabled || validate.isPending}>
          {validate.isPending ? t("common.working") : t("pay.review")}
        </Button>
      </div>
      <StepUpConfirm open={!!body} onClose={() => { if (!create.isPending) setBody(null); }}
        title={t("pay.confirmTitle")} confirmLabel={submitLabel}
        items={body ? [
          ...summary(body),
          ...(fee?.amount ? [{ label: t("pay.fee"), value: <Money amount={fee.amount} currency={fee.currency} /> }] : []),
          ...(validation?.total_debit ? [{ label: t("pay.totalDebit"), value: <Money amount={validation.total_debit} currency={validation.currency ?? body.currency} /> }] : []),
          ...(validation?.execution_date ? [{ label: t("pay.executes"), value: validation.execution_date }] : []),
          ...(validation?.payee_verification?.result ? [{ label: t("pay.cop"),
            value: `${validation.payee_verification.result.replaceAll("_", " ").toLowerCase()}${validation.payee_verification.matched_name ? ` · ${validation.payee_verification.matched_name}` : ""}` }] : []),
        ] : []}
        warning={(validation?.warnings ?? []).map((w) => w.message).join(" ") || undefined}
        onConfirm={() => create.mutate()} pending={create.isPending} error={create.error} />
    </>
  );
}
