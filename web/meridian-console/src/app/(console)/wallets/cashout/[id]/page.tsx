"use client";

import { useParams } from "next/navigation";
import { Suspense } from "react";
import { useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Card, Loading, Mono, Money, PageHeader } from "@/components/ui";
import { Action, Details, type Row } from "@/components/ops";

// WE-TXN-2 (G-07): one cash-out authorisation — agent passcode / cardless ATM OTP / pre-authorised auth code.
// Redeemable only while ISSUED; the redeem code is entered by the operator (or captured at the ATM / agent till).
const REDEEMABLE = ["ISSUED"];

function CashoutDetail() {
  const { id } = useParams<{ id: string }>();
  const key = ["wallet-cashout-authorisations", id];
  const inv = [["wallet-cashout-authorisations"]];
  const q = useQuery({ queryKey: key, queryFn: () => call<Row>("GET", `/v1/wallet-cashout-authorisations/${id}`) });

  if (q.isLoading) return <Loading />;
  const c = q.data;
  const status = String(c?.status ?? "");

  return (
    <>
      <PageHeader title="Cash-out authorisation"
        subtitle={c && <><Mono>{String(c.reference ?? id)}</Mono> · {String(c.auth_type ?? "").replaceAll("_", " ")} · <Badge value={status} /></>}
        actions={c && REDEEMABLE.includes(status) && (
          <Action label="Redeem" path={`/v1/wallet-cashout-authorisations/${id}/redeem`} invalidate={inv}
            title="Redeem cash-out authorisation"
            description="Validates the one-time code and releases the agent/ATM cash-out. Idempotent per authorisation."
            fields={[{ name: "code", label: "Authorisation code", required: true, placeholder: "passcode / OTP / auth code" }]} />
        )} />

      {c && (
        <Card title="Authorisation">
          <Details items={[
            ["Reference", <Mono key="r">{String(c.reference ?? "—")}</Mono>],
            ["Type", String(c.auth_type ?? "").replaceAll("_", " ")],
            ["Amount", <Money key="a" amount={c.amount as string} currency={c.currency as string} />],
            ["Wallet", c.wallet_id ? <Mono key="w">{String(c.wallet_id)}</Mono> : null],
            ["Beneficiary", c.beneficiary_msisdn ?? c.beneficiary ?? null],
            ["Agent", c.agent_id ? <Mono key="g">{String(c.agent_id)}</Mono> : null],
            ["Issued", c.created_at],
            ["Expires", c.expires_at],
            ["Redeemed", c.redeemed_at],
            ["Status", <Badge key="s" value={status} />],
          ]} />
        </Card>
      )}
    </>
  );
}

export default function Page() {
  return <Suspense><CashoutDetail /></Suspense>;
}
