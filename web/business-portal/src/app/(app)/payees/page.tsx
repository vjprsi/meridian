"use client";

import { usePayees, usePermissions } from "@/api/business";
import { api, idem, unwrap } from "@/api/client";
import type { Payee } from "@/api/types";
import { ActionButton, opt } from "@/components/actions";
import { Async, Badge, Card, Empty, LinkButton, PageHeader, Table } from "@/components/ui";

const ID_TYPES: Payee["account_id_type"][] = ["ACCOUNT_NUMBER", "IBAN", "BBAN", "WALLET", "PROXY", "INTERNAL"];

export default function PayeesPage() {
  const perms = usePermissions();
  const payees = usePayees();
  return (
    <>
      <PageHeader title="Beneficiaries" subtitle="Payees of the business for domestic and international payments"
        actions={perms.canMake ? (
          <ActionButton label="Add beneficiary" variant="primary" size="md" confirmLabel="Add"
            fields={[
              { name: "name", label: "Name", required: true },
              { name: "nickname", label: "Nickname" },
              { name: "account_id_type", label: "Account type", type: "select", required: true, options: ID_TYPES.map((t) => ({ value: t, label: t })) },
              { name: "account_number", label: "Account number / IBAN", required: true },
              { name: "bank_name", label: "Bank name" },
              { name: "agent_bic", label: "Bank BIC" },
              { name: "agent_clearing_id", label: "Bank clearing / sort code" },
              { name: "country", label: "Country (ISO 3166 alpha-2)", required: true, defaultValue: "KE" },
              { name: "currency", label: "Currency" },
            ]}
            run={async (v, key) => unwrap(await api.POST("/v1/business/payees", {
              params: { header: idem(key) },
              body: { name: v.name, nickname: opt(v.nickname), account_id_type: v.account_id_type as Payee["account_id_type"],
                account_number: v.account_number, bank_name: opt(v.bank_name), agent_bic: opt(v.agent_bic),
                agent_clearing_id: opt(v.agent_clearing_id), country: v.country.toUpperCase(), currency: opt(v.currency)?.toUpperCase() },
            }))} />
        ) : undefined} />
      <Card flush>
        <Async q={payees} empty={<div className="p-4"><Empty title="No beneficiaries yet" /></div>}>
          {(rows) => (
            <Table rows={rows} rowKey={(p) => p.id} caption="Beneficiaries" columns={[
              { header: "Name", cell: (p) => <>{p.name}{p.nickname && <span className="text-muted"> · {p.nickname}</span>}</> },
              { header: "Account", cell: (p) => <span className="font-mono text-xs">{p.account_number}</span> },
              { header: "Bank", cell: (p) => [p.bank_name, p.agent_bic].filter(Boolean).join(" · ") || "—" },
              { header: "Country / currency", cell: (p) => `${p.country}${p.currency ? ` · ${p.currency}` : ""}` },
              { header: "Status", cell: (p) => <Badge value={p.status} /> },
              { header: "", align: "right", cell: (p) => perms.canMake && (
                <div className="flex justify-end gap-2">
                  {perms.canInitiate && <LinkButton size="sm" variant="secondary" href={`/payments/new?payee=${p.id}`}>Pay</LinkButton>}
                  <ActionButton label="Remove" variant="danger" title={`Remove ${p.name}?`} confirmLabel="Remove"
                    run={async (_v, key) => {
                      const r = await api.DELETE("/v1/business/payees/{payee_id}", { params: { path: { payee_id: p.id }, header: idem(key) } });
                      return unwrap(r);
                    }} />
                </div>
              ) },
            ]} />
          )}
        </Async>
      </Card>
    </>
  );
}
