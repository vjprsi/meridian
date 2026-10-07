"use client";

import Link from "next/link";
import { ErrorBanner, Loading, PageHeader } from "@/components/ui";
import { useCatalog } from "@/lib/spec";

export default function ReferenceIndex() {
  const catalog = useCatalog();
  return (
    <>
      <PageHeader title="API reference"
        subtitle={<>One OpenAPI 3.1 contract per domain, generated from the platform spec. All operations are served by the
          edge gateway under <code className="font-mono">/v1</code>; the bundled contract is at <code className="font-mono">/openapi.yaml</code> on the gateway.</>} />
      <ErrorBanner error={catalog.error} />
      {catalog.isLoading ? <Loading /> : (
        <div className="overflow-x-auto rounded-[var(--radius)] border border-border bg-surface">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border text-left text-xs uppercase tracking-wide text-muted">
                <th className="px-4 py-2 font-medium">Domain</th>
                <th className="px-4 py-2 font-medium">Scopes</th>
                <th className="px-4 py-2 text-right font-medium">Resources</th>
                <th className="px-4 py-2 text-right font-medium">Operations</th>
                <th className="px-4 py-2 font-medium">Services</th>
                <th className="px-4 py-2 font-medium">Contract</th>
              </tr>
            </thead>
            <tbody>
              {(catalog.data?.domains ?? []).map((d) => (
                <tr key={d.id} className="border-b border-border last:border-0 align-top">
                  <td className="px-4 py-3">
                    <Link href={`/reference/${d.id}`} className="font-medium text-accent hover:underline">{d.title}</Link>
                    <p className="mt-0.5 line-clamp-2 max-w-md text-xs text-muted">{d.description}</p>
                  </td>
                  <td className="px-4 py-3 font-mono text-xs">{d.scopes.join(" ")}</td>
                  <td className="num px-4 py-3 text-right">{d.tags}</td>
                  <td className="num px-4 py-3 text-right">{d.operations}</td>
                  <td className="px-4 py-3 text-xs text-muted">{d.services.map((s) => s.name).join(", ")}</td>
                  <td className="px-4 py-3 text-xs"><a className="text-accent hover:underline" href={`/specs/openapi/${d.id}.openapi.yaml`}>YAML</a></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
