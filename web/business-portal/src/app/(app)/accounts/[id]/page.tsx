"use client";

import { useParams } from "next/navigation";
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { usePermissions } from "@/api/business";
import { api, unwrap } from "@/api/client";
import { ExportForm, Statements } from "@/components/account-files";
import {
  Async, Badge, Button, Card, DateTime, Empty, ErrorBanner, Field, Input, LinkButton, Loading, Money, PageHeader, Summary,
  Table, Tabs,
} from "@/components/ui";

export default function AccountPage() {
  const { id } = useParams<{ id: string }>();
  const perms = usePermissions();
  const [tab, setTab] = useState<"transactions" | "statements" | "export">("transactions");
  const [range, setRange] = useState({ from: "", to: "" });
  const [draft, setDraft] = useState(range);

  const account = useQuery({
    queryKey: ["account", id],
    queryFn: async () => unwrap(await api.GET("/v1/business/accounts/{account_id}", { params: { path: { account_id: id } } })),
  });
  const balance = useQuery({
    queryKey: ["balance", id],
    queryFn: async () => unwrap(await api.GET("/v1/business/accounts/{account_id}/balance", { params: { path: { account_id: id } } })),
  });
  const txns = useInfiniteQuery({
    queryKey: ["transactions", id, range], enabled: tab === "transactions",
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam }) => unwrap(await api.GET("/v1/business/accounts/{account_id}/transactions", {
      params: { path: { account_id: id }, query: { limit: 50, cursor: pageParam,
        from: range.from ? `${range.from}T00:00:00Z` : undefined, to: range.to ? `${range.to}T23:59:59Z` : undefined } } })),
    getNextPageParam: (last) => (last.has_more ? last.next_cursor ?? undefined : undefined),
  });

  if (account.isPending) return <Loading rows={5} />;
  if (account.error || !account.data) return <ErrorBanner error={account.error} />;
  const a = account.data;
  const rows = txns.data?.pages.flatMap((p) => p.data) ?? [];

  return (
    <>
      <PageHeader back={{ href: "/accounts", label: "Accounts" }} title={a.nickname ?? a.account_number}
        subtitle={`${a.account_type} · ${a.iban ?? a.account_number} · ${a.currency}`}
        actions={perms.initiateOn(a.id) ? <LinkButton href={`/payments/new?from=${a.id}`}>Pay from this account</LinkButton> : undefined} />

      <Card className="mb-5">
        <Summary cols={4} items={[
          { label: "Available", value: <Money amount={balance.data?.available_balance} currency={a.currency} /> },
          { label: "Posted", value: <Money amount={balance.data?.posted_balance} currency={a.currency} /> },
          { label: "Pending debits", value: <Money amount={balance.data?.pending_debits} currency={a.currency} /> },
          { label: "Status", value: <Badge value={a.status} /> },
        ]} />
        <ErrorBanner error={balance.error} className="mt-3" />
      </Card>

      <Tabs value={tab} onChange={setTab} tabs={[
        { key: "transactions", label: "Transactions" }, { key: "statements", label: "Statements" }, { key: "export", label: "Accounting export" },
      ]} />

      {tab === "transactions" && (
        <Card>
          <form className="mb-4 flex flex-wrap items-end gap-3" onSubmit={(e) => { e.preventDefault(); setRange(draft); }}>
            <Field label="From"><Input type="date" value={draft.from} onChange={(e) => setDraft({ ...draft, from: e.target.value })} /></Field>
            <Field label="To"><Input type="date" value={draft.to} onChange={(e) => setDraft({ ...draft, to: e.target.value })} /></Field>
            <Button type="submit" variant="secondary">Apply</Button>
          </form>
          <Async q={{ data: txns.data ? rows : undefined, error: txns.error, isPending: txns.isPending }}
            empty={<Empty title="No transactions in this period" />}>
            {(list) => (
              <>
                <Table rows={list} rowKey={(t) => t.id} caption="Transactions" columns={[
                  { header: "Date", cell: (t) => <DateTime value={t.effective_at} /> },
                  { header: "Description", cell: (t) => t.description ?? t.reference ?? "—" },
                  { header: "Debit", align: "right", cell: (t) => t.direction === "DEBIT" ? <Money amount={t.amount} currency={t.currency} /> : "" },
                  { header: "Credit", align: "right", cell: (t) => t.direction === "CREDIT" ? <Money amount={t.amount} currency={t.currency} /> : "" },
                  { header: "Balance", align: "right", cell: (t) => <Money amount={t.balance_after} currency={t.currency} /> },
                ]} />
                {txns.hasNextPage && (
                  <Button className="mt-3" variant="secondary" onClick={() => txns.fetchNextPage()} disabled={txns.isFetchingNextPage}>
                    {txns.isFetchingNextPage ? "Loading…" : "Load more"}
                  </Button>
                )}
              </>
            )}
          </Async>
        </Card>
      )}
      {tab === "statements" && <Card><Statements account={a} /></Card>}
      {tab === "export" && <Card title="Accounting export"><ExportForm account={a} /></Card>}
    </>
  );
}
