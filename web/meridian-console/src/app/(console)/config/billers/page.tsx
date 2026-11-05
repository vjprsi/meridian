"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Button, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { EntityForm } from "@/components/entity-form";
import { Filters, Pager, str, usePaged } from "@/components/ops";
import { billerFormSections } from "@/lib/entities/config";

// MovilRed Admin: Biller Management.
function Billers() {
  const router = useRouter();
  const qc = useQueryClient();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [open, setOpen] = useState(false);
  const list = usePaged("billers", "/v1/billers", filters);

  const create = useMutation({
    mutationFn: (body: Record<string, unknown>) => call("POST", "/v1/billers", body),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["billers"] }); setOpen(false); },
  });

  return (
    <>
      <PageHeader title="Billers" subtitle="Bill-payment and recharge billers"
        actions={<Button onClick={() => setOpen(!open)}>{open ? "Cancel" : "New biller"}</Button>} />
      {open && (
        <div className="mb-8">
          <EntityForm sections={billerFormSections} submitLabel="Create biller"
            initial={{ integration_type: "API", account_reference_checksum: "NONE", settlement_frequency: "DAILY",
              fee_bearer: "CUSTOMER", supports_inquiry: "true" }}
            pending={create.isPending} error={create.error}
            onSubmit={(body) => create.mutate(body)} />
        </div>
      )}
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "status", label: "Status" },
          { name: "category", label: "Category" },
          { name: "code", label: "Code" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/config/billers/${r.id}`)}
            empty="No billers."
            columns={[
              { key: "code", header: "Code", render: (r) => <Mono>{str(r.code)}</Mono> },
              { key: "name", header: "Name", render: (r) => <span className="font-medium">{str(r.name)}</span> },
              { key: "category", header: "Category" },
              { key: "status", header: "Status", render: (r) => <Badge value={str(r.status)} /> },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><Billers /></Suspense>;
}
