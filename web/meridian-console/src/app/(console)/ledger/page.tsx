"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Button, Card, ErrorBanner, Field, Input, Loading, Mono, PageHeader, Table } from "@/components/ui";
import type { Ledger, Page } from "@/lib/types";

export default function LedgersPage() {
  const router = useRouter();
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ code: "", name: "", base_currency: "USD", legal_entity_id: "" });
  const ledgers = useQuery({ queryKey: ["ledgers"], queryFn: () => call<Page<Ledger>>("GET", "/v1/ledgers?limit=100") });
  const create = useMutation({
    mutationFn: () => call<Ledger>("POST", "/v1/ledgers", form),
    onSuccess: (l) => { qc.invalidateQueries({ queryKey: ["ledgers"] }); router.push(`/ledger/${l.id}`); },
  });

  return (
    <>
      <PageHeader title="Ledger explorer" subtitle="Books of account, their accounts and postings"
        actions={<Button onClick={() => setOpen(!open)}>{open ? "Cancel" : "New ledger"}</Button>} />
      {open && (
        <Card title="New ledger" className="mb-6">
          <ErrorBanner error={create.error} />
          <form className="grid grid-cols-1 gap-4 md:grid-cols-4" onSubmit={(e) => { e.preventDefault(); create.mutate(); }}>
            <Field label="Code"><Input required value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })} /></Field>
            <Field label="Name"><Input required value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></Field>
            <Field label="Base currency"><Input required maxLength={3} value={form.base_currency}
              onChange={(e) => setForm({ ...form, base_currency: e.target.value.toUpperCase() })} /></Field>
            <Field label="Legal entity ID"><Input required value={form.legal_entity_id}
              onChange={(e) => setForm({ ...form, legal_entity_id: e.target.value })} /></Field>
            <div><Button type="submit" disabled={create.isPending}>Create ledger</Button></div>
          </form>
        </Card>
      )}
      <Card>
        <ErrorBanner error={ledgers.error} />
        {ledgers.isLoading ? <Loading /> : (
          <Table rows={(ledgers.data?.data ?? []) as Record<string, unknown>[]} onRow={(r) => router.push(`/ledger/${r.id}`)}
            empty="No ledgers yet."
            columns={[
              { key: "code", header: "Code", render: (r) => <span className="font-medium">{String(r.code)}</span> },
              { key: "name", header: "Name" },
              { key: "base_currency", header: "Currency" },
              { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
              { key: "id", header: "ID", render: (r) => <Mono>{String(r.id)}</Mono> },
            ]} />
        )}
      </Card>
    </>
  );
}
