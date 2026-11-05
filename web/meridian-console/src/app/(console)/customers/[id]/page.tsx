"use client";

import { useParams } from "next/navigation";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Button, Card, ErrorBanner, Field, Input, Loading, Mono, PageHeader, Select, Table } from "@/components/ui";
import { DetailSections } from "@/components/detail";
import { customerDetailSections } from "@/lib/entities/customer";
import type { Customer } from "@/lib/types";

const TIERS = ["TIER_0", "TIER_1", "TIER_2", "TIER_3"];

/** Reason-based lifecycle transitions available per status (the service enforces the real state machine). */
const ACTIONS: Record<string, { action: string; label: string; danger?: boolean }[]> = {
  PROSPECT: [{ action: "activate", label: "Activate" }],
  PENDING_KYC: [{ action: "activate", label: "Activate" }],
  ACTIVE: [{ action: "suspend", label: "Suspend", danger: true }, { action: "close", label: "Close", danger: true }],
  DORMANT: [{ action: "reactivate", label: "Reactivate" }, { action: "close", label: "Close", danger: true }],
  SUSPENDED: [{ action: "reactivate", label: "Reactivate" }, { action: "close", label: "Close", danger: true }],
};

function rows(v: unknown): Record<string, unknown>[] {
  if (Array.isArray(v)) return v as Record<string, unknown>[];
  if (v && typeof v === "object" && Array.isArray((v as { data?: unknown }).data)) return (v as { data: Record<string, unknown>[] }).data;
  return [];
}

type TabKey = "identifiers" | "addresses" | "contacts" | "relationships" | "segments" | "devices" | "consents" | "tiers";
const TABS: { key: TabKey; label: string; path: (id: string) => string }[] = [
  { key: "identifiers", label: "Identifiers", path: (id) => `/v1/customers/${id}/identifiers` },
  { key: "addresses", label: "Addresses", path: (id) => `/v1/customers/${id}/addresses` },
  { key: "contacts", label: "Contacts", path: (id) => `/v1/customers/${id}/contacts` },
  { key: "relationships", label: "Relationships", path: (id) => `/v1/customers/${id}/relationships` },
  { key: "segments", label: "Segments", path: (id) => `/v1/customers/${id}/segments` },
  { key: "devices", label: "Devices", path: (id) => `/v1/customers/${id}/devices` },
  { key: "consents", label: "Consents", path: (id) => `/v1/consents?customer_id=${id}` },
  { key: "tiers", label: "Tier history", path: (id) => `/v1/customers/${id}/tier-history` },
];

const COLUMNS: Record<TabKey, { key: string; header: string; render?: (r: Record<string, unknown>) => React.ReactNode; align?: "right" }[]> = {
  identifiers: [
    { key: "identifier_type", header: "Type" }, { key: "identifier_value", header: "Value", render: (r) => <Mono>{String(r.identifier_value ?? r.masked_value ?? "••••")}</Mono> },
    { key: "issuing_country", header: "Country" }, { key: "expiry_date", header: "Expires" },
    { key: "is_primary", header: "Primary", render: (r) => r.is_primary ? "✓" : "" },
    { key: "verified", header: "Verified", render: (r) => <Badge value={r.verified ? "VERIFIED" : "UNVERIFIED"} tone={r.verified ? "good" : "warn"} /> },
  ],
  addresses: [
    { key: "address_type", header: "Type" }, { key: "line1", header: "Address", render: (r) => [r.line1, r.line2].filter(Boolean).join(", ") },
    { key: "city", header: "City" }, { key: "state", header: "State" }, { key: "country", header: "Country" },
    { key: "is_primary", header: "Primary", render: (r) => r.is_primary ? "✓" : "" },
  ],
  contacts: [
    { key: "contact_type", header: "Type" }, { key: "value", header: "Value", render: (r) => String(r.value ?? r.masked_value ?? r.email ?? r.phone ?? "—") },
    { key: "label", header: "Label" }, { key: "is_primary", header: "Primary", render: (r) => r.is_primary ? "✓" : "" },
    { key: "is_verified", header: "Verified", render: (r) => <Badge value={r.is_verified ? "VERIFIED" : "UNVERIFIED"} tone={r.is_verified ? "good" : "warn"} /> },
  ],
  relationships: [
    { key: "relationship_type", header: "Type" }, { key: "related_customer_id", header: "Related party", render: (r) => <Mono>{String(r.related_customer_id ?? "—")}</Mono> },
    { key: "ownership_percentage", header: "Ownership %", align: "right" }, { key: "position_title", header: "Position" },
    { key: "signing_authority", header: "Signing" },
  ],
  segments: [
    { key: "name", header: "Segment", render: (r) => String(r.name ?? r.customer_segment_id ?? "—") }, { key: "assigned_at", header: "Assigned" },
    { key: "is_automatic", header: "Automatic", render: (r) => r.is_automatic ? "✓" : "" },
  ],
  devices: [
    { key: "platform", header: "Platform" }, { key: "model", header: "Model", render: (r) => String(r.model ?? r.label ?? "—") },
    { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> }, { key: "last_seen_at", header: "Last seen" },
  ],
  consents: [
    { key: "purpose", header: "Purpose" }, { key: "recipient", header: "Recipient" },
    { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> }, { key: "expires_at", header: "Expires" },
  ],
  tiers: [
    { key: "from_tier", header: "From" }, { key: "to_tier", header: "To" }, { key: "reason", header: "Reason" },
    { key: "changed_at", header: "Changed" }, { key: "changed_by", header: "By" },
  ],
};

