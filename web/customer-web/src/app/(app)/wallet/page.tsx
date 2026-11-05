"use client";

import { useState } from "react";
import { useMyWallets, useWalletBalance, useWalletTransactions } from "@/api/me";
import type { Wallet, WalletTransaction } from "@/api/types";
import { ActionTile } from "@/components/wallet-flow";
import { Async, Badge, Card, DateTime, Empty, LinkButton, Money, PageHeader, Select, cx } from "@/components/ui";
import { useT } from "@/lib/i18n";

const ICON = {
  send: "M4 12l16-8-6 16-3-6-7-2z",
  request: "M12 20V6m0 0l-5 5m5-5l5 5",
  airtime: "M7 3h10v18H7zM10 18h4",
  bills: "M6 3h12v18l-3-2-3 2-3-2-3 2zM9 8h6M9 12h6",
  qr: "M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h2v2h-2zM18 14h2v2h-2zM14 18h2v2h-2zM18 18h2v2h-2z",
  merchant: "M4 7h16l-1 13H5zM9 7V5a3 3 0 0 1 6 0v2",
};

/** A wallet transaction from the selected wallet's point of view (money out is negative). */
function TxnRow({ txn, walletId }: { txn: WalletTransaction; walletId: string }) {
  const outgoing = txn.payer_wallet_id === walletId || (!txn.payee_wallet_id && txn.txn_type !== "CASH_IN" && txn.txn_type !== "BANK_TO_WALLET" && txn.txn_type !== "INTEROP_IN" && txn.txn_type !== "REFUND");
  const title = txn.payee_name || txn.payee_msisdn || txn.payee_external_ref || txn.txn_type.replaceAll("_", " ").toLowerCase();
  return (
    <li className="flex min-h-14 items-center justify-between gap-3 px-2 py-2">
      <span className="min-w-0">
        <span className="block truncate font-medium">{title}</span>
        <span className="block text-xs text-muted"><DateTime value={txn.created_at} /> · <Badge value={txn.status} /></span>
      </span>
      <Money amount={outgoing ? `-${txn.amount}` : txn.amount} currency={txn.currency} signed
        className={cx("font-semibold", !outgoing && "text-good")} />
    </li>
  );
}

export default function WalletHome() {
  const t = useT();
  const wallets = useMyWallets();
  const [selected, setSelected] = useState<string>("");

  return (
    <>
      <PageHeader title={t("wallet.title")} subtitle={t("wallet.subtitle")} />
      <Async q={wallets} empty={<Empty title={t("wallet.none")}>{t("wallet.noneHint")}</Empty>}>
        {(list) => {
          const active = list.filter((w) => w.status === "ACTIVE");
          const usable = active.length ? active : list;
          if (usable.length === 0) return <Empty title={t("wallet.none")}>{t("wallet.noneHint")}</Empty>;
          const wallet = usable.find((w) => w.id === selected) ?? usable.find((w) => w.is_default) ?? usable[0];
          return <WalletDashboard key={wallet.id} wallet={wallet} wallets={usable} selected={wallet.id!}
            onSelect={setSelected} />;
        }}
      </Async>
    </>
  );
}

function WalletDashboard({ wallet, wallets, selected, onSelect }:
  { wallet: Wallet; wallets: Wallet[]; selected: string; onSelect: (id: string) => void }) {
  const t = useT();
  const bal = useWalletBalance(wallet.id);
  const txns = useWalletTransactions(wallet.id, 15);
  const q = `?wallet=${wallet.id}`;

  return (
    <div className="flex flex-col gap-5">
      <Card>
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div className="min-w-0">
            {wallets.length > 1 ? (
              <Select aria-label={t("wallet.select")} value={selected} onChange={(e) => onSelect(e.target.value)}
                className="mb-2 max-w-xs"
                options={wallets.map((w) => ({ value: w.id!, label: `${w.display_name} · ${w.msisdn ?? w.wallet_number}` }))} />
            ) : (
              <>
                <p className="font-medium">{wallet.display_name}</p>
                <p className="text-xs text-muted">{wallet.msisdn ?? wallet.wallet_number}</p>
              </>
            )}
            <p className="mt-2 text-sm text-muted">{t("wallet.balance")}</p>
            {bal.error ? <p className="text-sm text-bad">{t("home.balanceUnavailable")}</p>
              : <Money amount={bal.data?.available_balance} currency={wallet.currency} className="text-3xl font-semibold" />}
          </div>
          {wallet.status !== "ACTIVE" && <Badge value={wallet.status} />}
        </div>
      </Card>

      <div className="grid grid-cols-3 gap-2 sm:grid-cols-6">
        <ActionTile href={`/wallet/send${q}`} label={t("wallet.send")} icon={ICON.send} />
        <ActionTile href={`/wallet/request${q}`} label={t("wallet.request")} icon={ICON.request} />
        <ActionTile href={`/wallet/airtime${q}`} label={t("wallet.airtime")} icon={ICON.airtime} />
        <ActionTile href={`/wallet/bills${q}`} label={t("wallet.bills")} icon={ICON.bills} />
        <ActionTile href={`/wallet/qr${q}`} label={t("wallet.qr")} icon={ICON.qr} />
        <ActionTile href={`/wallet/merchant${q}`} label={t("wallet.merchant")} icon={ICON.merchant} />
      </div>

      <Card title={t("wallet.recent")} actions={<LinkButton href={`/wallet/request${q}`} variant="secondary">{t("wallet.requests")}</LinkButton>}>
        <Async q={txns} empty={<Empty title={t("wallet.noTxns")} />}>
          {(rows) => <ul className="-mx-2 divide-y divide-border">{rows.map((x) => <TxnRow key={x.id} txn={x} walletId={wallet.id!} />)}</ul>}
        </Async>
      </Card>
    </div>
  );
}
