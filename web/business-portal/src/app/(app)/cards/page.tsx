"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { accountLabel, accountOptions, useAccounts, usePermissions } from "@/api/business";
import { api, idem, unwrap } from "@/api/client";
import { ActionButton, opt } from "@/components/actions";
import { Async, Badge, Card, Empty, PageHeader, Table } from "@/components/ui";

export default function CardsPage() {
  const perms = usePermissions();
  const accounts = useAccounts();
  const cards = useQuery({
    queryKey: ["cards"],
    queryFn: async () => unwrap(await api.GET("/v1/business/cards", { params: { query: { limit: 200 } } })).data,
  });
  const products = useQuery({
    queryKey: ["card-products"], enabled: perms.isAdmin,
    queryFn: async () => unwrap(await api.GET("/v1/business/card-products", { params: { query: { limit: 100 } } })).data,
  });

  return (
    <>
      <PageHeader title="Expense cards" subtitle="Virtual expense cards for employees, linked to the business's accounts"
        actions={perms.isAdmin ? (
          <ActionButton label="Issue card" variant="primary" size="md" confirmLabel="Issue card"
            fields={[
              { name: "card_product_id", label: "Card product", type: "select", required: true,
                options: (products.data ?? []).map((p) => ({ value: p.id, label: `${p.name} (${p.code})` })) },
              { name: "deposit_account_id", label: "Linked account", type: "select", required: true, options: accountOptions(accounts.data) },
              { name: "cardholder_name", label: "Cardholder name", required: true },
              { name: "nickname", label: "Nickname (e.g. Travel – J. Doe)" },
            ]}
            run={async (v, key) => unwrap(await api.POST("/v1/business/cards", {
              params: { header: idem(key) },
              body: { card_product_id: v.card_product_id, deposit_account_id: v.deposit_account_id,
                cardholder_name: v.cardholder_name, nickname: opt(v.nickname) } }))} />
        ) : undefined} />
      <Card flush>
        <Async q={cards} empty={<div className="p-4"><Empty title="No expense cards yet" /></div>}>
          {(rows) => (
            <Table rows={rows} rowKey={(c) => c.id} caption="Expense cards" columns={[
              { header: "Card", cell: (c) => <Link className="prose-link" href={`/cards/${c.id}`}>{c.nickname ?? c.cardholder_name} •••• {c.last4}</Link> },
              { header: "Cardholder", cell: (c) => c.cardholder_name },
              { header: "Expires", cell: (c) => `${String(c.expiry_month).padStart(2, "0")}/${c.expiry_year}` },
              { header: "Account", cell: (c) => <span className="text-xs">{accountLabel(accounts.data?.find((a) => a.id === c.deposit_account_id))}</span> },
              { header: "Form", cell: (c) => c.form_factor },
              { header: "Status", cell: (c) => <Badge value={c.status} /> },
            ]} />
          )}
        </Async>
      </Card>
    </>
  );
}
