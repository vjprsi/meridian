"use client";

import { Suspense } from "react";
import Link from "next/link";
import { Badge, Mono } from "@/components/ui";
import { str, type Row } from "@/components/ops";
import { HierarchyDetail } from "../../_hierarchy";

// WE-HIER-1 (G-06): domain category detail — lifecycle + its service preferences.
export default function Page() {
  return (
    <Suspense>
      <HierarchyDetail
        entity="Category"
        navKey="domain-categories"
        basePath="/v1/domain-categories"
        detailItems={(r: Row) => [
          ["Code", <Mono key="c">{str(r.code)}</Mono>],
          ["Name", r.name],
          ["Domain", r.distribution_domain_id
            ? <Link key="d" className="text-accent hover:underline" href={`/config/domains/${r.distribution_domain_id}`}><Mono>{String(r.distribution_domain_id)}</Mono></Link>
            : null],
          ["Level", r.level],
          ["Owner", r.is_owner],
          ["Priority", r.priority],
          ["Created", r.created_at],
          ["Status", <Badge key="s" value={str(r.status)} />],
        ]}
        childTabs={[{
          id: "preferences",
          label: "Service preferences",
          path: (id) => `/v1/domain-categories/${id}/service-preferences`,
          empty: "No service preferences set.",
          columns: [
            { key: "service_code", header: "Service", render: (r) => <Mono>{str(r.service_code ?? r.service)}</Mono> },
            { key: "enabled", header: "Enabled", render: (r) => <Badge value={r.enabled ? "ENABLED" : "DISABLED"} /> },
            { key: "priority", header: "Priority", align: "right", render: (r) => str(r.priority) },
          ],
        }]} />
    </Suspense>
  );
}
