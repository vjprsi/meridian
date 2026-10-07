"use client";

import Link from "next/link";
import { useAccountBalance, useCustomerId, useWalletBalance } from "@/api/me";
import type { Card, DepositAccount, Payment, Wallet } from "@/api/types";
import { useT } from "@/lib/i18n";
import { Badge, DateTime, Money, cx, masked } from "./ui";

export function accountName(a: Pick<DepositAccount, "nickname" | "account_type" | "account_number">) {
  return a.nickname || `${a.account_type.charAt(0)}${a.account_type.slice(1).toLowerCase().replaceAll("_", " ")} ${(a.account_number ?? "").slice(-4)}`.trim();
}

const tile = "flex min-h-16 items-center justify-between gap-3 rounded-[var(--radius)] border border-border bg-surface px-4 py-3 hover:border-accent focus-visible:outline-3 focus-visible:outline-focus";

export function AccountTile({ account }: { account: DepositAccount & { holder_role?: string } }) {
  const t = useT();
  const bal = useAccountBalance(account.id);
  return (
    <Link href={`/accounts/${account.id}`} className={tile}>
      <span className="min-w-0">
        <span className="block truncate font-medium">{accountName(account)}</span>
        <span className="block text-xs text-muted">
          {masked(account.iban ?? account.account_number)}
          {account.holder_role && account.holder_role !== "PRIMARY" && <> · {account.holder_role.toLowerCase().replaceAll("_", " ")}</>}
        </span>
      </span>
      <span className="text-right">
        {bal.error ? <span className="text-xs text-bad">{t("home.balanceUnavailable")}</span> : (
          <>
            <Money amount={bal.data?.available_balance} currency={account.currency} className="block font-semibold" />
            <span className="block text-xs text-muted">{t("account.available")}</span>
          </>
        )}
        {account.status !== "ACTIVE" && <Badge value={account.status} />}
      </span>
    </Link>
  );
}

export function WalletTile({ wallet }: { wallet: Wallet }) {
  const t = useT();
  const bal = useWalletBalance(wallet.id);
  return (
    <div className={cx(tile, "hover:border-border")}>
      <span className="min-w-0">
        <span className="block truncate font-medium">{wallet.display_name}</span>
        <span className="block text-xs text-muted">{wallet.msisdn ?? wallet.wallet_number}</span>
      </span>
      <span className="text-right">
        {bal.error ? <span className="text-xs text-bad">{t("home.balanceUnavailable")}</span>
          : <Money amount={bal.data?.available_balance} currency={wallet.currency} className="block font-semibold" />}
        {wallet.status !== "ACTIVE" && <Badge value={wallet.status} />}
      </span>
    </div>
  );
}

export function CardTile({ card }: { card: Card }) {
  return (
    <Link href={`/cards/${card.id}`} className={tile}>
      <span className="min-w-0">
        <span className="block truncate font-medium">{card.nickname || card.cardholder_name}</span>
        <span className="block font-mono text-sm text-muted" aria-label={`card ending ${card.last4 ?? ""}`}>
          •••• {card.last4 ?? "····"}
        </span>
      </span>
      <span className="flex flex-col items-end gap-1 text-xs text-muted">
        <Badge value={card.status} />
        {card.form_factor.toLowerCase()}
      </span>
    </Link>
  );
}

/** A payment from the customer's point of view: money out is negative. */
export function PaymentRow({ p }: { p: Payment }) {
  const c = useCustomerId();
  const t = useT();
  const outgoing = p.direction !== "INBOUND" && p.customer_id === c;
  return (
    <li>
      <Link href={`/move/receipt/${p.id}`} className="flex min-h-14 items-center justify-between gap-3 rounded-[var(--radius)] px-2 py-2 hover:bg-surface-2 focus-visible:outline-3 focus-visible:outline-focus">
        <span className="min-w-0">
          <span className="block truncate font-medium">
            {p.creditor_name || p.reference || p.payment_type.toLowerCase().replaceAll("_", " ")}
          </span>
          <span className="block text-xs text-muted"><DateTime value={p.created_at} /> · <Badge value={p.status} /></span>
        </span>
        <Money amount={outgoing ? `-${p.amount}` : p.amount} currency={p.currency} signed
          className={cx("font-semibold", !outgoing && "text-good")} />
        <span className="sr-only">{outgoing ? t("activity.out") : t("activity.in")}</span>
      </Link>
    </li>
  );
}
