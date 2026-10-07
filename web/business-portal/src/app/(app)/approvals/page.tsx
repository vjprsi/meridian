"use client";

import Link from "next/link";
import { accountLabel, useAccounts, useApprovalQueue, usePermissions, useUsers } from "@/api/business";
import { Approvals, KIND_LABEL, PaymentActions } from "@/components/payments";
import { Async, Badge, Card, DateTime, Empty, Money, PageHeader, Table, shortId } from "@/components/ui";

export default function ApprovalQueuePage() {
  const perms = usePermissions();
  const queue = useApprovalQueue();
  const accounts = useAccounts();
  const users = useUsers(perms.loaded);
  return (
    <>
      <PageHeader title="Approval queue"
        subtitle="Payments waiting for your decision: not initiated by you, not yet decided by you, and next in sequence" />
      <Card flush>
        <Async q={queue} empty={<div className="p-4"><Empty title="Nothing waiting for your approval" /></div>}>
          {(rows) => (
            <Table rows={rows} rowKey={(p) => p.id} caption="Approval queue" columns={[
              { header: "Submitted", cell: (p) => <DateTime value={p.submitted_at ?? p.created_at} /> },
              { header: "Payment", cell: (p) => (
                <Link className="prose-link" href={`/payments/${p.id}`}>{p.name ?? p.creditor_name ?? p.reference ?? shortId(p.id)}</Link>
              ) },
              { header: "Kind", cell: (p) => KIND_LABEL[p.kind] },
              { header: "From", cell: (p) => <span className="text-xs">{accountLabel(accounts.data?.find((a) => a.id === p.debtor_account_id))}</span> },
              { header: "Initiator", cell: (p) => users.data?.find((u) => u.id === p.initiated_by)?.display_name ?? shortId(p.initiated_by) },
              { header: "Amount", align: "right", cell: (p) => <Money amount={p.amount} currency={p.currency} /> },
              { header: "Approvals", cell: (p) => <Approvals p={p} /> },
              { header: "Status", cell: (p) => <Badge value={p.status} /> },
              { header: "", cell: (p) => <PaymentActions p={p} perms={perms} /> },
            ]} />
          )}
        </Async>
      </Card>
    </>
  );
}