export default function CustomerDetail() {
  const { id } = useParams<{ id: string }>();
  const qc = useQueryClient();
  const [reason, setReason] = useState("");
  const [toTier, setToTier] = useState("");
  const [tab, setTab] = useState<TabKey>("identifiers");

  const customer = useQuery({ queryKey: ["customer", id], queryFn: () => call<Customer>("GET", `/v1/customers/${id}`) });
  const tabQuery = useQuery({
    queryKey: ["customer-tab", id, tab],
    queryFn: () => call<unknown>("GET", TABS.find((t) => t.key === tab)!.path(id)),
    retry: false,
  });
  const invalidate = () => { qc.invalidateQueries({ queryKey: ["customer", id] }); };
  const act = useMutation({
    mutationFn: (action: string) => call("POST", `/v1/customers/${id}/${action}`, { reason: reason || undefined }),
    onSuccess: invalidate,
  });
  const changeTier = useMutation({
    mutationFn: () => call("POST", `/v1/customers/${id}/change-tier`, { to_tier: toTier, reason: reason || "Tier change" }),
    onSuccess: invalidate,
  });

  const c = customer.data as Record<string, unknown> | undefined;
  if (customer.isLoading) return <Loading />;
  const name = c ? (c.legal_name ?? [c.first_name, c.last_name].filter(Boolean).join(" ")) : "Customer";
  const status = String(c?.status ?? "");

  return (
    <>
      <PageHeader title={String(name || "Customer")}
        subtitle={c && <><Mono>{String(c.customer_number ?? c.id)}</Mono> · {String(c.customer_type)} · KYC {String(c.kyc_tier ?? "—")} · <Badge value={status} /></>} />
      <ErrorBanner error={customer.error ?? act.error ?? changeTier.error} />

      <Card title="Lifecycle" className="mb-6">
        <div className="flex flex-wrap items-end gap-3">
          <Field label="Reason"><Input placeholder="Reason for the change" value={reason} onChange={(e) => setReason(e.target.value)} className="min-w-64" /></Field>
          {(ACTIONS[status] ?? []).map((a) => (
            <Button key={a.action} variant={a.danger ? "danger" : "primary"} disabled={act.isPending}
              onClick={() => act.mutate(a.action)}>{a.label}</Button>
          ))}
          <div className="flex items-end gap-2 border-l border-border pl-3">
            <Field label="Change KYC tier"><Select options={["", ...TIERS]} value={toTier} onChange={(e) => setToTier(e.target.value)} /></Field>
            <Button variant="secondary" disabled={!toTier || changeTier.isPending} onClick={() => changeTier.mutate()}>Apply tier</Button>
          </div>
        </div>
      </Card>

      {c && <DetailSections sections={customerDetailSections(c)} />}

      <Card className="mt-6">
        <div className="mb-4 flex flex-wrap gap-1 border-b border-border">
          {TABS.map((t) => (
            <button key={t.key} onClick={() => setTab(t.key)}
              className={"rounded-t-md px-3 py-2 text-sm " + (tab === t.key ? "border-b-2 border-accent font-medium" : "text-muted hover:text-accent")}>
              {t.label}
            </button>
          ))}
        </div>
        {tabQuery.isLoading ? <Loading /> : (
          <Table rows={rows(tabQuery.data)} columns={COLUMNS[tab]} empty={`No ${tab}.`} />
        )}
      </Card>
    </>
  );
}
