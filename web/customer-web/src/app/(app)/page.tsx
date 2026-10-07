"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { me, unwrap } from "@/api/client";
import { useCustomerId, useMe, useMyAccounts, useMyCards, useMyLoans, useMyPayments, useMyWallets } from "@/api/me";
import { AccountTile, CardTile, PaymentRow, WalletTile } from "@/components/banking";
import { Async, Card, Empty, LinkButton, Money, Badge } from "@/components/ui";
import { useT } from "@/lib/i18n";

export default function Home() {
  const t = useT();
  const c = useCustomerId();
  const self = useMe();
  const accounts = useMyAccounts();
  const walletsQ = useMyWallets();
  const cardsQ = useMyCards();
  const loansQ = useMyLoans();
  const activity = useMyPayments(8);
  const banners = useQuery({
    queryKey: ["announcements", c], enabled: !!c,
    queryFn: async () => unwrap(await me.GET("/v1/me/announcements", {
      params: { query: { app: "CUSTOMER_WEB", placement: "HOME_BANNER", limit: 3 } } })).data,
  });

  const name = self.data?.first_name ?? self.data?.display_name;

  return (
    <>
      <div className="mb-5">
        <h1 className="text-2xl font-semibold tracking-tight">{name ? t("home.greeting", { name }) : t("home.title")}</h1>
        {self.data?.customer_number && <p className="text-sm text-muted">{t("home.customerNo", { no: self.data.customer_number })}</p>}
      </div>

      {(banners.data ?? []).map((b) => (
        <aside key={b.id} aria-label={t("home.announcement")} className="mb-4 rounded-[var(--radius)] bg-accent-soft px-4 py-3 text-sm">
          <p className="font-semibold text-accent">{b.title}</p>
          <p className="mt-1">{b.body}</p>
        </aside>
      ))}

      <div className="mb-6 grid grid-cols-2 gap-2 sm:grid-cols-4">
        <LinkButton href="/move/transfer">{t("move.ownTransfer")}</LinkButton>
        <LinkButton href="/move/pay" variant="secondary">{t("move.payPayee")}</LinkButton>
        <LinkButton href="/move/payees" variant="secondary">{t("move.payees")}</LinkButton>
        <LinkButton href="/standing-orders" variant="secondary">{t("nav.standingOrders")}</LinkButton>
      </div>

      <div className="grid gap-5 lg:grid-cols-2">
        <Card title={t("home.accounts")}>
          <Async q={accounts} empty={<Empty title={t("home.noAccounts")}>{t("home.noAccountsHint")}</Empty>}>
            {(list) => <div className="flex flex-col gap-2">{list.map((a) => <AccountTile key={a.id} account={a} />)}</div>}
          </Async>
        </Card>

        <Card title={t("home.activity")} actions={<Link href="/move" className="prose-link text-sm">{t("common.seeAll")}</Link>}>
          <Async q={activity} empty={<Empty title={t("home.noActivity")} />}>
            {(list) => <ul className="-mx-2 flex flex-col">{list.map((p) => <PaymentRow key={p.id} p={p} />)}</ul>}
          </Async>
        </Card>

        <Card title={t("home.wallets")}>
          <Async q={walletsQ} empty={<p className="text-sm text-muted">{t("home.noWallets")}</p>}>
            {(list) => <div className="flex flex-col gap-2">{list.map((w) => <WalletTile key={w.id} wallet={w} />)}</div>}
          </Async>
        </Card>

        <Card title={t("nav.cards")} actions={<Link href="/cards" className="prose-link text-sm">{t("common.seeAll")}</Link>}>
          <Async q={cardsQ} empty={<p className="text-sm text-muted">{t("cards.none")}</p>}>
            {(list) => <div className="flex flex-col gap-2">{list.slice(0, 3).map((x) => <CardTile key={x.id} card={x} />)}</div>}
          </Async>
        </Card>

        <Card title={t("nav.loans")} actions={<Link href="/loans" className="prose-link text-sm">{t("common.seeAll")}</Link>}>
          <Async q={loansQ} empty={<p className="text-sm text-muted">{t("loans.none")}</p>}>
            {(list) => (
              <ul className="flex flex-col gap-2">
                {list.filter((l) => l.status !== "CLOSED").slice(0, 3).map((l) => (
                  <li key={l.id}>
                    <Link href={`/loans/${l.id}`} className="flex min-h-14 items-center justify-between gap-3 rounded-[var(--radius)] border border-border px-4 py-2 hover:border-accent focus-visible:outline-3 focus-visible:outline-focus">
                      <span>
                        <span className="block font-medium">{l.loan_number}</span>
                        <span className="block text-xs text-muted">{t("loans.outstanding")}</span>
                      </span>
                      <span className="text-right">
                        <Money amount={l.total_outstanding} currency={l.currency} className="block font-semibold" />
                        <Badge value={l.status} />
                      </span>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </Async>
        </Card>
      </div>
    </>
  );
}
