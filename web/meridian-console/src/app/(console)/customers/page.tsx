"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Button, Card, ErrorBanner, Input, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { EntityForm } from "@/components/entity-form";
import { customerFormSections } from "@/lib/entities/customer";
import type { Customer, Page } from "@/lib/types";

function Customers() {
  const router = useRouter();
  const params = useSearchParams();
  const qc = useQueryClient();
  const [q, setQ] = useState("");
  const [term, setTerm] = useState("");
  const [open, setOpen] = useState(params.get("new") === "1");

  const list = useQuery({
    queryKey: ["customers", term],
    queryFn: () => term
      ? call<Page<Customer>>("GET", `/v1/customers/search?q=${encodeURIComponent(term)}`)
      : call<Page<Customer>>("GET", "/v1/customers?limit=50"),
  });
  const create = useMutation({
    mutationFn: (body: Record<string, unknown>) => {
      // Derive display_name when the operator left it blank.
      if (!body.display_name) {
        body.display_name = body.legal_name
          ?? ([body.first_name, body.last_name].filter(Boolean).join(" ") || undefined);
      }
      return call<Customer>("POST", "/v1/customers", body);
    },
    onSuccess: (c) => { qc.invalidateQueries({ queryKey: ["customers"] }); router.push(`/customers/${c.id}`); },
  });

  return (
    <>
      <PageHeader title="Customers" subtitle="Individuals and businesses — single customer view (CIF)"
        actions={<Button onClick={() => setOpen(!open)}>{open ? "Cancel" : "New customer"}</Button>} />
      {open && (
        <div className="mb-8">
          <EntityForm sections={customerFormSections} submitLabel="Create customer"
            initial={{ customer_type: "INDIVIDUAL" }} pending={create.isPending} error={create.error}
            onSubmit={(body) => create.mutate(body)} />
        </div>
      )}
      <Card>
        <form className="mb-4 flex gap-2" onSubmit={(e) => { e.preventDefault(); setTerm(q); }}>
          <Input placeholder="Search by name, number, email, phone or identifier" value={q} onChange={(e) => setQ(e.target.value)} className="flex-1" />
          <Button type="submit" variant="secondary">Search</Button>
        </form>
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={(list.data?.data ?? []) as Record<string, unknown>[]} onRow={(r) => router.push(`/customers/${r.id}`)}
            empty={term ? "No customers match." : "No customers yet."}
            columns={[
              { key: "customer_number", header: "Number", render: (r) => <Mono>{String(r.customer_number ?? "—")}</Mono> },
              { key: "name", header: "Name", render: (r) => <span className="font-medium">
                {String(r.legal_name ?? ([r.first_name, r.last_name].filter(Boolean).join(" ") || "—"))}</span> },
              { key: "customer_type", header: "Type" },
              { key: "residence_country", header: "Residence" },
              { key: "kyc_tier", header: "KYC tier" },
              { key: "risk_level", header: "Risk", render: (r) => r.risk_level ? <Badge value={String(r.risk_level)} /> : <span className="text-muted">—</span> },
              { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
            ]} />
        )}
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><Customers /></Suspense>;
}
