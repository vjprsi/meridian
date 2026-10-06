"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { call, download } from "@/api/client";
import { Badge, Button, Card, DateTime, ErrorBanner, Loading, Money, Mono, PageHeader, Table } from "@/components/ui";
import { Action, Details, Json, Stat, Timeline, rows, usePaged, Pager, type Row } from "@/components/ops";
import type { Page } from "@/lib/types";
import { CANCELLABLE, RECALL_REASONS, RETURN_REASONS, type Payment, type PaymentApproval } from "../shared";

/** Receipt (pacs advice PDF) is issued once the payment has left the bank. */
const RECEIPT_STATUSES = ["SUBMITTED", "ACCEPTED", "SETTLED", "RETURNED"];

type Tracker = Row & { stage?: string; gpi_status?: string; final?: boolean; processing_seconds?: number;
  returns?: Row[]; recalls?: Row[] };

function PaymentDetail() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const [showRaw, setShowRaw] = useState(false);

  const payment = useQuery({ queryKey: ["payment", id], queryFn: () => call<Payment>("GET", `/v1/payments/${id}`) });
  const tracker = useQuery({ queryKey: ["payment", id, "tracker"], retry: false,
    queryFn: () => call<Tracker>("GET", `/v1/payments/${id}/tracker`) });
  const history = usePaged("payment", `/v1/payments/${id}/status-history`, {}, { limit: 50 });
  const approvals = useQuery({ queryKey: ["payment", id, "approvals"], retry: false,
    queryFn: () => call<Page<PaymentApproval>>("GET", `/v1/payments/${id}/approvals?limit=50`) });
  const receipt = useMutation({ mutationFn: () => download(`/v1/payments/${id}/receipt`, `payment-${id}-receipt.pdf`) });

  if (payment.isLoading) return <Loading />;
  const p = payment.data;
  if (!p) return <ErrorBanner error={payment.error ?? "Payment not found"} />;

  const status = p.status;
  // ["payment"] covers this payment's detail, tracker, approvals and status-history pages.
  const inv = [["payment"], ["payments"]];
  const outbound = p.direction === "OUTBOUND" || p.direction === "INTERNAL";
  const t = tracker.data;

  return (
    <>
      <PageHeader title={`${p.payment_type.replaceAll("_", " ")} payment`}
        subtitle={<><Mono>{p.end_to_end_id}</Mono> · {p.direction} · {p.channel} · <Badge value={status} /></>}
        actions={<>
          <Button variant="secondary" onClick={() => router.push("/payments")}>Back to payments</Button>
          {RECEIPT_STATUSES.includes(status) && (
            <Button variant="secondary" disabled={receipt.isPending} onClick={() => receipt.mutate()}>
              {receipt.isPending ? "Downloading…" : "Download receipt"}</Button>
          )}
        </>} />
      <ErrorBanner error={payment.error ?? receipt.error} />

      {(p.rejection_code || p.rejection_reason) && (
        <div className="mb-4 rounded-[var(--radius)] border border-bad/30 bg-bad-soft px-4 py-3 text-sm text-bad">
          Rejected{p.rejection_code ? <> · <span className="font-mono">{p.rejection_code}</span></> : null}
          {p.rejection_reason ? <> — {p.rejection_reason}</> : null}
        </div>
      )}

      <div className="mb-6 grid grid-cols-2 gap-4 lg:grid-cols-4">
        <Stat label="Amount" value={<Money amount={p.amount} currency={p.currency} />}
          hint={p.instructed_amount ? <>instructed <Money amount={p.instructed_amount} currency={p.instructed_currency} /></> : undefined} />
        <Stat label="Fee" value={<Money amount={p.fee_amount ?? "0"} currency={p.fee_currency ?? p.currency} />}
          hint={`charge bearer ${p.charge_bearer ?? "—"}`} />
        <Stat label="Stage" value={<span className="text-base">{(t?.stage ?? status).replaceAll("_", " ")}</span>}
          hint={t?.gpi_status ? `gpi ${t.gpi_status}` : undefined} />
        <Stat label="Rail" value={<span className="text-base">{p.rail_code ?? "—"}</span>}
          hint={t?.processing_seconds !== undefined ? `settled in ${t.processing_seconds}s` : p.value_date ? `value ${p.value_date}` : undefined} />
      </div>

      <Card title="Actions" className="mb-6">
        <div className="flex flex-wrap gap-2">
          {status === "PENDING_APPROVAL" && <>
            <Action label="Approve" path={`/v1/payments/${id}/approve`} invalidate={inv}
              description="Records your approval for the current approval level. You cannot approve a payment you initiated."
              fields={[{ name: "comment", label: "Comment" }]} />
            <Action label="Reject" variant="danger" path={`/v1/payments/${id}/reject`} invalidate={inv}
              description="Rejects the payment; it will not be executed."
              fields={[{ name: "reason", label: "Reason", required: true, type: "textarea" }]} />
          </>}
          {status === "SCREENING" && (
            <Action label="Screening decision" variant="secondary" path={`/v1/payments/${id}/screening-decision`} invalidate={inv}
              description="Apply the compliance decision on this held payment. CLEAR / FALSE_POSITIVE releases it; CONFIRMED_MATCH rejects it."
              fields={[
                { name: "status", label: "Decision", required: true, options: ["CLEAR", "FALSE_POSITIVE", "CONFIRMED_MATCH"] },
                { name: "screening_result_id", label: "Screening result ID", placeholder: "UUID" },
                { name: "reason", label: "Reason", type: "textarea" },
              ]} />
          )}
          {status === "APPROVED" && (
            <Action label="Execute now" path={`/v1/payments/${id}/execute`} invalidate={inv} body={{}}
              description="Reserves funds and submits the payment to its rail now, instead of waiting for the requested execution date." />
          )}
          {CANCELLABLE.includes(status) && (
            <Action label="Cancel payment" variant="danger" path={`/v1/payments/${id}/cancel`} invalidate={inv}
              description="Cancels the payment before it is submitted. Any funds reservation is released."
              fields={[{ name: "reason", label: "Reason", type: "textarea" }]} />
          )}
          {status === "SETTLED" && p.direction === "INBOUND" && <>
            <Action label="Return payment" variant="danger" path={`/v1/payments/${id}/return`} invalidate={inv}
              description={<>Returns this settled inbound payment to the sender (pacs.004). Leave the amount empty to return
                the full <Money amount={p.amount} currency={p.currency} />.</>}
              fields={[
                { name: "reason_code", label: "Reason code (ISO 20022)", required: true, options: RETURN_REASONS },
                { name: "reason", label: "Reason", type: "textarea" },
                { name: "amount", label: `Amount (${p.currency})`, type: "number", placeholder: p.amount },
              ]} />
            <Action label="Refund" variant="secondary" path={`/v1/payments/${id}/refund`} invalidate={inv}
              description="Sends a new outbound payment refunding the payer. Leave the amount empty for a full refund."
              fields={[
                { name: "amount", label: `Amount (${p.currency})`, type: "number", placeholder: p.amount },
                { name: "reason", label: "Reason", type: "textarea" },
              ]} />
          </>}
          {status === "SETTLED" && outbound && (
            <Action label="Recall payment" variant="danger" path={`/v1/payments/${id}/recall`} invalidate={inv}
              description="Requests the beneficiary bank to return this settled payment (camt.056). The outcome arrives asynchronously."
              fields={[
                { name: "reason_code", label: "Reason code", required: true, options: RECALL_REASONS,
                  hint: "DUPL duplicate · FRAD fraud · TECH technical · CUST customer request · AM09 wrong amount · AC03 wrong account" },
                { name: "reason", label: "Reason", type: "textarea" },
              ]} />
          )}
          {!["PENDING_APPROVAL", "SCREENING", "APPROVED", "SETTLED"].includes(status) && !CANCELLABLE.includes(status) && (
            <p className="text-sm text-muted">No operator actions are available in status {status.replaceAll("_", " ")}.</p>
          )}
        </div>
      </Card>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <div className="space-y-6 lg:col-span-2">
          <Card title="Parties">
            <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
              <div>
                <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted">Debtor</p>
                <Details cols={1} items={[
                  ["Name", p.debtor_name], ["Account", p.debtor_account_number], ["ID type", p.debtor_account_id_type],
                  ["Agent BIC", p.debtor_agent_bic], ["Country", p.debtor_country],
                  ["Customer", p.customer_id ? <Link className="text-accent hover:underline" href={`/customers/${p.customer_id}`}>
                    <Mono>{p.customer_id}</Mono></Link> : null],
                  ["Internal account", p.debtor_account_id ? <Mono>{p.debtor_account_id}</Mono> : null],
                  ["Wallet", p.debtor_wallet_id ? <Mono>{p.debtor_wallet_id}</Mono> : null],
                ]} />
              </div>
              <div>
                <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted">Creditor</p>
                <Details cols={1} items={[
                  ["Name", p.creditor_name], ["Account", p.creditor_account_number], ["ID type", p.creditor_account_id_type],
                  ["Agent BIC", p.creditor_agent_bic], ["Country", p.creditor_country],
                  ["Internal account", p.creditor_account_id ? <Mono>{p.creditor_account_id}</Mono> : null],
                  ["Wallet", p.creditor_wallet_id ? <Mono>{p.creditor_wallet_id}</Mono> : null],
                  ["Merchant", p.creditor_merchant_id ? <Mono>{p.creditor_merchant_id}</Mono> : null],
                ]} />
              </div>
            </div>
          </Card>
          <Card title="Payment details">
            <Details items={[
              ["Payment ID", <Mono key="id">{p.id}</Mono>], ["UETR", p.uetr ? <Mono key="u">{p.uetr}</Mono> : null],
              ["Instruction ID", p.instruction_id], ["Transaction ID", p.transaction_id],
              ["Clearing ref", p.clearing_system_ref], ["Priority", p.priority],
              ["Reference", p.reference], ["Remittance", p.remittance_unstructured],
              ["Purpose", p.purpose_code], ["Category purpose", p.category_purpose],
              ["Requested execution", p.requested_execution_date], ["Value date", p.value_date],
              ["FX rate", p.fx_rate], ["Settlement", p.settlement_amount ? `${p.settlement_amount} ${p.settlement_currency ?? ""}` : null],
              ["Fraud score", p.fraud_score], ["Initiated by", p.initiated_by],
              ["Journal entry", p.journal_entry_id ? <Mono key="j">{p.journal_entry_id}</Mono> : null],
              ["Original payment", p.original_payment_id ? <Link key="o" className="text-accent hover:underline"
                href={`/payments/${p.original_payment_id}`}><Mono>{p.original_payment_id}</Mono></Link> : null],
              ["Created", p.created_at], ["Submitted", p.submitted_at], ["Accepted", p.accepted_at], ["Settled", p.settled_at],
            ]} />
          </Card>
          {t && (rows(t.returns).length > 0 || rows(t.recalls).length > 0) && (
            <Card title="Returns & recalls">
              {rows(t.returns).length > 0 && (
                <Table rows={rows(t.returns)} columns={[
                  { key: "created_at", header: "Returns", render: (r) => <DateTime value={r.created_at as string} /> },
                  { key: "direction", header: "Direction" },
                  { key: "reason_code", header: "Reason", render: (r) => <><Mono>{String(r.reason_code ?? "")}</Mono> {String(r.reason ?? "")}</> },
                  { key: "amount", header: "Amount", align: "right", render: (r) => <Money amount={r.amount as string} currency={r.currency as string} /> },
                  { key: "status", header: "Status", render: (r) => <Badge value={r.status as string} /> },
                ]} />
              )}
              {rows(t.recalls).length > 0 && (
                <Table rows={rows(t.recalls)} columns={[
                  { key: "created_at", header: "Recalls", render: (r) => <DateTime value={r.created_at as string} /> },
                  { key: "direction", header: "Direction" },
                  { key: "reason_code", header: "Reason", render: (r) => <><Mono>{String(r.reason_code ?? "")}</Mono> {String(r.reason ?? "")}</> },
                  { key: "response_code", header: "Response", render: (r) => String(r.response_reason ?? r.response_code ?? "—") },
                  { key: "status", header: "Status", render: (r) => <Badge value={r.status as string} /> },
                ]} />
              )}
            </Card>
          )}
          <Card title="Approvals">
            <ErrorBanner error={approvals.error} />
            {approvals.isLoading ? <Loading /> : (
              <Table rows={rows(approvals.data)} empty="This payment needed no approval."
                columns={[
                  { key: "approver_level", header: "Level", align: "right" },
                  { key: "received", header: "Received", render: (r) => `${String(r.approvals_received ?? 0)} / ${String(r.required_approvals ?? 1)}` },
                  { key: "approved_by", header: "Approved by", render: (r) => Array.isArray(r.approved_by) ? r.approved_by.join(", ") || "—" : "—" },
                  { key: "comment", header: "Comment", render: (r) => String(r.comment ?? "—") },
                  { key: "decided_at", header: "Decided", render: (r) => <DateTime value={r.decided_at as string} /> },
                  { key: "status", header: "Status", render: (r) => <Badge value={r.status as string} /> },
                ]} />
            )}
          </Card>
        </div>
        <div className="space-y-6">
          <Card title="Status timeline">
            <ErrorBanner error={history.error} />
            {history.isLoading ? <Loading /> : (
              <Timeline items={history.rows.map((h) => ({
                at: h.created_at,
                status: h.to_status as string,
                title: h.from_status ? `from ${String(h.from_status).replaceAll("_", " ")}` : "created",
                detail: [h.reason_code, h.reason, h.actor ? `by ${String(h.actor)}` : null].filter(Boolean).join(" · ") || undefined,
              }))} />
            )}
            <Pager p={history} />
          </Card>
          <Card title="Tracker" actions={<Button variant="ghost" onClick={() => setShowRaw(!showRaw)}>{showRaw ? "Hide" : "Raw"}</Button>}>
            <ErrorBanner error={tracker.error} />
            {t && (
              <Details cols={1} items={[
                ["Stage", t.stage], ["gpi status", t.gpi_status], ["Final", t.final],
                ["Processing time", t.processing_seconds !== undefined ? `${t.processing_seconds}s` : null],
              ]} />
            )}
            {showRaw && <div className="mt-3"><Json value={t} /></div>}
          </Card>
          {p.metadata && Object.keys(p.metadata).length > 0 && <Card title="Metadata"><Json value={p.metadata} /></Card>}
        </div>
      </div>
    </>
  );
}

export default function Page() {
  return <Suspense><PaymentDetail /></Suspense>;
}
