"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Button, Card, DateTime, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import type { Page, Product, ProductVersion } from "@/lib/types";

/** Version actions by status: submit (maker) -> approve (checker, four-eyes) -> publish. */
const NEXT: Record<string, { action: string; label: string }[]> = {
  DRAFT: [{ action: "submit", label: "Submit for approval" }],
  SUBMITTED: [{ action: "approve", label: "Approve" }, { action: "reject", label: "Reject" }],
  PENDING_APPROVAL: [{ action: "approve", label: "Approve" }, { action: "reject", label: "Reject" }],
  APPROVED: [{ action: "publish", label: "Publish" }],
};

export default function Products() {
  const qc = useQueryClient();
  const [product, setProduct] = useState<Product | null>(null);
  const products = useQuery({ queryKey: ["products"], queryFn: () => call<Page<Product>>("GET", "/v1/products?limit=100") });
  const versions = useQuery({
    queryKey: ["versions", product?.id], enabled: !!product,
    queryFn: () => call<Page<ProductVersion>>("GET", `/v1/product-versions?product_id=${product!.id}&limit=50`),
  });
  const act = useMutation({
    mutationFn: ({ id, action }: { id: string; action: string }) => call("POST", `/v1/product-versions/${id}/${action}`, { reason: "console" }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["versions", product?.id] }); qc.invalidateQueries({ queryKey: ["products"] }); },
  });

  return (
    <>
      <PageHeader title="Product Studio" subtitle="Versioned products: draft → submit → approve (four-eyes) → publish" />
      <ErrorBanner error={products.error ?? act.error} />
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <Card title="Catalogue">
          {products.isLoading ? <Loading /> : (
            <Table rows={(products.data?.data ?? []) as Record<string, unknown>[]} onRow={(r) => setProduct(r as Product)}
              empty="No products yet."
              columns={[
                { key: "code", header: "Code", render: (r) => <span className="font-medium">{String(r.code)}</span> },
                { key: "name", header: "Name" },
                { key: "product_family", header: "Family" },
                { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
              ]} />
          )}
        </Card>
        <Card title={product ? `Versions · ${product.code}` : "Versions"}>
          {!product ? <p className="text-sm text-muted">Select a product.</p> : versions.isLoading ? <Loading /> : (
            <Table rows={(versions.data?.data ?? []) as Record<string, unknown>[]} empty="No versions."
              columns={[
                { key: "version_no", header: "Version", render: (r) => <span className="num">v{String(r.version_no)}</span> },
                { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
                { key: "effective_from", header: "Effective", render: (r) => <DateTime value={r.effective_from as string} /> },
                { key: "checksum", header: "Checksum", render: (r) => <Mono>{String(r.checksum ?? "—").slice(0, 12)}</Mono> },
                { key: "actions", header: "", render: (r) => (
                  <div className="flex justify-end gap-2">
                    {(NEXT[String(r.status)] ?? []).map((a) => (
                      <Button key={a.action} variant={a.action === "reject" ? "secondary" : "primary"} disabled={act.isPending}
                        onClick={(e) => { e.stopPropagation(); act.mutate({ id: String(r.id), action: a.action }); }}>{a.label}</Button>
                    ))}
                  </div>) },
              ]} />
          )}
        </Card>
      </div>
    </>
  );
}
