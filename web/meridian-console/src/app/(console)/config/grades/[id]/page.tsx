"use client";

import { Suspense } from "react";
import Link from "next/link";
import { Badge, Mono } from "@/components/ui";
import { str, type Row } from "@/components/ops";
import { HierarchyDetail } from "../../_hierarchy";

// WE-HIER-1 (G-06): grade detail — lifecycle (+ delete while draft) + its services.
export default function Page() {
  return (
    <Suspense>
      <HierarchyDetail
        entity="Grade"
        navKey="grades"
        basePath="/v1/grades"
        allowDelete
        detailItems={(r: Row) => [
          ["Code", <Mono key="c">{str(r.code)}</Mono>],
          ["Name", r.name],
          ["Category", r.domain_category_id
            ? <Link key="k" className="text-accent hover:underline" href={`/config/categories/${r.domain_category_id}`}><Mono>{String(r.domain_category_id)}</Mono></Link>
            : null],
          ["Priority", r.priority],
          ["Unregistered", r.is_unregistered],
          ["Created", r.created_at],
          ["Status", <Badge key="s" value={str(r.status)} />],
        ]}
        childTabs={[{
          id: "services",
          label: "Services",
          path: (id) => `/v1/grades/${id}/services`,
          empty: "No services enabled for this grade.",
          columns: [
            { key: "service_code", header: "Service", render: (r) => <Mono>{str(r.service_code ?? r.service)}</Mono> },
            { key: "enabled", header: "Enabled", render: (r) => <Badge value={r.enabled ? "ENABLED" : "DISABLED"} /> },
            { key: "priority", header: "Priority", align: "right", render: (r) => str(r.priority) },
          ],
        }]} />
    </Suspense>
  );
}
