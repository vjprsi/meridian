"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Button, Card, ErrorBanner, Field, Input, Loading, Mono, PageHeader, Select, Table } from "@/components/ui";
import type { Customer, Page } from "@/lib/types";

function Customers() {
  const router = useRouter();
  const params = useSearchParams();
  const qc = useQueryClient();
  const [q, setQ] = useState("");
  const [term, setTerm] = useState("");
  const [open, setOpen] = useState(params.get("new") === "1");
  const [form, setForm] = useState({ customer_type: "INDIVIDUAL", first_name: "", last_name: "", legal_name: "",
    date_of_birth: "", nationality: "", residence_country: "", legal_entity_id: "" });

  const list = useQuery({
    queryKey: ["customers", term],
    queryFn: () => term
      ? call<Page<Customer>>("GET", `/v1/customers/search?q=${encodeURIComponent(term)}`)
      : call<Page<Customer>>("GET", "/v1/customers?limit=50"),
  });
  const create = useMutation({
    mutationFn: () => {
      const body: Record<string, unknown> = Object.fromEntries(Object.entries(form).filter(([, v]) => v !== ""));
      body.display_name = form.customer_type === "INDIVIDUAL"
        ? [form.first_name, form.last_name].filter(Boolean).join(" ") : form.legal_name;
      return call<Customer>("POST", "/v1/customers", body);
    },
    onSuccess: (c) => { qc.invalidateQueries({ queryKey: ["customers"] }); router.push(`/customers/${c.id}`); },
  });
  const individual = form.customer_type === "INDIVIDUAL";

  return (
    <>
      <PageHeader title="Customers" subtitle="Individuals and businesses"
        actions={<Button onClick={() => setOpen(!open)}>{open ? "Cancel" : "New customer"}</Button>} />
      {open && (
        <Card title="New customer" className="mb-6">
          <ErrorBanner error={create.error} />
          <form className="grid grid-cols-1 gap-4 md:grid-cols-3" onSubmit={(e) => { e.preventDefault(); create.mutate(); }}>
            <Field label="Type"><Select options={["INDIVIDUAL", "SOLE_TRADER", "BUSINESS"]} value={form.customer_type}
              onChange={(e) => setForm({ ...form, customer_type: e.target.value })} /></Field>
            {individual ? (
              <>
                <Field label="First name"><Input required value={form.first_name} onChange={(e) => setForm({ ...form, first_name: e.target.value })} /></Field>
                <Field label="Last name"><Input required value={form.last_name} onChange={(e) => setForm({ ...form, last_name: e.target.value })} /></Field>
                <Field label="Date of birth"><Input type="date" value={form.date_of_birth} onChange={(e) => setForm({ ...form, date_of_birth: e.target.value })} /></Field>
                <Field label="Nationality (ISO)"><Input maxLength={2} value={form.nationality} onChange={(e) => setForm({ ...form, nationality: e.target.value.toUpperCase() })} /></Field>
              </>
            ) : (
              <Field label="Legal name"><Input required value={form.legal_name} onChange={(e) => setForm({ ...form, legal_name: e.target.value })} /></Field>
            )}
            <Field label="Residence country (ISO)"><Input maxLength={2} value={form.residence_country}
              onChange={(e) => setForm({ ...form, residence_country: e.target.value.toUpperCase() })} /></Field>
            <Field label="Legal entity ID"><Input value={form.legal_entity_id} onChange={(e) => setForm({ ...form, legal_entity_id: e.target.value })} /></Field>
            <div><Button type="submit" disabled={create.isPending}>Create customer</Button></div>
          </form>
        </Card>
      )}
      <Card>
        <form className="mb-4 flex gap-2" onSubmit={(e) => { e.preventDefault(); setTerm(q); }}>
          <Input placeholder="Search by name, number, email or phone" value={q} onChange={(e) => setQ(e.target.value)} className="flex-1" />
          <Button type="submit" variant="secondary">Search</Button>
        </form>
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={(list.data?.data ?? []) as Record<string, unknown>[]} onRow={(r) => router.push(`/customers/${r.id}`)}
            empty={term ? "No customers match." : "No customers yet."}
            columns={[
              { key: "customer_number", header: "Number", render: (r) => <Mono>{String(r.customer_number ?? "—")}</Mono> },
              { key: "name", header: "Name", render: (r) => <span className="font-medium">
                {String(r.legal_name ?? [r.first_name, r.last_name].filter(Boolean).join(" ") ?? "—")}</span> },
              { key: "customer_type", header: "Type" },
              { key: "kyc_tier", header: "KYC tier" },
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
