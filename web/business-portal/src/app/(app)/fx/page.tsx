"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useAccounts, usePermissions } from "@/api/business";
import { api, idem, unwrap } from "@/api/client";
import { ActionButton, opt } from "@/components/actions";
import { Async, Badge, Card, DateTime, Empty, LinkButton, Money, PageHeader, Table, Tabs } from "@/components/ui";

export default function FxPage() {
  const perms = usePermissions();
  const accounts = useAccounts();
  const [tab, setTab] = useState<"quotes" | "deals">("quotes");
  const quotes = useQuery({
    queryKey: ["fx-quotes"],
    queryFn: async () => unwrap(await api.GET("/v1/business/fx-quotes", { params: { query: { limit: 100 } } })).data,
  });
  const deals = useQuery({
    queryKey: ["fx-deals"], enabled: tab === "deals",
    queryFn: async () => unwrap(await api.GET("/v1/business/fx-deals", { params: { query: { limit: 100 } } })).data,
  });
  const currencies = [...new Set((accounts.data ?? []).map((a) => a.currency))];

  return (
    <>
      <PageHeader title="FX & international" subtitle="Firm quotes for the business and the deals booked for released international payments"
        actions={<>
          <ActionButton label="Get a quote" variant="secondary" size="md" confirmLabel="Get quote"
            description="Sell the currency of one of your accounts. Fix either the amount you sell or the amount you buy."
            fields={[
              { name: "sell_currency", label: "Sell currency", type: "select", required: true, options: currencies.map((c) => ({ value: c, label: c })) },
              { name: "buy_currency", label: "Buy currency", required: true, placeholder: "USD" },
              { name: "sell_amount", label: "Sell amount", type: "number" },
              { name: "buy_amount", label: "Buy amount", type: "number" },
            ]}
            run={async (v, key) => unwrap(await api.POST("/v1/business/fx-quotes", {
              params: { header: idem(key) },
              body: { sell_currency: v.sell_currency, buy_currency: v.buy_currency.toUpperCase(),
                sell_amount: opt(v.sell_amount), buy_amount: opt(v.buy_amount) } }))} />
          {perms.canInitiate && <LinkButton href="/payments/new?kind=INTERNATIONAL">New international payment</LinkButton>}
        </>} />
      <Tabs value={tab} onChange={setTab} tabs={[{ key: "quotes", label: "Quotes" }, { key: "deals", label: "Deals" }]} />
      <Card flush>
        {tab === "quotes" ? (
          <Async q={quotes} empty={<div className="p-4"><Empty title="No quotes yet" /></div>}>
            {(rows) => (
              <Table rows={rows} rowKey={(q) => q.id} caption="FX quotes" columns={[
                { header: "Created", cell: (q) => <DateTime value={q.created_at} /> },
                { header: "Sell", align: "right", cell: (q) => <Money amount={q.sell_amount} currency={q.sell_currency} /> },
                { header: "Buy", align: "right", cell: (q) => <Money amount={q.buy_amount} currency={q.buy_currency} /> },
                { header: "Rate", align: "right", cell: (q) => <span className="num">{q.customer_rate}</span> },
                { header: "Fee", align: "right", cell: (q) => <Money amount={q.fee_amount} currency={q.fee_currency ?? q.sell_currency} /> },
                { header: "Expires", cell: (q) => <DateTime value={q.expires_at} /> },
                { header: "Status", cell: (q) => <Badge value={q.status} /> },
              ]} />
            )}
          </Async>
        ) : (
          <Async q={deals} empty={<div className="p-4"><Empty title="No deals yet" /></div>}>
            {(rows) => (
              <Table rows={rows} rowKey={(d) => d.id} caption="FX deals" columns={[
                { header: "Value date", cell: (d) => <DateTime value={d.value_date} dateOnly /> },
                { header: "Side", cell: (d) => d.side },
                { header: "Sell", align: "right", cell: (d) => <Money amount={d.sell_amount} currency={d.sell_currency} /> },
                { header: "Buy", align: "right", cell: (d) => <Money amount={d.buy_amount} currency={d.buy_currency} /> },
                { header: "Rate", align: "right", cell: (d) => <span className="num">{d.rate}</span> },
                { header: "Status", cell: (d) => <Badge value={d.status} /> },
              ]} />
            )}
          </Async>
        )}
      </Card>
    </>
  );
}
