"use client";

import { useParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Button, Card, DateTime, ErrorBanner, Loading, Money, Mono, PageHeader, Table } from "@/components/ui";
import {
  Action, Details, Filters, Pager, Stat, Tabs, Timeline, amountCell, dateCell, rows, str, usePaged, type Row,
} from "@/components/ops";
import type { AccountBalance } from "@/lib/types";

const TXN_TYPES = ["P2P", "CASH_IN", "CASH_OUT", "MERCHANT_PAYMENT", "BILL_PAYMENT", "AIRTIME", "G2P", "BULK_DISBURSEMENT",
  "WALLET_TO_BANK", "BANK_TO_WALLET", "FLOAT_TRANSFER", "COMMISSION_PAYOUT", "INTEROP_OUT", "INTEROP_IN", "REFUND", "REVERSAL",
  "FEE", "ADJUSTMENT", "LOAN_DISBURSEMENT", "LOAN_REPAYMENT"] as const;
const TXN_STATUSES = ["INITIATED", "PENDING_CONFIRMATION", "PENDING_APPROVAL", "PROCESSING", "COMPLETED", "FAILED", "REVERSED",
  "EXPIRED", "CANCELLED"] as const;

const REASON = [{ name: "reason", label: "Reason", required: true, type: "textarea" as const }];

type Tab = "transactions" | "limits" | "history";

