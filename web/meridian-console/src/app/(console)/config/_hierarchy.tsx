"use client";

import { useParams } from "next/navigation";
import { useState, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Card, Column, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Action, Details, Pager, Tabs, usePaged, type Row } from "@/components/ops";

/**
 * WE-HIER-1 (G-06): shared detail/action view for the distribution hierarchy entities (domain / category / grade),
 * which all follow the same maker-checker lifecycle: DRAFT -(submit)-> PENDING_APPROVAL -(approve)-> ACTIVE
 * -(retire)-> RETIRED; reject returns a pending entity to draft. Each entity supplies its own base path, the extra
 * key/value rows for its Details block, and its child collection tab (categories / service preferences / services).
 */
export interface ChildTab {
  id: string;
  label: string;
  path: (id: string) => string;
  /** Optional query filters (e.g. filter the standard collection by parent id) merged into the paged request. */
  filters?: (id: string) => Record<string, string>;
  columns: Column<Row>[];
  empty: string;
}

const SUBMITTABLE = ["DRAFT"];
const DECIDABLE = ["PENDING_APPROVAL"];
const RETIRABLE = ["ACTIVE"];

export function HierarchyDetail({ entity, navKey, basePath, detailItems, childTabs = [], allowDelete }: {
  entity: string;
  navKey: string;
  basePath: string;
  detailItems: (r: Row) => [string, ReactNode | unknown][];
  childTabs?: ChildTab[];
  allowDelete?: boolean;
}) {
  const { id } = useParams<{ id: string }>();
  const tabs = [{ id: "detail", label: "Detail" }, ...childTabs.map((t) => ({ id: t.id, label: t.label }))] as const;
  const [tab, setTab] = useState<string>("detail");
  const inv = [[navKey]];
  const q = useQuery({ queryKey: [navKey, id], queryFn: () => call<Row>("GET", `${basePath}/${id}`) });
  const activeChild = childTabs.find((t) => t.id === tab);
  const children = usePaged(navKey, activeChild ? activeChild.path(id) : basePath,
    activeChild?.filters ? activeChild.filters(id) : {},
    { enabled: !!activeChild, limit: 50 });

  if (q.isLoading) return <Loading />;
  const r = q.data;
  const status = String(r?.status ?? "");

  return (
    <>
      <PageHeader title={r ? `${entity}: ${String(r.name ?? r.code ?? id)}` : entity}
        subtitle={r && <><Mono>{String(r.code ?? "")}</Mono> · <Badge value={status} /></>}
        actions={r && <>
          {SUBMITTABLE.includes(status) && (
            <Action label="Submit for approval" variant="secondary" path={`${basePath}/${id}/submit`} invalidate={inv}
              title={`Submit ${entity.toLowerCase()} for approval`} />
          )}
          {DECIDABLE.includes(status) && (
            <Action label="Approve" path={`${basePath}/${id}/approve`} invalidate={inv}
              title={`Approve ${entity.toLowerCase()}`}
              description="Maker-checker: the checker must differ from the maker."
              fields={[{ name: "comment", label: "Comment", type: "textarea" }]} />
          )}
          {DECIDABLE.includes(status) && (
            <Action label="Reject" variant="danger" path={`${basePath}/${id}/reject`} invalidate={inv}
              fields={[{ name: "reason", label: "Reason", type: "textarea", required: true }]} />
          )}
          {RETIRABLE.includes(status) && (
            <Action label="Retire" variant="ghost" path={`${basePath}/${id}/retire`} invalidate={inv}
              title={`Retire ${entity.toLowerCase()}`}
              description="Retires the entity; it no longer applies to new assignments." confirmLabel="Retire" />
          )}
          {allowDelete && SUBMITTABLE.includes(status) && (
            <Action label="Delete" variant="danger" method="DELETE" path={`${basePath}/${id}`} invalidate={inv}
              title={`Delete ${entity.toLowerCase()}`} description="Only a draft can be deleted." confirmLabel="Delete" />
          )}
        </>} />

      <Card>
        <Tabs tabs={tabs} value={tab} onChange={setTab} />

        {tab === "detail" && r && <Details items={detailItems(r)} />}

        {activeChild && (
          <>
            {children.isLoading ? <Loading /> : (
              <Table rows={children.rows} columns={activeChild.columns} empty={activeChild.empty} />
            )}
            <Pager p={children} />
          </>
        )}
      </Card>
    </>
  );
}
