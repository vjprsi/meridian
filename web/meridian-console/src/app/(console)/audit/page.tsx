"use client";

import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Button, Card, DateTime, ErrorBanner, Input, Loading, Mono, PageHeader, Table } from "@/components/ui";
import type { AuditEvent, Page } from "@/lib/types";

export default function Audit() {
  const [actor, setActor] = useState("");
  const [filter, setFilter] = useState("");
  const events = useQuery({
    queryKey: ["audit", filter],
    queryFn: () => call<Page<AuditEvent>>("GET", `/v1/audit-events?limit=100${filter ? `&actor_id=${encodeURIComponent(filter)}` : ""}`),
  });
  const verify = useMutation({ mutationFn: () => call<Record<string, unknown>>("POST", "/v1/audit-events/verify-chain", {}) });

  return (
    <>
      <PageHeader title="Audit log" subtitle="Append-only, hash-chained record of every action"
        actions={<Button variant="secondary" onClick={() => verify.mutate()} disabled={verify.isPending}>Verify hash chain</Button>} />
      <ErrorBanner error={events.error ?? verify.error} />
      {verify.data && (
        <p className={`mb-4 rounded-[var(--radius)] px-4 py-3 text-sm ${verify.data.valid ?? verify.data.passed ? "bg-good-soft text-good" : "bg-bad-soft text-bad"}`}>
          Chain {(verify.data.valid ?? verify.data.passed) ? "intact" : "BROKEN"} · {JSON.stringify(verify.data)}
        </p>
      )}
      <Card>
        <form className="mb-4 flex gap-2" onSubmit={(e) => { e.preventDefault(); setFilter(actor); }}>
          <Input placeholder="Filter by actor" value={actor} onChange={(e) => setActor(e.target.value)} className="flex-1" />
          <Button type="submit" variant="secondary">Filter</Button>
        </form>
        {events.isLoading ? <Loading /> : (
          <Table rows={(events.data?.data ?? []) as Record<string, unknown>[]} empty="No audit events."
            columns={[
              { key: "occurred_at", header: "When", render: (r) => <DateTime value={String(r.occurred_at ?? r.created_at)} /> },
              { key: "actor_id", header: "Actor" },
              { key: "action", header: "Action" },
              { key: "resource_type", header: "Resource" },
              { key: "outcome", header: "Outcome", render: (r) => <Badge value={String(r.outcome ?? "SUCCESS")} /> },
              { key: "hash", header: "Hash", render: (r) => <Mono>{String(r.hash ?? "").slice(0, 10)}</Mono> },
            ]} />
        )}
      </Card>
    </>
  );
}
