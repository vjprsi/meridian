"use client";

import { useParams } from "next/navigation";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Button, Card, DateTime, ErrorBanner, Input, Loading, Mono, PageHeader, Table } from "@/components/ui";
import type { Customer } from "@/lib/types";

type View = Record<string, unknown> & { customer?: Customer };

/** Lifecycle actions available per status (the service enforces the state machine; this only guides the UI). */
const ACTIONS: Record<string, { action: string; label: string; danger?: boolean }[]> = {
  PROSPECT: [{ action: "activate", label: "Activate" }],
  PENDING_KYC: [{ action: "activate", label: "Activate" }],
  ACTIVE: [{ action: "suspend", label: "Suspend", danger: true }, { action: "close", label: "Close", danger: true }],
  SUSPENDED: [{ action: "reactivate", label: "Reactivate" }, { action: "close", label: "Close", danger: true }],
};

function list(v: unknown): Record<string, unknown>[] {
  if (Array.isArray(v)) return v as Record<string, unknown>[];
  if (v && typeof v === "object" && Array.isArray((v as { data?: unknown }).data)) return (v as { data: Record<string, unknown>[] }).data;
  return [];
}

export default function CustomerDetail() {
  const { id } = useParams<{ id: string }>();
  const qc = useQueryClient();
  const [reason, setReason] = useState("");
  const customer = useQuery({ queryKey: ["customer", id], queryFn: () => call<Customer>("GET", `/v1/customers/${id}`) });
  const view = useQuery({ queryKey: ["customer-view", id], queryFn: () => call<View>("GET", `/v1/customers/${id}/view`), retry: false });
  const act = useMutation({
    mutationFn: (action: string) => call("POST", `/v1/customers/${id}/${action}`, { reason: reason || undefined }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["customer", id] }); qc.invalidateQueries({ queryKey: ["customer-view", id] }); },
  });

  const c = customer.data;
  if (customer.isLoading) return <Loading />;
  const name = c ? (c.legal_name ?? [c.first_name, c.last_name].filter(Boolean).join(" ")) : "Customer";
  const v = view.data ?? {};
  return (
    <>
      <PageHeader title={name ?? "Customer"}
        subtitle={c && <><Mono>{c.customer_number ?? c.id}</Mono> · {c.customer_type} · KYC {c.kyc_tier ?? "—"} · <Badge value={c.status} /></>} />
      <ErrorBanner error={customer.error ?? act.error} />
      {c && (ACTIONS[c.status ?? ""] ?? []).length > 0 && (
        <Card title="Lifecycle" className="mb-6">
          <div className="flex flex-wrap items-center gap-3">
            <Input placeholder="Reason" value={reason} onChange={(e) => setReason(e.target.value)} className="min-w-64" />
            {(ACTIONS[c.status ?? ""] ?? []).map((a) => (
              <Button key={a.action} variant={a.danger ? "danger" : "primary"} disabled={act.isPending}
                onClick={() => act.mutate(a.action)}>{a.label}</Button>
            ))}
          </div>
        </Card>
      )}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <Card title="Profile">
          <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
            {c && Object.entries({
              "Date of birth": c.date_of_birth, Nationality: c.nationality, Residence: c.residence_country,
              Occupation: c.occupation, "Risk level": c.risk_level, PEP: c.is_pep ? "yes" : "no",
              "Onboarding channel": c.onboarding_channel, Created: c.created_at,
            }).map(([k, val]) => (
              <div key={k} className="contents">
                <dt className="text-muted">{k}</dt>
                <dd>{k === "Created" ? <DateTime value={val as string} /> : String(val ?? "—")}</dd>
              </div>
            ))}
          </dl>
        </Card>
        <Card title="Relationships">
          <Table rows={list(v.relationships)} empty="No relationships."
            columns={[{ key: "relationship_type", header: "Type" }, { key: "ownership_percent", header: "Ownership %", align: "right" },
              { key: "status", header: "Status", render: (r) => <Badge value={String(r.status ?? "ACTIVE")} /> }]} />
        </Card>
        <Card title="Addresses">
          <Table rows={list(v.addresses)} empty="No addresses."
            columns={[{ key: "address_type", header: "Type" }, { key: "line1", header: "Address" }, { key: "city", header: "City" },
              { key: "country", header: "Country" }]} />
        </Card>
        <Card title="Contacts">
          <Table rows={list(v.contacts)} empty="No contacts."
            columns={[{ key: "contact_type", header: "Type" }, { key: "value", header: "Value" },
              { key: "is_verified", header: "Verified", render: (r) => <Badge value={r.is_verified ? "VERIFIED" : "UNVERIFIED"} tone={r.is_verified ? "good" : "warn"} /> }]} />
        </Card>
      </div>
    </>
  );
}
