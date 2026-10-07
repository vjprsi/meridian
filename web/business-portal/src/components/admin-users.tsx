"use client";

import { api, idem, unwrap } from "@/api/client";
import type { BusinessRole, BusinessUser } from "@/api/types";
import { ActionButton, opt } from "./actions";
import { Button, Dialog } from "./ui";

export const ROLES: BusinessRole[] = ["ADMIN", "MAKER", "APPROVER", "VIEWER"];
export const roleOptions = ROLES.map((r) => ({ value: r, label: r }));

/** Edit / suspend / reactivate / remove one user (ADMIN; step-up). Admins cannot change their own role or suspend themselves. */
export function UserActions({ u, self }: { u: BusinessUser; self: boolean }) {
  const path = { user_id: u.id };
  if (u.status === "REMOVED") return null;
  return (
    <div className="flex flex-wrap justify-end gap-2">
      <ActionButton label="Edit" title={`Edit ${u.display_name}`} confirmLabel="Save"
        description={self ? "You cannot change your own role." : undefined}
        fields={[
          { name: "display_name", label: "Name", required: true, defaultValue: u.display_name },
          ...(self ? [] : [{ name: "role", label: "Role", type: "select" as const, required: true, options: roleOptions, defaultValue: u.role }]),
          { name: "daily_limit", label: "Daily limit", type: "number", defaultValue: u.daily_limit ?? "" },
          { name: "limit_currency", label: "Limit currency", defaultValue: u.limit_currency ?? "" },
        ]}
        run={async (v, key) => unwrap(await api.PATCH("/v1/business/users/{user_id}", {
          params: { path, header: idem(key) },
          body: { display_name: v.display_name, role: self ? undefined : v.role as BusinessRole, daily_limit: opt(v.daily_limit),
            limit_currency: opt(v.limit_currency)?.toUpperCase() } }))} />
      {!self && u.status === "ACTIVE" && (
        <ActionButton label="Suspend" variant="danger" title={`Suspend ${u.display_name}?`} confirmLabel="Suspend"
          fields={[{ name: "reason", label: "Reason", type: "textarea" }]}
          run={async (v, key) => unwrap(await api.POST("/v1/business/users/{user_id}/suspend", {
            params: { path, header: idem(key) }, body: { reason: opt(v.reason) } }))} />
      )}
      {!self && u.status === "SUSPENDED" && (
        <ActionButton label="Reactivate" title={`Reactivate ${u.display_name}?`} confirmLabel="Reactivate"
          run={async (_v, key) => unwrap(await api.POST("/v1/business/users/{user_id}/reactivate", { params: { path, header: idem(key) } }))} />
      )}
      {!self && (
        <ActionButton label="Remove" variant="danger" title={`Remove ${u.display_name} for good?`} confirmLabel="Remove user"
          description="The user loses access and their entitlements are deleted."
          fields={[{ name: "reason", label: "Reason", type: "textarea" }]}
          run={async (v, key) => unwrap(await api.POST("/v1/business/users/{user_id}/remove", {
            params: { path, header: idem(key) }, body: { reason: opt(v.reason) } }))} />
      )}
    </div>
  );
}

/** After adding a user: the id an identity-provider admin sets as the person's business_user_id attribute. */
export function UserCreated({ user, onClose }: { user: BusinessUser | null; onClose: () => void }) {
  return (
    <Dialog open={!!user} onClose={onClose} title="User added" footer={<Button onClick={onClose}>Done</Button>}>
      <p>{user?.display_name} was added as {user?.role}.</p>
      <p className="mt-2 text-muted">Ask your identity-provider administrator to set this value as the person&apos;s
        <code className="font-mono"> business_user_id</code> attribute so they can sign in to the portal:</p>
      <pre className="mt-3 overflow-x-auto rounded-[var(--radius)] bg-surface-2 p-3 font-mono text-xs" data-testid="new-user-id">{user?.id}</pre>
    </Dialog>
  );
}
