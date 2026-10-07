"use client";

import { useMyPayments } from "@/api/me";
import { PaymentRow } from "@/components/banking";
import { Async, Card, Empty, LinkButton, PageHeader } from "@/components/ui";
import { useT } from "@/lib/i18n";

export default function MoveMoney() {
  const t = useT();
  const recent = useMyPayments(30);
  return (
    <>
      <PageHeader title={t("nav.move")} subtitle={t("move.subtitle")} />
      <div className="mb-6 grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-4">
        <LinkButton href="/move/transfer">{t("move.ownTransfer")}</LinkButton>
        <LinkButton href="/move/pay" variant="secondary">{t("move.payPayee")}</LinkButton>
        <LinkButton href="/move/payees" variant="secondary">{t("move.payees")}</LinkButton>
        <LinkButton href="/standing-orders" variant="secondary">{t("nav.standingOrders")}</LinkButton>
      </div>
      <Card title={t("move.history")}>
        <Async q={recent} empty={<Empty title={t("home.noActivity")} />}>
          {(list) => <ul className="-mx-2 flex flex-col">{list.map((p) => <PaymentRow key={p.id} p={p} />)}</ul>}
        </Async>
      </Card>
    </>
  );
}