function WalletDetail() {
  const { id } = useParams<{ id: string }>();
  const [tab, setTab] = useState<Tab>("transactions");
  const [txFilters, setTxFilters] = useState<Record<string, string>>({});
  const [selected, setSelected] = useState<Row | null>(null);

  const wallet = useQuery({ queryKey: ["wallet", id], queryFn: () => call<Row>("GET", `/v1/wallets/${id}`) });
  const balance = useQuery({
    queryKey: ["wallet", id, "balance"], retry: false,
    queryFn: () => call<AccountBalance>("GET", `/v1/wallets/${id}/balance`),
    enabled: !!wallet.data?.ledger_account_id,
  });
  const limits = useQuery({
    queryKey: ["wallet", id, "limits"], retry: false, enabled: tab === "limits",
    queryFn: () => call<Row>("GET", `/v1/wallets/${id}/limits`),
  });
  const txns = usePaged("wallet-transactions", `/v1/wallets/${id}/transactions`, txFilters);
  const history = usePaged("wallet-history", `/v1/wallets/${id}/status-history`, {}, { enabled: tab === "history" });

  if (wallet.isLoading) return <Loading />;
  const w = wallet.data;
  const status = String(w?.status ?? "");
  const cur = w?.currency as string | undefined;
  const inv = [["wallet", id], ["wallets"]];

  return (
    <>
      <PageHeader title={str(w?.display_name ?? "Wallet")}
        subtitle={w && <><Mono>{str(w.wallet_number)}</Mono> · {str(w.wallet_type)} · {str(w.msisdn)} · tier {str(w.kyc_tier)} · <Badge value={status} /></>}
        actions={w && <>
          {(status === "PENDING" || status === "DORMANT") && (
            <Action label="Activate" path={`/v1/wallets/${id}/activate`} invalidate={inv}
              description="Activating opens the ledger account on first activation."
              fields={[{ name: "reason", label: "Reason", type: "textarea" }]} />
          )}
          {status === "ACTIVE" && (
            <Action label="Suspend" variant="secondary" path={`/v1/wallets/${id}/suspend`} invalidate={inv} fields={REASON}
              description="Suspension freezes the ledger account for debits; credits still arrive." />
          )}
          {(status === "ACTIVE" || status === "SUSPENDED" || status === "DORMANT") && (
            <Action label="Block" variant="danger" path={`/v1/wallets/${id}/block`} invalidate={inv} fields={REASON}
              description="Blocking (fraud or legal) fully freezes the ledger account." />
          )}
          {(status === "SUSPENDED" || status === "BLOCKED") && (
            <Action label="Reactivate" path={`/v1/wallets/${id}/reactivate`} invalidate={inv} fields={REASON}
              description="Checker action: must be a different user from the one who suspended or blocked the wallet." />
          )}
          {status !== "CLOSED" && (
            <Action label="Close" variant="danger" path={`/v1/wallets/${id}/close`} invalidate={inv}
              description="Any residual balance is swept to another wallet in the same currency."
              fields={[...REASON, { name: "sweep_to_wallet_id", label: "Sweep to wallet ID", hint: "Required if the balance is not zero" }]} />
          )}
          {status !== "CLOSED" && (
            <Action label="Upgrade tier" variant="secondary" path={`/v1/wallets/${id}/upgrade-tier`} invalidate={inv}
              description="Tiers only go up, and require a verified KYC profile."
              body={(v) => ({ kyc_tier: Number(v.kyc_tier), kyc_profile_id: v.kyc_profile_id })}
              fields={[{ name: "kyc_tier", label: "New KYC tier", type: "number", required: true },
                { name: "kyc_profile_id", label: "KYC profile ID", required: true }]} />
          )}
        </>} />
      <ErrorBanner error={wallet.error} />

      {w && (
        <>
          <div className="mb-6 grid grid-cols-1 gap-4 sm:grid-cols-3">
            <Stat label="Available" value={<Money amount={balance.data?.available_balance} currency={cur} />} />
            <Stat label="Posted" value={<Money amount={balance.data?.posted_balance} currency={cur} />} />
            <Stat label="Pending debits" value={<Money amount={balance.data?.pending_debits} currency={cur} />}
              hint={balance.isError ? "balance unavailable" : !w.ledger_account_id ? "no ledger account yet" : undefined} />
          </div>
          <Card title="Wallet" className="mb-6">
            <Details items={[
              ["Customer", w.customer_id ? <Mono>{String(w.customer_id)}</Mono> : null],
              ["Agent", w.agent_id ? <Mono>{String(w.agent_id)}</Mono> : null],
              ["Merchant", w.merchant_id ? <Mono>{String(w.merchant_id)}</Mono> : null],
              ["Alias", w.alias],
              ["Opened channel", w.opened_channel],
              ["Ledger account", w.ledger_account_id ? <Mono>{String(w.ledger_account_id)}</Mono> : null],
              ["PIN set", w.has_pin],
              ["PIN locked until", w.pin_locked_until],
              ["Activated", w.activated_at],
              ["Last activity", w.last_activity_at],
              ["Dormant since", w.dormant_since],
              ["Status reason", w.status_reason],
            ]} />
          </Card>
        </>
      )}

      <Tabs value={tab} onChange={setTab} tabs={[
        { id: "transactions", label: "Transactions" }, { id: "limits", label: "Limits" }, { id: "history", label: "Status history" },
      ]} />

      {tab === "transactions" && (
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
          <Card className="lg:col-span-2">
            <Filters value={txFilters} onChange={setTxFilters} specs={[
              { name: "txn_type", label: "Type", options: TXN_TYPES },
              { name: "status", label: "Status", options: TXN_STATUSES },
              { name: "from", label: "From", type: "date" },
              { name: "to", label: "To", type: "date" },
            ]} />
            <ErrorBanner error={txns.error} />
            {txns.isLoading ? <Loading /> : (
              <Table rows={txns.rows} onRow={setSelected} empty="No transactions."
                columns={[
                  { key: "created_at", header: "When", render: dateCell("created_at") },
                  { key: "reference", header: "Reference", render: (r) => <Mono>{str(r.reference)}</Mono> },
                  { key: "txn_type", header: "Type" },
                  { key: "direction", header: "Dir", render: (r) => (r.payer_wallet_id === id ? "OUT" : "IN") },
                  { key: "amount", header: "Amount", align: "right", render: amountCell() },
                  { key: "status", header: "Status", render: (r) => <Badge value={r.status as string} /> },
                ]} />
            )}
            <Pager p={txns} />
          </Card>
          <TransactionPanel txn={selected} walletId={id} onClose={() => setSelected(null)} />
        </div>
      )}

      {tab === "limits" && (
        <Card title={limits.data ? `Tier ${str(limits.data.kyc_tier)} limits · ${str(limits.data.currency)}` : "Limits"}>
          <ErrorBanner error={limits.error} />
          {limits.isLoading ? <Loading /> : (
            <Table rows={rows(limits.data, "limits")} empty="No tier limits configured."
              columns={[
                { key: "txn_type", header: "Transaction", render: (r) => str(r.txn_type ?? "ALL") },
                { key: "per_txn_max", header: "Per txn max", align: "right", render: (r) => <Money amount={r.per_txn_max as string} currency={cur} /> },
                { key: "daily_used", header: "Daily used / limit", align: "right",
                  render: (r) => <><Money amount={r.daily_used as string} currency={cur} /> / <Money amount={r.daily_amount as string} currency={cur} /></> },
                { key: "daily_remaining", header: "Daily remaining", align: "right", render: (r) => <Money amount={r.daily_remaining as string} currency={cur} /> },
                { key: "monthly_used", header: "Monthly used / limit", align: "right",
                  render: (r) => <><Money amount={r.monthly_used as string} currency={cur} /> / <Money amount={r.monthly_amount as string} currency={cur} /></> },
                { key: "max_balance", header: "Max balance", align: "right", render: (r) => <Money amount={r.max_balance as string} currency={cur} /> },
              ]} />
          )}
        </Card>
      )}

      {tab === "history" && (
        <Card title="Status history">
          <ErrorBanner error={history.error} />
          {history.isLoading ? <Loading /> : (
            <Timeline items={history.rows.map((h) => ({
              at: h.created_at, status: h.to_status as string,
              title: `${str(h.from_status)} → ${str(h.to_status)}`,
              detail: <>{str(h.reason)} · by {str(h.changed_by ?? h.created_by)}</>,
            }))} />
          )}
          <Pager p={history} />
        </Card>
      )}
    </>
  );
}

