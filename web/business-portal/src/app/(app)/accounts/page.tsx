"use client";

import Link from "next/link";
import { useAccounts, usePermissions } from "@/api/business";
import { Async, Badge, Card, Empty, PageHeader, Table } from "@/components/ui";

export default function AccountsPage() {
  const accounts = useAccounts();
  const perms = usePermissions();
  return (
    <>
      <PageHeader title="Accounts" subtitle="Accounts of the business you may see" />
      <Card flush>
        <Async q={accounts} empty={<div className="p-4"><Empty title="No accounts visible" /></div>}>
          {(rows) => (
            <Table rows={rows} rowKey={(a) => a.id} caption="Accounts" columns={[
              { header: "Account", cell: (a) => <Link className="prose-link" href={`/accounts/${a.id}`}>{a.nickname ?? a.account_number}</Link> },
              { header: "Number", cell: (a) => <span className="font-mono text-xs">{a.iban ?? a.account_number}</span> },
              { header: "Type", cell: (a) => a.account_type },
              { header: "Currency", cell: (a) => a.currency },
              { header: "Status", cell: (a) => <Badge value={a.status} /> },
              { header: "Your rights", cell: (a) => {
                if (perms.isAdmin) return <span className="text-xs">Admin (all)</span>;
                const e = perms.entitlement(a.id);
                return <span className="text-xs">{[e?.can_view && "view", e?.can_initiate && "initiate", e?.can_approve && "approve"].filter(Boolean).join(", ") || "—"}
                  {e?.max_amount ? ` · max ${e.max_amount}` : ""}</span>;
              } },
            ]} />
          )}
        </Async>
      </Card>
    </>
  );
}
