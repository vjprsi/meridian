"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { useSession } from "@/components/providers";
import { Badge, Button, Card, DateTime, ErrorBanner, Input, Loading, PageHeader, Table } from "@/components/ui";
import type { ApprovalRequest, Page } from "@/lib/types";

export default function Approvals() {
  const qc = useQueryClient();
  const { session } = useSession();
  const [selected, setSelected] = useState<ApprovalRequest | null>(null);
  const [comment, setComment] = useState("");
  const inbox = useQuery({
    queryKey: ["approvals"],
    queryFn: () => call<Page<ApprovalRequest>>("GET", "/v1/approval-requests?status=PENDING&limit=100"),
  });
  const decide = useMutation({
    mutationFn: ({ action }: { action: "approve" | "reject" | "request-changes" }) =>
      call("POST", `/v1/approval-requests/${selected!.id}/${action}`, { comment: comment || undefined, reason: comment || undefined }),
    onSuccess: () => { setSelected(null); setComment(""); qc.invalidateQueries({ queryKey: ["approvals"] }); },
  });

  return (
    <>
      <PageHeader title="Approvals inbox" subtitle="Maker-checker: you cannot approve a request you raised" />
      <ErrorBanner error={inbox.error ?? decide.error} />
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <Card title="Pending" className="lg:col-span-2">
          {inbox.isLoading ? <Loading /> : (
            <Table rows={(inbox.data?.data ?? []) as Record<string, unknown>[]} onRow={(r) => setSelected(r as ApprovalRequest)}
              empty="Nothing waiting for approval."
              columns={[
                { key: "resource_type", header: "What" },
                { key: "action", header: "Action" },
                { key: "maker", header: "Requested by" },
                { key: "title", header: "Title" },
                { key: "created_at", header: "Raised", render: (r) => <DateTime value={String(r.created_at)} /> },
                { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
              ]} />
          )}
        </Card>
        <Card title="Decision">
          {!selected ? <p className="text-sm text-muted">Select a request to review it.</p> : (
            <div className="space-y-4 text-sm">
              <div>
                <p className="font-medium">{String(selected.resource_type)} · {String(selected.action)}</p>
                <p className="text-muted">Raised by {String(selected.maker ?? "—")} · {String(selected.title ?? "")}</p>
              </div>
              <pre className="max-h-64 overflow-auto rounded bg-surface-2 p-3 text-xs">
                {JSON.stringify(selected.payload ?? {}, null, 2)}
              </pre>
              {selected.maker === session?.user && (
                <p className="rounded bg-warn-soft p-2 text-xs text-warn">You raised this request; another user must approve it.</p>
              )}
              <Input placeholder="Comment" value={comment} onChange={(e) => setComment(e.target.value)} className="w-full" />
              <div className="flex gap-2">
                <Button disabled={decide.isPending} onClick={() => decide.mutate({ action: "approve" })}>Approve</Button>
                <Button variant="danger" disabled={decide.isPending} onClick={() => decide.mutate({ action: "reject" })}>Reject</Button>
                <Button variant="secondary" disabled={decide.isPending} onClick={() => decide.mutate({ action: "request-changes" })}>Request changes</Button>
              </div>
            </div>
          )}
        </Card>
      </div>
    </>
  );
}
