"use client";

import Link from "next/link";
import { accountLabel, type Permissions } from "@/api/business";
import { api, idem, unwrap } from "@/api/client";
import type { BusinessPayment, DepositAccount } from "@/api/types";
import { ActionButton } from "./actions";
import { Badge, DateTime, Money, Table, shortId } from "./ui";

export const KIND_LABEL: Record<BusinessPayment["kind"], string> = {
  OWN_TRANSFER: "Own transfer", DOMESTIC: "Domestic", INTERNATIONAL: "International", BULK: "Bulk", PAYROLL: "Payroll",
};

export function Approvals({ p }: { p: BusinessPayment }) {
  return (
    <span className="num whitespace-nowrap" data-testid="approvals-progress">
      {p.approvals_received} / {p.required_approvals}
    </span>
  );
}

export function PaymentTable({ rows, accounts }: { rows: BusinessPayment[]; accounts?: DepositAccount[] }) {
  return (
    <Table rows={rows} rowKey={(p) => p.id} caption="Payments" columns={[
      { header: "Created", cell: (p) => <DateTime value={p.created_at} /> },
      { header: "Payment", cell: (p) => (
        <Link className="prose-link" href={`/payments/${p.id}`}>{p.name ?? p.creditor_name ?? p.reference ?? shortId(p.id)}</Link>
      ) },
      { header: "Kind", cell: (p) => KIND_LABEL[p.kind] },
      { header: "From", cell: (p) => <span className="text-xs">{accountLabel(accounts?.find((a) => a.id === p.debtor_account_id))}</span> },
      { header: "Amount", align: "right", cell: (p) => <Money amount={p.amount} currency={p.currency} /> },
      { header: "Approvals", cell: (p) => <Approvals p={p} /> },
      { header: "Status", cell: (p) => <Badge value={p.status} /> },
    ]} />
  );
}

/**
 * The actions the signed-in user may take on a payment in its current state. Hidden when the role or entitlement
 * does not allow them (and never approve/reject on one's own payment); the server still decides and its refusal
 * (not_entitled, self_approval_forbidden, out_of_sequence, ...) is shown in the dialog.
 */
export function PaymentActions({ p, perms }: { p: BusinessPayment; perms: Permissions }) {
  const mine = p.initiated_by === perms.userId;
  const path = { payment_id: p.id };
  const canDecide = p.status === "PENDING_APPROVAL" && !mine && perms.approveOn(p.debtor_account_id);
  return (
    <div className="flex flex-wrap gap-2">
      {p.status === "DRAFT" && mine && (
        <ActionButton label="Submit for approval" variant="primary" size="md"
          description="The approval rule for this amount is fixed when you submit; your daily limit and entitlement are checked again."
          run={async (_v, key) => unwrap(await api.POST("/v1/business/payments/{payment_id}/submit", { params: { path, header: idem(key) } }))} />
      )}
      {canDecide && (
        <ActionButton label="Approve" variant="primary" size="md" confirmLabel="Approve"
          description={<>Approve <Money amount={p.amount} currency={p.currency} />? The final approval releases the payment.</>}
          fields={[{ name: "comment", label: "Comment", type: "textarea" }]}
          run={async (v, key) => unwrap(await api.POST("/v1/business/payments/{payment_id}/approve", {
            params: { path, header: idem(key) }, body: { comment: v.comment || undefined } }))} />
      )}
      {canDecide && (
        <ActionButton label="Reject" variant="danger" size="md" confirmLabel="Reject"
          fields={[{ name: "reason", label: "Reason", type: "textarea", required: true }]}
          run={async (v, key) => unwrap(await api.POST("/v1/business/payments/{payment_id}/reject", {
            params: { path, header: idem(key) }, body: { reason: v.reason } }))} />
      )}
      {(p.status === "DRAFT" || p.status === "PENDING_APPROVAL") && (mine || perms.isAdmin) && (
        <ActionButton label="Cancel payment" variant="secondary" size="md" confirmLabel="Cancel payment" confirmVariant="danger"
          fields={[{ name: "reason", label: "Reason", type: "textarea" }]}
          run={async (v, key) => unwrap(await api.POST("/v1/business/payments/{payment_id}/cancel", {
            params: { path, header: idem(key) }, body: { reason: v.reason || undefined } }))} />
      )}
      {p.status === "RELEASE_FAILED" && (perms.isAdmin || perms.approveOn(p.debtor_account_id)) && (
        <ActionButton label="Retry release" variant="primary" size="md"
          description="Retries the release to the payment system. It is idempotent: it never creates a second payment."
          run={async (_v, key) => unwrap(await api.POST("/v1/business/payments/{payment_id}/release", { params: { path, header: idem(key) } }))} />
      )}
    </div>
  );
}
