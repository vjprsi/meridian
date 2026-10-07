"use client";

import { useQuery } from "@tanstack/react-query";
import { api, unwrap } from "@/api/client";
import { useT } from "@/lib/i18n";
import { Async, Badge, Card, DateTime, Empty, PageHeader, Table } from "@/components/ui";

/** Security log: the OWNER sees the whole merchant's activity, others their own (the server decides). */
export default function ActivityPage() {
  const t = useT();
  const log = useQuery({
    queryKey: ["activity"],
    queryFn: async () => unwrap(await api.GET("/v1/merchant/activity", { params: { query: { limit: 100 } } })).data,
  });
  return (
    <>
      <PageHeader title={t("activity")} />
      <Card flush>
        <Async q={log} empty={<div className="p-4"><Empty title={t("none")} /></div>}>
          {(rows) => (
            <Table rows={rows} rowKey={(r) => r.id} columns={[
              { header: t("when"), cell: (r) => <DateTime value={r.created_at} /> },
              { header: t("action"), cell: (r) => r.action },
              { header: t("outcome"), cell: (r) => <Badge value={r.outcome} /> },
              { header: "", cell: (r) => <span className="font-mono text-xs">{r.error_code ?? ""}</span> },
            ]} />
          )}
        </Async>
      </Card>
    </>
  );
}
