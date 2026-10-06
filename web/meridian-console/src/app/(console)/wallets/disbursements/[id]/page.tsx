"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { call, download } from "@/api/client";
import { Badge, Button, Card, ErrorBanner, Loading, Money, Mono, PageHeader, Table } from "@/components/ui";
import { Action, Details, Filters, Pager, Stat, Timeline, amountCell, str, usePaged, type Row } from "@/components/ops";

const VALIDATION = ["PENDING", "VALID", "INVALID", "NAME_MISMATCH", "DUPLICATE"] as const;
const PAYOUT = ["PENDING", "PAID", "FAILED", "SKIPPED", "RETURNED"] as const;
const CANCELLABLE = ["DRAFT", "VALIDATED", "PENDING_APPROVAL", "APPROVED"];

function BatchDetail() {
  const { id } = useParams<{ id: string }>();
  const [lineFilters, setLineFilters] = useState<Record<string, string>>({});
  const batch = useQuery({ queryKey: ["disbursement-batch", id], queryFn: () => call<Row>("GET", `/v1/disbursement-batches/${id}`) });
  const program = useQuery({
    queryKey: ["disbursement-program", batch.data?.disbursement_program_id], retry: false,
    enabled: !!batch.data?.disbursement_program_id,
    queryFn: () => call<Row>("GET", `/v1/disbursement-programs/${batch.data!.disbursement_program_id}`),
  });
  const lines = usePaged("disbursement-beneficiaries", `/v1/disbursement-batches/${id}/beneficiaries`, lineFilters, { limit: 50 });
  const approvals = usePaged("disbursement-approvals", `/v1/disbursement-batches/${id}/approvals`);
  const report = useMutation({ mutationFn: () => download(`/v1/disbursement-batches/${id}/report`, `disbursement-${id}.csv`) });

  if (batch.isLoading) return <Loading />;
  const b = batch.data;
  const s = String(b?.status ?? "");
  const cur = b?.currency as string | undefined;
  const inv = [["disbursement-batch", id], ["disbursement-batches"], ["disbursement-beneficiaries"], ["disbursement-approvals"]];

  return (
    <>
      <PageHeader title={`Batch ${str(b?.batch_reference)}`}
        subtitle={b && <>{program.data ? <>{str(program.data.code)} · {str(program.data.name)}</> : <Mono>{String(b.disbursement_program_id)}</Mono>}
          {" "}· {str(b.description)} · <Badge value={s} /></>}
        actions={b && <>
          {s === "DRAFT" && (
            <Action label="Upload file" variant="secondary" path={`/v1/disbursement-batches/${id}/upload`} invalidate={inv}
              description="CSV with header msisdn,amount and optional full_name,national_id,beneficiary_reference,narrative. Replaces any existing lines."
              fields={[{ name: "content", label: "CSV content", type: "textarea", required: true, placeholder: "msisdn,amount,full_name" },
                { name: "file_name", label: "File name" }]} />
          )}
          {s === "DRAFT" && (
            <Action label="Validate" path={`/v1/disbursement-batches/${id}/validate`} invalidate={inv}
              description="Checks every beneficiary: wallet exists and is active, name match, KYC tier, amount cap and duplicates." />
          )}
          {s === "VALIDATED" && (
            <Action label="Submit for approval" path={`/v1/disbursement-batches/${id}/submit`} invalidate={inv}
              description={<>Submits {str(b.valid_count)} valid lines totalling <Money amount={b.valid_amount as string} currency={cur} /> for approval.
                Invalid lines are skipped.</>} />
          )}
          {s === "PENDING_APPROVAL" && (
            <Action label="Approve" path={`/v1/disbursement-batches/${id}/approve`} invalidate={inv}
              description={<>Approves level {Number(b.approvals_received ?? 0) + 1} of {str(b.approvals_required)}. Each level needs a different
                approver and never the submitter; the final approval pays out from the program funding wallet.</>}
              fields={[{ name: "comment", label: "Comment" }]} />
          )}
          {s === "PENDING_APPROVAL" && (
            <Action label="Reject" variant="danger" path={`/v1/disbursement-batches/${id}/reject`} invalidate={inv}
              fields={[{ name: "reason", label: "Reason", required: true, type: "textarea" }]} />
          )}
          {(s === "PARTIALLY_COMPLETED" || s === "FAILED") && (
            <Action label="Retry failed lines" path={`/v1/disbursement-batches/${id}/retry-failed`} invalidate={inv}
              description={`Retries the ${str(b.failed_count)} failed payout lines.`} />
          )}
          {CANCELLABLE.includes(s) && (
            <Action label="Cancel batch" variant="danger" path={`/v1/disbursement-batches/${id}/cancel`} invalidate={inv}
              description="Cancels the batch before execution." fields={[{ name: "reason", label: "Reason", type: "textarea" }]} />
          )}
          <Button variant="secondary" disabled={report.isPending} onClick={() => report.mutate()}>Download report</Button>
        </>} />
      <ErrorBanner error={batch.error ?? report.error} />
      {b && (
        <>
          <div className="mb-6 grid grid-cols-2 gap-4 lg:grid-cols-4">
            <Stat label="Total" value={<Money amount={b.total_amount as string} currency={cur} />} hint={`${str(b.total_count)} lines`} />
            <Stat label="Valid" value={<Money amount={b.valid_amount as string} currency={cur} />}
              hint={`${str(b.valid_count)} valid · ${str(b.invalid_count)} invalid · ${str(b.duplicate_count)} duplicate`}
              tone={Number(b.invalid_count ?? 0) > 0 ? "warn" : undefined} />
            <Stat label="Paid" value={<Money amount={b.paid_amount as string} currency={cur} />}
              hint={`${str(b.paid_count)} paid · ${str(b.failed_count)} failed`} tone={Number(b.failed_count ?? 0) > 0 ? "bad" : undefined} />
            <Stat label="Approvals" value={`${str(b.approvals_received)} / ${str(b.approvals_required)}`} />
          </div>
          <div className="mb-6 grid grid-cols-1 gap-6 lg:grid-cols-3">
            <Card title="Batch" className="lg:col-span-2">
              <Details items={[
                ["Program funding wallet", program.data?.funding_wallet_id
                  ? <Link className="text-accent hover:underline" href={`/wallets/${program.data.funding_wallet_id}`}><Mono>{String(program.data.funding_wallet_id)}</Mono></Link> : null],
                ["File", b.file_name],
                ["File SHA-256", b.file_sha256 ? <Mono>{String(b.file_sha256).slice(0, 16)}…</Mono> : null],
                ["Scheduled for", b.scheduled_for],
                ["Fees", b.fee_amount ? <Money amount={String(b.fee_amount)} currency={cur} /> : null],
                ["Submitted", b.submitted_at ? <>{str(b.submitted_by)} · {new Date(String(b.submitted_at)).toLocaleString()}</> : null],
                ["Approved by", b.approved_by],
                ["Started", b.started_at],
                ["Completed", b.completed_at],
                ["Rejection reason", b.rejection_reason],
                ["Cancel reason", b.cancel_reason],
              ]} />
            </Card>
            <Card title="Approval trail">
              <ErrorBanner error={approvals.error} />
              {approvals.isLoading ? <Loading /> : (
                <Timeline items={approvals.rows.map((a) => ({
                  at: a.created_at, status: a.decision as string,
                  title: `Level ${str(a.level)} · ${str(a.decided_by ?? a.created_by)}`, detail: a.comment ? String(a.comment) : undefined,
                }))} />
              )}
              <Pager p={approvals} />
            </Card>
          </div>
        </>
      )}
      <Card title="Beneficiary lines">
        <Filters value={lineFilters} onChange={setLineFilters} specs={[
          { name: "validation_status", label: "Validation", options: VALIDATION },
          { name: "payout_status", label: "Payout", options: PAYOUT },
        ]} />
        <ErrorBanner error={lines.error} />
        {lines.isLoading ? <Loading /> : (
          <Table rows={lines.rows} empty={s === "DRAFT" ? "No lines yet. Upload the beneficiary file." : "No lines match."}
            columns={[
              { key: "line_no", header: "#", align: "right" },
              { key: "msisdn", header: "MSISDN" },
              { key: "full_name", header: "Name", render: (r) => <>{str(r.full_name)}{r.matched_name && r.matched_name !== r.full_name
                ? <span className="block text-xs text-muted">wallet: {String(r.matched_name)}</span> : null}</> },
              { key: "beneficiary_reference", header: "Reference" },
              { key: "amount", header: "Amount", align: "right", render: amountCell() },
              { key: "validation_status", header: "Validation", render: (r) => <>
                <Badge value={r.validation_status as string} tone={r.validation_status === "VALID" ? "good" : r.validation_status === "PENDING" ? "warn" : "bad"} />
                {r.validation_message ? <span className="block text-xs text-muted">{String(r.validation_message)}</span> : null}</> },
              { key: "payout_status", header: "Payout", render: (r) => <>
                <Badge value={r.payout_status as string} />
                {r.failure_reason ? <span className="block text-xs text-bad">{String(r.failure_reason)}</span> : null}</> },
            ]} />
        )}
        <Pager p={lines} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><BatchDetail /></Suspense>;
}
