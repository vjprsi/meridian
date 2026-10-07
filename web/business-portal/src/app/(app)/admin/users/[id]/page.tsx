"use client";

import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { accountLabel, useAccounts, usePermissions } from "@/api/business";
import { api, idem, unwrap } from "@/api/client";
import type { BusinessEntitlement, BusinessUser, DepositAccount } from "@/api/types";
import { AdminOnly } from "@/components/admin-only";
import { UserActions } from "@/components/admin-users";
import { useRun } from "@/components/step-up";
import {
  Badge, Button, Card, DateTime, ErrorBanner, Input, Loading, Money, Notice, PageHeader, Summary, cx,
} from "@/components/ui";

function EntitlementRow({ user, account, current }: { user: BusinessUser; account: DepositAccount; current?: BusinessEntitlement }) {
  const [view, setView] = useState(current?.can_view ?? false);
  const [initiate, setInitiate] = useState(current?.can_initiate ?? false);
  const [approve, setApprove] = useState(current?.can_approve ?? false);
  const [max, setMax] = useState(current?.max_amount ?? "");
  const save = useRun();
  const remove = useRun();
  const dirty = !current || view !== current.can_view || initiate !== current.can_initiate || approve !== current.can_approve
    || (max || null) !== (current.max_amount ?? null);
  const canInitiateRole = user.role === "MAKER" || user.role === "ADMIN";
  const canApproveRole = user.role === "APPROVER" || user.role === "ADMIN";
  const path = { user_id: user.id, account_id: account.id };

  return (
    <tr className="border-b border-border last:border-0 align-top" data-testid={`ent-${account.id}`}>
      <td className="px-3 py-2">{accountLabel(account)}</td>
      <td className="px-3 py-2 text-center">
        <input type="checkbox" aria-label="View" checked={view || initiate || approve} onChange={(e) => setView(e.target.checked)}
          disabled={initiate || approve} className="h-4 w-4 accent-[var(--accent)]" />
      </td>
      <td className="px-3 py-2 text-center">
        <input type="checkbox" aria-label="Initiate" checked={initiate} onChange={(e) => setInitiate(e.target.checked)}
          disabled={!canInitiateRole} title={canInitiateRole ? undefined : "Needs role MAKER or ADMIN"} className="h-4 w-4 accent-[var(--accent)]" />
      </td>
      <td className="px-3 py-2 text-center">
        <input type="checkbox" aria-label="Approve" checked={approve} onChange={(e) => setApprove(e.target.checked)}
          disabled={!canApproveRole} title={canApproveRole ? undefined : "Needs role APPROVER or ADMIN"} className="h-4 w-4 accent-[var(--accent)]" />
      </td>
      <td className="px-3 py-2">
        <Input aria-label="Maximum amount" value={max} onChange={(e) => setMax(e.target.value)} inputMode="decimal"
          placeholder="No maximum" className="min-h-8 w-36 py-1" />
      </td>
      <td className="px-3 py-2">
        <div className="flex flex-wrap justify-end gap-2">
          <Button size="sm" disabled={!dirty || save.pending} onClick={() => {
            save.newKey();
            void save.run(async (key) => unwrap(await api.PUT("/v1/business/users/{user_id}/entitlements/{account_id}", {
              params: { path, header: idem(key) },
              body: { can_view: view || initiate || approve, can_initiate: initiate, can_approve: approve, max_amount: max || undefined } })));
          }}>{save.pending ? "Saving…" : "Save"}</Button>
          {current && (
            <Button size="sm" variant="secondary" disabled={remove.pending} onClick={() => {
              remove.newKey();
              void remove.run(async (key) => unwrap(await api.DELETE("/v1/business/users/{user_id}/entitlements/{account_id}", {
                params: { path, header: idem(key) } })));
            }}>Remove</Button>
          )}
        </div>
        <ErrorBanner error={save.error ?? remove.error} className="mt-2" />
      </td>
    </tr>
  );
}

export default function UserPage() {
  const { id } = useParams<{ id: string }>();
  const perms = usePermissions();
  const accounts = useAccounts();
  const user = useQuery({
    queryKey: ["user", id], enabled: perms.isAdmin,
    queryFn: async () => unwrap(await api.GET("/v1/business/users/{user_id}", { params: { path: { user_id: id } } })),
  });
  const ents = useQuery({
    queryKey: ["entitlements", id], enabled: perms.isAdmin,
    queryFn: async () => unwrap(await api.GET("/v1/business/users/{user_id}/entitlements",
      { params: { path: { user_id: id }, query: { limit: 200 } } })).data,
  });

  return (
    <AdminOnly>
      {user.isPending || ents.isPending || accounts.isPending ? <Loading rows={5} />
        : user.error || !user.data ? <ErrorBanner error={user.error} />
        : (
          <>
            <PageHeader back={{ href: "/admin/users", label: "Users" }} title={user.data.display_name}
              subtitle={user.data.email ?? undefined} actions={<UserActions u={user.data} self={user.data.id === perms.userId} />} />
            <Card className="mb-5">
              <Summary cols={4} items={[
                { label: "Role", value: <Badge value={user.data.role} tone="info" /> },
                { label: "Status", value: <Badge value={user.data.status} /> },
                { label: "Daily limit", value: user.data.daily_limit ? <Money amount={user.data.daily_limit} currency={user.data.limit_currency} /> : "No limit" },
                { label: "Last sign-in", value: <DateTime value={user.data.last_login_at} /> },
                { label: "business_user_id", value: <span className="font-mono text-xs">{user.data.id}</span> },
              ]} />
            </Card>
            <Card title="Account entitlements" flush>
              {user.data.role === "ADMIN" && (
                <div className="p-4 pb-0"><Notice>ADMIN users see every account; entitlements still decide where they may initiate or approve.</Notice></div>
              )}
              <ErrorBanner error={ents.error} className="m-4" />
              <div className="overflow-x-auto">
                <table className="w-full border-collapse text-sm">
                  <caption className="sr-only">Account entitlements</caption>
                  <thead>
                    <tr className={cx("border-b border-border text-left text-xs uppercase tracking-wide text-muted")}>
                      <th scope="col" className="px-3 py-2">Account</th>
                      <th scope="col" className="px-3 py-2 text-center">View</th>
                      <th scope="col" className="px-3 py-2 text-center">Initiate</th>
                      <th scope="col" className="px-3 py-2 text-center">Approve</th>
                      <th scope="col" className="px-3 py-2">Max amount</th>
                      <th scope="col" className="px-3 py-2"><span className="sr-only">Actions</span></th>
                    </tr>
                  </thead>
                  <tbody>
                    {(accounts.data ?? []).map((a) => {
                      const current = ents.data?.find((e) => e.deposit_account_id === a.id);
                      return <EntitlementRow key={`${a.id}-${current?.row_version ?? 0}`} user={user.data!} account={a} current={current} />;
                    })}
                  </tbody>
                </table>
              </div>
            </Card>
          </>
        )}
    </AdminOnly>
  );
}
