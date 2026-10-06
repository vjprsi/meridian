"use client";

import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Card, ErrorBanner, Loading, Money, Mono, PageHeader, Table } from "@/components/ui";
import { Action, Details, Filters, Pager, dateCell, rows, statusCell, usePaged, type Row } from "@/components/ops";
import { AUTH_DECISIONS, AUTH_STATUSES } from "../shared";

function Authorisations() {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const selected = params.get("id");
  const [filters, setFilters] = useState<Record<string, string>>(
    params.get("card_id") ? { card_id: params.get("card_id")! } : {});
  const list = usePaged("card-authorizations", "/v1/card-authorizations", filters);
  const select = (id: string | null) => router.replace(id ? `${pathname}?id=${id}` : pathname, { scroll: false });

  return (
    <>
      <PageHeader title="Card authorisations" subtitle="Every authorisation decision with its reason, funding source and fraud score" />
      <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">
        <Card className={selected ? "xl:col-span-2" : "xl:col-span-3"}>
          <Filters value={filters} onChange={setFilters} specs={[
            { name: "status", label: "Status", options: AUTH_STATUSES },
            { name: "decision", label: "Decision", options: AUTH_DECISIONS },
            { name: "card_id", label: "Card ID" },
            { name: "mcc", label: "MCC" },
            { name: "rrn", label: "RRN" },
            { name: "created_after", label: "From", type: "date" },
            { name: "created_before", label: "To", type: "date" },
          ]} />
          <ErrorBanner error={list.error} />
          {list.isLoading ? <Loading /> : (
            <Table rows={list.rows} onRow={(r) => select(String(r.id))} empty="No authorisations match."
              columns={[
                { key: "created_at", header: "When", render: dateCell("created_at") },
                { key: "merchant_name", header: "Merchant", render: (r) => <>{String(r.merchant_name ?? "—")}
                  <span className="ml-1 text-xs text-muted">{String(r.merchant_country ?? "")}</span></> },
                { key: "mcc", header: "MCC" },
                { key: "message_type", header: "Type" },
                { key: "amount", header: "Amount", align: "right", render: (r) =>
                  <Money amount={r.transaction_amount as string} currency={r.transaction_currency as string} /> },
                { key: "decision", header: "Decision", render: statusCell("decision") },
                { key: "status", header: "Status", render: statusCell() },
              ]} />
          )}
          <Pager p={list} />
        </Card>
        {selected && <AuthDetail id={selected} onClose={() => select(null)} />}
      </div>
    </>
  );
}

function AuthDetail({ id, onClose }: { id: string; onClose: () => void }) {
  const auth = useQuery({ queryKey: ["card-authorization", id], queryFn: () => call<Row>("GET", `/v1/card-authorizations/${id}`) });
  const related = useQuery({
    queryKey: ["card-authorization-related", id],
    queryFn: () => call<unknown>("GET", `/v1/card-authorizations/${id}/related?limit=50`),
  });
  const a = auth.data;
  const inv = [["card-authorization", id], ["card-authorizations"]];
  return (
    <Card title="Authorisation" actions={<button className="text-sm text-muted hover:text-text" onClick={onClose}>Close</button>}>
      <ErrorBanner error={auth.error} />
      {auth.isLoading ? <Loading /> : a && (
        <div className="space-y-5">
          <div className="flex items-baseline justify-between">
            <span className="text-xl font-semibold"><Money amount={a.transaction_amount as string} currency={a.transaction_currency as string} /></span>
            {statusCell()(a)}
          </div>
          <Details cols={1} items={[
            ["Card", <Link key="c" className="text-accent hover:underline" href={`/cards/${String(a.card_id)}`}><Mono>{String(a.card_id)}</Mono></Link>],
            ["Decision", a.decline_reason ? `${String(a.decision)} · ${String(a.decline_reason)}` : a.decision],
            ["Decline detail", a.decline_detail],
            ["Merchant", [a.merchant_name, a.merchant_city, a.merchant_country].filter(Boolean).join(", ")],
            ["MCC", a.mcc], ["Channel", a.channel], ["Entry mode", a.pos_entry_mode],
            ["Billing", <Money key="b" amount={a.billing_amount as string} currency={a.billing_currency as string} />],
            ["Approved", a.approved_amount], ["Reversed", a.reversed_amount], ["Cleared", a.cleared_amount],
            ["Funding", a.funding_source], ["Stand-in", a.stand_in], ["JIT", a.jit_used ? `${String(a.jit_result)} (${String(a.jit_latency_ms ?? "?")} ms)` : false],
            ["Fraud score", a.fraud_score], ["Fraud decision", a.fraud_decision],
            ["Auth code", a.auth_code], ["Response code", a.response_code], ["RRN", a.rrn], ["STAN", a.stan],
            ["Network txn", a.network_transaction_id], ["3DS / ECI", a.eci], ["CVV / AVS", [a.cvv_result, a.avs_result].filter(Boolean).join(" / ")],
            ["Expires", a.expires_at], ["Processing ms", a.processing_time_ms], ["When", a.created_at],
          ]} />
          {["APPROVED", "PARTIALLY_CLEARED", "PARTIALLY_REVERSED"].includes(String(a.status)) && (
            <Action label="Expire & release hold" variant="danger" path={`/v1/card-authorizations/${id}/expire`} invalidate={inv}
              description="Releases the funds hold of a stale authorisation. A later presentment will be force-posted."
              fields={[{ name: "reason", label: "Reason" }]} />
          )}
          <div>
            <p className="mb-2 text-xs font-medium uppercase tracking-wide text-muted">Related messages</p>
            <Table rows={rows(related.data)} empty="No incrementals, reversals or advices."
              columns={[
                { key: "message_type", header: "Type" },
                { key: "amount", header: "Amount", align: "right", render: (r) =>
                  <Money amount={r.transaction_amount as string} currency={r.transaction_currency as string} /> },
                { key: "status", header: "Status", render: statusCell() },
              ]} />
          </div>
        </div>
      )}
    </Card>
  );
}

export default function Page() {
  return <Suspense><Authorisations /></Suspense>;
}
