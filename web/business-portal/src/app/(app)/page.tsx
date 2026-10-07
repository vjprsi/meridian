"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { useApprovalQueue, useMe, usePermissions } from "@/api/business";
import { api, unwrap } from "@/api/client";
import type { CashPosition } from "@/api/types";
import { Async, Badge, Card, Empty, LinkButton, Money, PageHeader, Table, masked } from "@/components/ui";

export default function Dashboard() {
  const me = useMe();
  const perms = usePermissions();
  const cash = useQuery({
    queryKey: ["cash-position"],
    queryFn: async () => unwrap(await api.GET("/v1/business/cash-position")) as unknown as CashPosition,
  });
  const queue = useApprovalQueue(perms.loaded && perms.canApprove);
  const name = me.data?.business?.display_name ?? me.data?.business?.legal_name;

  return (
    <>
      <PageHeader title="Cash position" subtitle={name ? `${name} · signed in as ${me.data?.user.display_name}` : undefined}
        actions={<>
          {perms.canInitiate && <LinkButton href="/payments/new">New payment</LinkButton>}
          {perms.canInitiate && <LinkButton href="/bulk" variant="secondary">Upload bulk / payroll</LinkButton>}
        </>} />

      <Async q={cash} rows={4}>
        {(c) => (
          <div className="space-y-5">
            <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
              {(c.totals ?? []).map((t) => (
                <div key={t.currency} className="rounded-[var(--radius)] border border-border bg-surface p-4">
                  <p className="text-xs uppercase tracking-wide text-muted">Available · {t.currency}</p>
                  <p className="mt-1 text-2xl font-semibold"><Money amount={t.available_balance} currency={t.currency} /></p>
                  <p className="mt-1 text-xs text-muted">Posted <Money amount={t.posted_balance} currency={t.currency} />
                    {t.accounts !== undefined && <> · {t.accounts} account{t.accounts === 1 ? "" : "s"}</>}</p>
                </div>
              ))}
              {perms.canApprove && (
                <Link href="/approvals" className="rounded-[var(--radius)] border border-border bg-surface p-4 hover:bg-surface-2">
                  <p className="text-xs uppercase tracking-wide text-muted">Waiting for your approval</p>
                  <p className="mt-1 text-2xl font-semibold" data-testid="queue-count">{queue.data?.length ?? "—"}</p>
                  <p className="mt-1 text-xs text-muted">Open the approval queue</p>
                </Link>
              )}
            </div>

            {(c.pending_approval ?? []).length > 0 && (
              <Card title="Pending approval (all users)">
                <ul className="flex flex-wrap gap-6 text-sm">
                  {(c.pending_approval ?? []).map((p) => (
                    <li key={p.currency}><Money amount={p.amount} currency={p.currency} />
                      <span className="text-muted"> in {p.count ?? 0} payment{p.count === 1 ? "" : "s"}</span></li>
                  ))}
                </ul>
              </Card>
            )}

            <Card title="Accounts" flush>
              <Table rows={c.accounts ?? []} rowKey={(a) => a.account_id} caption="Accounts"
                empty={<div className="p-4"><Empty title="No accounts visible">Ask an administrator for account entitlements.</Empty></div>}
                columns={[
                  { header: "Account", cell: (a) => <Link className="prose-link" href={`/accounts/${a.account_id}`}>{a.nickname ?? masked(a.account_number)}</Link> },
                  { header: "Number", cell: (a) => <span className="font-mono text-xs">{a.account_number}</span> },
                  { header: "Currency", cell: (a) => <Badge value={a.currency} tone="muted" /> },
                  { header: "Available", align: "right", cell: (a) => <Money amount={a.available_balance} currency={a.currency} /> },
                  { header: "Posted", align: "right", cell: (a) => <Money amount={a.posted_balance} currency={a.currency} /> },
                ]} />
            </Card>
          </div>
        )}
      </Async>
    </>
  );
}
