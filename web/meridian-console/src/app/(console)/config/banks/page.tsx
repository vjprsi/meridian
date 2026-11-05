"use client";

import { Suspense, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Button, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { EntityForm } from "@/components/entity-form";
import { Filters, Pager, str, usePaged } from "@/components/ops";
import { bankFormSections } from "@/lib/entities/config";

// MovilRed Admin: Bank Management (partner / settlement banks).
function Banks() {
  const qc = useQueryClient();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [open, setOpen] = useState(false);
  const list = usePaged("reference-banks", "/v1/reference/banks", filters);

  const create = useMutation({
    mutationFn: (body: Record<string, unknown>) => call("POST", "/v1/reference/banks", body),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["reference-banks"] }); setOpen(false); },
  });

  return (
    <>
      <PageHeader title="Banks" subtitle="Partner and settlement bank registry"
        actions={<Button onClick={() => setOpen(!open)}>{open ? "Cancel" : "New bank"}</Button>} />
      {open && (
        <div className="mb-8">
          <EntityForm sections={bankFormSections} submitLabel="Create bank"
            initial={{ is_active: "true" }} pending={create.isPending} error={create.error}
            onSubmit={(body) => create.mutate(body)} />
        </div>
      )}
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "country", label: "Country", placeholder: "KE" },
          { name: "name", label: "Name" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows}
            empty="No banks."
            columns={[
              { key: "code", header: "Code", render: (r) => <Mono>{str(r.code)}</Mono> },
              { key: "name", header: "Name", render: (r) => <span className="font-medium">{str(r.name)}</span> },
              { key: "bic", header: "BIC / SWIFT", render: (r) => <Mono>{str(r.bic)}</Mono> },
              { key: "country", header: "Country" },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><Banks /></Suspense>;
}
