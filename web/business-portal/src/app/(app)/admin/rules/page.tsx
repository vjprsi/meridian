"use client";

import { useQuery } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { usePermissions, useUsers } from "@/api/business";
import { api, idem, unwrap } from "@/api/client";
import type { BusinessApprovalRule } from "@/api/types";
import { ActionButton } from "@/components/actions";
import { AdminOnly } from "@/components/admin-only";
import { useRun } from "@/components/step-up";
import {
  Async, Badge, Button, Card, Checkbox, Dialog, Empty, ErrorBanner, Field, Input, Money, PageHeader, Select, Table,
} from "@/components/ui";

type RuleBody = {
  name: string; applies_to: BusinessApprovalRule["applies_to"]; currency: string; min_amount: string; max_amount?: string;
  required_approvals: number; approval_order: BusinessApprovalRule["approval_order"]; approver_ids?: string[];
};

function RuleDialog({ rule, open, onClose }: { rule?: BusinessApprovalRule; open: boolean; onClose: () => void }) {
  const users = useUsers(open);
  const r = useRun();
  const [f, setF] = useState<RuleBody>(() => ({
    name: rule?.name ?? "", applies_to: rule?.applies_to ?? "ALL", currency: rule?.currency ?? "KES", min_amount: rule?.min_amount ?? "0",
    max_amount: rule?.max_amount ?? "", required_approvals: rule?.required_approvals ?? 1, approval_order: rule?.approval_order ?? "ANY_ORDER",
    approver_ids: (rule?.approver_ids as string[] | null | undefined) ?? [],
  }));
  const approvers = (users.data ?? []).filter((u) => (u.role === "APPROVER" || u.role === "ADMIN") && u.status === "ACTIVE");

  async function submit(e: FormEvent) {
    e.preventDefault();
    const body = { ...f, currency: f.currency.toUpperCase(), max_amount: f.max_amount || undefined,
      approver_ids: f.approver_ids && f.approver_ids.length > 0 ? f.approver_ids : undefined };
    const res = await r.run(async (key) => unwrap(rule
      ? await api.PUT("/v1/business/approval-rules/{rule_id}", { params: { path: { rule_id: rule.id }, header: idem(key) }, body })
      : await api.POST("/v1/business/approval-rules", { params: { header: idem(key) }, body })));
    if (res.ok) onClose();
  }

  const toggle = (id: string, on: boolean) => setF((s) => {
    const ids = (s.approver_ids ?? []).filter((x) => x !== id);
    return { ...s, approver_ids: on ? [...ids, id] : ids };
  });

  return (
    <Dialog open={open} onClose={onClose} title={rule ? `Replace rule "${rule.name}"` : "Add approval rule"} wide>
      <form onSubmit={submit} className="flex flex-col gap-3">
        {rule && <p className="text-muted">The current version is deactivated and kept for payments that used it.</p>}
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Name"><Input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} required /></Field>
          <Field label="Applies to">
            <Select value={f.applies_to} onChange={(e) => setF({ ...f, applies_to: e.target.value as RuleBody["applies_to"] })}
              options={[{ value: "ALL", label: "All payments" }, { value: "SINGLE", label: "Single payments" }, { value: "BULK", label: "Bulk & payroll" }]} />
          </Field>
          <Field label="Currency"><Input value={f.currency} onChange={(e) => setF({ ...f, currency: e.target.value })} required maxLength={3} /></Field>
          <Field label="Required approvals">
            <Input type="number" min={1} max={10} value={f.required_approvals}
              onChange={(e) => setF({ ...f, required_approvals: Number(e.target.value) })} required />
          </Field>
          <Field label="Minimum amount"><Input value={f.min_amount} onChange={(e) => setF({ ...f, min_amount: e.target.value })} required inputMode="decimal" /></Field>
          <Field label="Maximum amount" optional hint="Leave empty for no upper bound.">
            <Input value={f.max_amount ?? ""} onChange={(e) => setF({ ...f, max_amount: e.target.value })} inputMode="decimal" />
          </Field>
          <Field label="Approval order">
            <Select value={f.approval_order} onChange={(e) => setF({ ...f, approval_order: e.target.value as RuleBody["approval_order"] })}
              options={[{ value: "ANY_ORDER", label: "Any order" }, { value: "SEQUENTIAL", label: "Sequential (in the order below)" }]} />
          </Field>
        </div>
        <fieldset className="rounded-[var(--radius)] border border-border p-3">
          <legend className="px-1 text-sm font-medium">Approvers (optional: any entitled approver when none are picked)</legend>
          {approvers.length === 0 ? <p className="text-muted">No active APPROVER or ADMIN users.</p> : (
            <ol className="flex flex-col gap-1">
              {approvers.map((u) => {
                const pos = (f.approver_ids ?? []).indexOf(u.id);
                return (
                  <li key={u.id}>
                    <Checkbox label={<>{u.display_name} <span className="text-muted">({u.role}){pos >= 0 && f.approval_order === "SEQUENTIAL" ? ` · #${pos + 1}` : ""}</span></>}
                      checked={pos >= 0} onChange={(e) => toggle(u.id, e.target.checked)} />
                  </li>
                );
              })}
            </ol>
          )}
        </fieldset>
        <ErrorBanner error={r.error} />
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>Close</Button>
          <Button type="submit" disabled={r.pending}>{r.pending ? "Saving…" : "Save rule"}</Button>
        </div>
      </form>
    </Dialog>
  );
}