/** Selected transaction: key facts and the operator actions its status allows. */
function TransactionPanel({ txn, walletId, onClose }: { txn: Row | null; walletId: string; onClose: () => void }) {
  if (!txn) {
    return <Card title="Transaction"><p className="text-sm text-muted">Select a transaction to see its details.</p></Card>;
  }
  const s = String(txn.status);
  const inv = [["wallet-transactions"], ["wallet", walletId]];
  return (
    <Card title="Transaction" actions={<Button variant="ghost" onClick={onClose}>Close</Button>}>
      <div className="space-y-4">
        <div className="flex items-center gap-2"><Badge value={s} /><Mono>{str(txn.reference)}</Mono></div>
        <Details cols={1} items={[
          ["Type", txn.txn_type],
          ["Amount", <Money key="a" amount={txn.amount as string} currency={txn.currency as string} />],
          ["Fee", <Money key="f" amount={txn.fee_amount as string} currency={txn.currency as string} />],
          ["Payer wallet", txn.payer_wallet_id ? <Mono>{String(txn.payer_wallet_id)}</Mono> : null],
          ["Payee wallet", txn.payee_wallet_id ? <Mono>{String(txn.payee_wallet_id)}</Mono> : null],
          ["Payee", txn.payee_name ?? txn.payee_msisdn],
          ["Channel", txn.channel],
          ["Completed", txn.completed_at ? <DateTime key="c" value={String(txn.completed_at)} /> : null],
          ["Reversed amount", txn.reversed_amount],
          ["Reversal requested by", txn.reversal_requested_by],
          ["Failure", txn.failure_reason ?? txn.failure_code],
          ["Description", txn.description],
        ]} />
        <div className="flex flex-wrap gap-2">
          {s === "PENDING_CONFIRMATION" && (
            <Action label="Cancel" variant="secondary" size="sm" path={`/v1/wallet-transactions/${txn.id}/cancel`} invalidate={inv}
              description="Cancels the transaction and releases the hold." fields={[{ name: "reason", label: "Reason" }]}
              onDone={onClose} />
          )}
          {s === "COMPLETED" && !txn.reversal_requested_by && (
            <Action label="Request reversal" variant="secondary" size="sm" path={`/v1/wallet-transactions/${txn.id}/request-reversal`}
              invalidate={inv} onDone={onClose}
              description="Maker step: another user must execute the reversal."
              fields={[...REASON, { name: "amount", label: "Amount (partial reversal)", type: "number", hint: "Leave empty for a full reversal" }]} />
          )}
          {s === "COMPLETED" && (
            <Action label="Reverse" variant="danger" size="sm" path={`/v1/wallet-transactions/${txn.id}/reverse`} invalidate={inv}
              onDone={onClose} fields={REASON}
              description="Checker step: posts compensating entries. Must be a different user from the reversal maker or initiator." />
          )}
        </div>
      </div>
    </Card>
  );
}

export default function Page() {
  return <Suspense><WalletDetail /></Suspense>;
}
