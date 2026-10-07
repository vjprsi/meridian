"use client";

import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { accountLabel, useAccounts, usePayees, usePermissions, useUsers } from "@/api/business";
import { api, download, unwrap } from "@/api/client";
import type { BulkValidationError } from "@/api/types";
import { Approvals, KIND_LABEL, PaymentActions } from "@/components/payments";
import { useAction } from "@/components/step-up";
import {
  Async, Badge, Button, Card, DateTime, Empty, ErrorBanner, Loading, Money, Notice, PageHeader, Summary, Table, shortId,
} from "@/components/ui";

export default function PaymentPage() {
  const { id } = useParams<{ id: string }>();
  const perms = usePermissions();
  const accounts = useAccounts();
  const payees = usePayees();
  const users = useUsers(perms.loaded);
  const action = useAction();
  const [reportError, setReportError] = useState<unknown>(null);
  const [showDownstream, setShowDownstream] = useState(false);

  const payment = useQuery({
    queryKey: ["payment", id],
    queryFn: async () => unwrap(await api.GET("/v1/business/payments/{payment_id}", { params: { path: { payment_id: id } } })),
  });
  const approvals = useQuery({
    queryKey: ["payment-approvals", id],
    queryFn: async () => unwrap(await api.GET("/v1/business/payments/{payment_id}/approvals",
      { params: { path: { payment_id: id }, query: { limit: 100 } } })).data,
  });
  const downstream = useQuery({
    queryKey: ["payment-downstream", id], enabled: showDownstream,
    queryFn: async () => unwrap(await api.GET("/v1/business/payments/{payment_id}/downstream", { params: { path: { payment_id: id } } })),
  });

  if (payment.isPending) return <Loading rows={6} />;
  if (payment.error || !payment.data) return <ErrorBanner error={payment.error} />;
  const p = payment.data;
  const userName = (uid?: string | null) => users.data?.find((u) => u.id === uid)?.display_name ?? shortId(uid);
  const bulk = p.kind === "BULK" || p.kind === "PAYROLL";
  const errors = (p.validation_errors ?? []) as BulkValidationError[];

  async function report() {
    setReportError(null);
    try {
      await action(() => download(`/v1/business/bulk-payments/${p.id}/report`, `${p.name ?? "batch"}-report.csv`));
    } catch (e) {
      setReportError(e);
    }
  }

  return (
    <>
      <PageHeader back={{ href: "/payments", label: "Payments" }}
        title={<span className="flex flex-wrap items-center gap-3">{p.name ?? p.creditor_name ?? KIND_LABEL[p.kind]}
          <span data-testid="payment-status"><Badge value={p.status} /></span></span>}
        subtitle={`${KIND_LABEL[p.kind]} · ${p.id}`} />

      {p.initiated_by === perms.userId && p.status === "PENDING_APPROVAL" && (
        <div className="mb-4"><Notice>You initiated this payment, so another approver has to approve it.</Notice></div>
      )}

      <Card className="mb-5" actions={<PaymentActions p={p} perms={perms} />} title="Details">
        <Summary cols={3} items={[
          { label: "Amount", value: <Money amount={p.amount} currency={p.currency} /> },
          ...(p.instructed_amount ? [{ label: "Beneficiary receives", value: <Money amount={p.instructed_amount} currency={p.instructed_currency} /> }] : []),
          ...(p.fx_rate ? [{ label: "FX rate", value: p.fx_rate }] : []),
          { label: "From", value: accountLabel(accounts.data?.find((a) => a.id === p.debtor_account_id)) },
          { label: "To", value: p.creditor_account_id
            ? accountLabel(accounts.data?.find((a) => a.id === p.creditor_account_id))
            : p.creditor_name ?? payees.data?.find((x) => x.id === p.payee_id)?.name ?? (bulk ? `${p.item_count} lines` : "—") },
          { label: "Approvals", value: <><Approvals p={p} /> <span className="text-muted">({p.approval_order === "SEQUENTIAL" ? "sequential" : "any order"})</span></> },
          { label: "Initiated by", value: userName(p.initiated_by) },
          { label: "Submitted", value: <DateTime value={p.submitted_at} /> },
          { label: "Execution date", value: <DateTime value={p.requested_execution_date} dateOnly /> },
          { label: "Reference", value: p.reference ?? "—" },
          ...(p.remittance ? [{ label: "Remittance", value: p.remittance }] : []),
          ...(p.released_at ? [{ label: "Released", value: <><DateTime value={p.released_at} /> by {userName(p.released_by)}</> }] : []),
          ...(p.downstream_status ? [{ label: "Downstream status", value: <Badge value={p.downstream_status} /> }] : []),
          ...(p.rejection_reason ? [{ label: "Rejection reason", value: `${p.rejection_reason} (${userName(p.rejected_by)})` }] : []),
          ...(p.release_error ? [{ label: "Release error", value: <span className="text-bad">{p.release_error}</span> }] : []),
          ...(bulk ? [{ label: "Lines", value: `${p.item_count} (${p.invalid_count} invalid)` }] : []),
        ]} />
      </Card>

      {bulk && errors.length > 0 && (
        <Card className="mb-5" title="Validation errors" flush>
          <Table rows={errors} rowKey={(e) => `${e.line ?? e.line_no}-${e.field}-${e.code}`} caption="Validation errors" columns={[
            { header: "Line", cell: (e) => e.line ?? e.line_no ?? "—" },
            { header: "Field", cell: (e) => e.field ?? "—" },
            { header: "Code", cell: (e) => <span className="font-mono text-xs">{e.code}</span> },
            { header: "Message", cell: (e) => e.message },
          ]} />
        </Card>
      )}

      {bulk && p.status === "RELEASED" && (
        <Card className="mb-5" title="Execution report">
          <Button variant="secondary" onClick={report}>Download report (CSV)</Button>
          <ErrorBanner error={reportError} className="mt-3" />
        </Card>
      )}

      <Card className="mb-5" title="Approval decisions" flush>
        <Async q={approvals} empty={<div className="p-4"><Empty title="No decisions yet" /></div>}>
          {(rows) => (
            <Table rows={rows} rowKey={(a) => a.id} caption="Approval decisions" columns={[
              { header: "#", cell: (a) => a.sequence },
              { header: "User", cell: (a) => userName(a.business_user_id) },
              { header: "Decision", cell: (a) => <Badge value={a.decision} /> },
              { header: "Comment", cell: (a) => a.comment ?? "—" },
              { header: "When", cell: (a) => <DateTime value={a.created_at} /> },
            ]} />
          )}
        </Async>
      </Card>

      {(p.payment_id || p.bulk_payment_id) && (
        <Card title="Downstream payment" actions={!showDownstream && <Button size="sm" variant="secondary" onClick={() => setShowDownstream(true)}>Show</Button>}>
          {showDownstream ? (
            <Async q={downstream}>
              {(d) => {
                const o = d as Record<string, unknown>;
                return (
                  <Summary cols={3} items={["id", "status", "payment_type", "rail_code", "end_to_end_id", "value_date", "settled_at", "rejection_reason", "item_count"]
                    .filter((k) => o[k] !== undefined && o[k] !== null)
                    .map((k) => ({ label: k.replaceAll("_", " "), value: k === "status" ? <Badge value={String(o[k])} /> : String(o[k]) }))} />
                );
              }}
            </Async>
          ) : <p className="text-sm text-muted">The payment as the payment system has it (status, rail, settlement).</p>}
        </Card>
      )}
    </>
  );
}