export default function RulesPage() {
  const perms = usePermissions();
  const users = useUsers(perms.isAdmin);
  const [editing, setEditing] = useState<BusinessApprovalRule | "new" | null>(null);
  const rules = useQuery({
    queryKey: ["approval-rules"], enabled: perms.isAdmin,
    queryFn: async () => unwrap(await api.GET("/v1/business/approval-rules", { params: { query: { limit: 200 } } })).data,
  });
  const name = (id: string) => users.data?.find((u) => u.id === id)?.display_name ?? id.slice(-8);

  return (
    <AdminOnly>
      <PageHeader title="Approval rules" subtitle="The approval matrix: amount bands per currency and scope decide how many approvals a payment needs"
        actions={<Button onClick={() => setEditing("new")}>Add rule</Button>} />
      <Card flush>
        <Async q={rules} empty={<div className="p-4"><Empty title="No rules">Without a matching rule a payment needs one approval.</Empty></div>}>
          {(rows) => (
            <Table rows={rows} rowKey={(r) => r.id} caption="Approval rules" columns={[
              { header: "Name", cell: (r) => r.name },
              { header: "Applies to", cell: (r) => r.applies_to },
              { header: "Band", cell: (r) => <><Money amount={r.min_amount} currency={r.currency} /> – {r.max_amount ? <Money amount={r.max_amount} currency={r.currency} /> : "∞"}</> },
              { header: "Approvals", cell: (r) => r.required_approvals },
              { header: "Order", cell: (r) => <Badge value={r.approval_order} tone="muted" /> },
              { header: "Approvers", cell: (r) => ((r.approver_ids as string[] | null) ?? []).map(name).join(", ") || "Any entitled approver" },
              { header: "", align: "right", cell: (r) => (
                <div className="flex justify-end gap-2">
                  <Button size="sm" variant="secondary" onClick={() => setEditing(r)}>Replace</Button>
                  <ActionButton label="Delete" variant="danger" title={`Deactivate rule "${r.name}"?`} confirmLabel="Deactivate"
                    run={async (_v, key) => unwrap(await api.DELETE("/v1/business/approval-rules/{rule_id}", {
                      params: { path: { rule_id: r.id }, header: idem(key) } }))} />
                </div>
              ) },
            ]} />
          )}
        </Async>
      </Card>
      {editing && <RuleDialog key={editing === "new" ? "new" : editing.id} open rule={editing === "new" ? undefined : editing}
        onClose={() => setEditing(null)} />}
    </AdminOnly>
  );
}
