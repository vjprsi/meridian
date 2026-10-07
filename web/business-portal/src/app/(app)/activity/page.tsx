"use client";

import { useInfiniteQuery } from "@tanstack/react-query";
import { usePermissions, useUsers } from "@/api/business";
import { api, unwrap } from "@/api/client";
import { Async, Badge, Button, Card, DateTime, Empty, PageHeader, Table, shortId } from "@/components/ui";

export default function ActivityPage() {
  const perms = usePermissions();
  const users = useUsers(perms.loaded && perms.isAdmin);
  const q = useInfiniteQuery({
    queryKey: ["activity"],
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam }) => unwrap(await api.GET("/v1/business/activity", { params: { query: { limit: 100, cursor: pageParam } } })),
    getNextPageParam: (last) => (last.has_more ? last.next_cursor ?? undefined : undefined),
  });
  const rows = q.data?.pages.flatMap((p) => p.data);
  return (
    <>
      <PageHeader title="Security log"
        subtitle={perms.isAdmin ? "What users of the business did or were refused, newest first" : "What you did or were refused, newest first"} />
      <Card flush>
        <Async q={{ data: rows, error: q.error, isPending: q.isPending }} empty={<div className="p-4"><Empty title="No activity yet" /></div>}>
          {(list) => (
            <>
              <Table rows={list} rowKey={(a) => a.id} caption="Security log" columns={[
                { header: "When", cell: (a) => <DateTime value={a.created_at} /> },
                { header: "User", cell: (a) => users.data?.find((u) => u.id === a.business_user_id)?.display_name ?? a.created_by ?? shortId(a.business_user_id) },
                { header: "Action", cell: (a) => <span className="font-mono text-xs">{a.action}</span> },
                { header: "Object", cell: (a) => a.object_type ? `${a.object_type} ${shortId(a.object_id)}` : "—" },
                { header: "Outcome", cell: (a) => <Badge value={a.outcome} /> },
                { header: "Error", cell: (a) => a.error_code ? <span className="font-mono text-xs">{a.error_code}</span> : "—" },
                { header: "Auth age", cell: (a) => a.auth_age_seconds !== null && a.auth_age_seconds !== undefined ? `${a.auth_age_seconds}s` : "—" },
                { header: "IP", cell: (a) => <span className="text-xs">{a.ip_address ?? "—"}</span> },
              ]} />
              {q.hasNextPage && (
                <div className="p-3"><Button variant="secondary" onClick={() => q.fetchNextPage()} disabled={q.isFetchingNextPage}>Load more</Button></div>
              )}
            </>
          )}
        </Async>
      </Card>
    </>
  );
}
