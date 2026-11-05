"use client";

import { Suspense } from "react";
import { Badge, Mono } from "@/components/ui";
import { str, type Row } from "@/components/ops";
import { HierarchyDetail } from "../../_hierarchy";

// WE-HIER-1 (G-06): distribution domain detail — lifecycle + its categories.
export default function Page() {
  return (
    <Suspense>
      <HierarchyDetail
        entity="Domain"
        navKey="distribution-domains"
        basePath="/v1/distribution-domains"
        detailItems={(r: Row) => [
          ["Code", <Mono key="c">{str(r.code)}</Mono>],
          ["Name", r.name],
          ["Priority", r.priority],
          ["Created", r.created_at],
          ["Updated", r.updated_at],
          ["Status", <Badge key="s" value={str(r.status)} />],
        ]}
        childTabs={[{
          id: "categories",
          label: "Categories",
          path: () => "/v1/domain-categories",
          filters: (id) => ({ distribution_domain_id: id }),
          empty: "No categories in this domain.",
          columns: [
            { key: "code", header: "Code", render: (r) => <Mono>{str(r.code)}</Mono> },
            { key: "name", header: "Name" },
            { key: "level", header: "Level", align: "right", render: (r) => str(r.level) },
            { key: "priority", header: "Priority", align: "right", render: (r) => str(r.priority) },
            { key: "status", header: "Status", render: (r) => <Badge value={str(r.status)} /> },
          ],
        }]} />
    </Suspense>
  );
}
