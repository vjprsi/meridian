"use client";

import { useParams } from "next/navigation";
import { Suspense } from "react";
import { useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Card, Loading, Mono, Money, PageHeader } from "@/components/ui";
import { Action, Details, type Row } from "@/components/ops";

// WE-TXN-1 (G-03): one unregistered / voucher transfer held in e-money suspense against a one-time passcode.
// Claimed by the registering wallet while ISSUED; the expiry / rollback sweep runs from the list page.
const CLAIMABLE = ["ISSUED"];

function VoucherDetail() {
  const { id } = useParams<{ id: string }>();
  const key = ["wallet-vouchers", id];
  const inv = [["wallet-vouchers"]];
  const q = useQuery({ queryKey: key, queryFn: () => call<Row>("GET", `/v1/wallet-vouchers/${id}`) });

  if (q.isLoading) return <Loading />;
  const v = q.data;
  const status = String(v?.status ?? "");

  return (
    <>
      <PageHeader title="Voucher"
        subtitle={v && <><Mono>{String(v.reference ?? id)}</Mono> · {String(v.voucher_type ?? "").replaceAll("_", " ")} · <Badge value={status} /></>}
        actions={v && CLAIMABLE.includes(status) && (
          <Action label="Claim" path={`/v1/wallet-vouchers/${id}/claim`} invalidate={inv}
            title="Claim voucher"
            description="Releases the suspended value into the claiming wallet once the passcode matches."
            fields={[
              { name: "claiming_wallet_id", label: "Claiming wallet", required: true, placeholder: "wallet uuid" },
              { name: "passcode", label: "Passcode", required: true },
            ]} />
        )} />

      {v && (
        <Card title="Voucher">
          <Details items={[
            ["Reference", <Mono key="r">{String(v.reference ?? "—")}</Mono>],
            ["Type", String(v.voucher_type ?? "").replaceAll("_", " ")],
            ["Amount", <Money key="a" amount={v.amount as string} currency={v.currency as string} />],
            ["Issuing wallet", v.issuing_wallet_id ? <Mono key="iw">{String(v.issuing_wallet_id)}</Mono> : null],
            ["Beneficiary", v.beneficiary_msisdn ?? v.beneficiary ?? null],
            ["Claiming wallet", v.claiming_wallet_id ? <Mono key="cw">{String(v.claiming_wallet_id)}</Mono> : null],
            ["Issued", v.created_at],
            ["Expires", v.expires_at],
            ["Claimed", v.claimed_at],
            ["Status", <Badge key="s" value={status} />],
          ]} />
        </Card>
      )}
    </>
  );
}

export default function Page() {
  return <Suspense><VoucherDetail /></Suspense>;
}
