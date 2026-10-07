"use client";

import { useQuery } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { usePermissions, useStaff, useStores } from "@/api/merchant";
import { api, unwrap } from "@/api/client";
import type { MerchantRole, MerchantUser } from "@/api/types";
import { useT } from "@/lib/i18n";
import { useRun } from "@/components/step-up";
import {
  Async, Badge, Button, Card, Checkbox, Dialog, Empty, ErrorBanner, Field, Input, Loading, Notice, PageHeader, Select,
} from "@/components/ui";

const ROLES: MerchantRole[] = ["OWNER", "MANAGER", "CASHIER", "VIEWER"];

/** Staff and roles (OWNER only; every change needs a recent sign-in, the server asks for it). */
export default function StaffPage() {
  const t = useT();
  const perms = usePermissions();
  const staff = useStaff(perms.loaded && perms.isOwner);
  const { stores } = useStores();
  const add = useRun();
  const act = useRun();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [role, setRole] = useState<MerchantRole>("CASHIER");
  const [allStores, setAllStores] = useState(false);
  const [storeIds, setStoreIds] = useState<string[]>([]);
  const [added, setAdded] = useState<string | null>(null);
  const [editing, setEditing] = useState<MerchantUser | null>(null);
  const roleOptions = ROLES.map((r) => ({ value: r, label: t(`role${r}`) }));

  if (perms.loaded && !perms.isOwner) {
    return <Empty title={t("code_role_not_allowed")}>{t("code_role_not_allowed_hint")}</Empty>;
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    const r = await add.run(async (key) => unwrap(await api.POST("/v1/merchant/staff", {
      params: { header: { "Idempotency-Key": key } },
      body: { display_name: name.trim(), email: email.trim(), role, all_stores: allStores, store_ids: allStores ? [] : storeIds },
    })));
    if (r.ok) {
      setAdded(r.data.id);
      setOpen(false);
      setName(""); setEmail(""); setStoreIds([]); setAllStores(false);
    }
  }

  function status(u: MerchantUser, op: "suspend" | "reactivate" | "remove") {
    act.newKey();
    void act.run(async (key) => {
      const params = { path: { user_id: u.id }, header: { "Idempotency-Key": key } };
      if (op === "suspend") return unwrap(await api.POST("/v1/merchant/staff/{user_id}/suspend", { params, body: {} }));
      if (op === "remove") return unwrap(await api.POST("/v1/merchant/staff/{user_id}/remove", { params, body: {} }));
      return unwrap(await api.POST("/v1/merchant/staff/{user_id}/reactivate", { params }));
    });
  }

  function changeRole(u: MerchantUser, next: MerchantRole) {
    act.newKey();
    void act.run(async (key) => unwrap(await api.PATCH("/v1/merchant/staff/{user_id}", {
      params: { path: { user_id: u.id }, header: { "Idempotency-Key": key } }, body: { role: next } })));
  }

  return (
    <>
      <PageHeader title={t("staff")}
        actions={!open ? <Button onClick={() => { add.newKey(); setOpen(true); }}>{t("addUser")}</Button> : undefined} />
      {added && <div className="mb-4"><Notice tone="good">{t("userAdded", { id: added })}</Notice></div>}
      {open && (
        <Card title={t("addUser")} className="mb-4">
          <form onSubmit={submit} className="grid gap-4 sm:grid-cols-2">
            <Field label={t("name")}><Input required maxLength={160} value={name} onChange={(e) => setName(e.target.value)} /></Field>
            <Field label={t("email")}><Input type="email" required value={email} onChange={(e) => setEmail(e.target.value)} /></Field>
            <Field label={t("role")}>
              <Select value={role} onChange={(e) => setRole(e.target.value as MerchantRole)} options={roleOptions} />
            </Field>
            <div className="flex flex-col gap-2">
              <Checkbox label={t("allStores")} checked={allStores} onChange={(e) => setAllStores(e.target.checked)} />
              {!allStores && stores.map((s) => (
                <Checkbox key={s.id} label={s.name} checked={storeIds.includes(s.id)}
                  onChange={(e) => setStoreIds((ids) => e.target.checked ? [...ids, s.id] : ids.filter((x) => x !== s.id))} />
              ))}
            </div>
            <div className="sm:col-span-2"><ErrorBanner error={add.error} /></div>
            <div className="flex gap-2 sm:col-span-2">
              <Button type="submit" disabled={add.pending}>{add.pending ? t("working") : t("addUser")}</Button>
              <Button variant="secondary" onClick={() => setOpen(false)}>{t("cancel")}</Button>
            </div>
          </form>
        </Card>
      )}
      <ErrorBanner error={act.error} className="mb-4" />
      <Async q={staff} empty={<Empty title={t("noStaff")} />}>
        {(rows) => (
          <ul className="flex flex-col gap-3">
            {rows.map((u) => {
              const me = u.id === perms.userId;
              return (
                <li key={u.id} className="rounded-[var(--radius)] border border-border bg-surface p-4">
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <p className="font-semibold">{u.display_name}{me ? ` (${t("you")})` : ""}</p>
                      <p className="truncate text-xs text-muted">{u.email} · {u.all_stores || u.role === "OWNER" ? t("allStores") : t("stores")}</p>
                      <p className="font-mono text-xs text-muted">{u.id}</p>
                    </div>
                    <div className="flex flex-col items-end gap-1">
                      <Badge value={t(`role${u.role}`)} tone="info" />
                      <Badge value={u.status} />
                    </div>
                  </div>
                  {!me && u.status !== "REMOVED" && (
                    <div className="mt-3 flex flex-wrap items-center gap-2">
                      <label className="flex items-center gap-2 text-xs">
                        <span>{t("changeRole")}</span>
                        <select value={u.role} disabled={act.pending} onChange={(e) => changeRole(u, e.target.value as MerchantRole)}
                          className="min-h-9 rounded border border-border-strong bg-surface px-2 text-xs">
                          {roleOptions.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                        </select>
                      </label>
                      <Button size="sm" variant="secondary" onClick={() => setEditing(u)}>{t("editStores")}</Button>
                      {u.status === "ACTIVE" && <Button size="sm" variant="secondary" onClick={() => status(u, "suspend")}>{t("suspend")}</Button>}
                      {u.status === "SUSPENDED" && <Button size="sm" variant="secondary" onClick={() => status(u, "reactivate")}>{t("reactivate")}</Button>}
                      <Button size="sm" variant="danger" onClick={() => status(u, "remove")}>{t("remove")}</Button>
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
        )}
      </Async>
      {editing && <StoresDialog user={editing} onClose={() => setEditing(null)} />}
    </>
  );
}

function StoresDialog({ user, onClose }: { user: MerchantUser; onClose: () => void }) {
  const t = useT();
  const { stores } = useStores();
  const save = useRun();
  const assigned = useQuery({
    queryKey: ["staff-stores", user.id],
    queryFn: async () => unwrap(await api.GET("/v1/merchant/staff/{user_id}/stores",
      { params: { path: { user_id: user.id }, query: { limit: 200 } } })).data,
  });
  const [ids, setIds] = useState<string[] | null>(null);
  const current = ids ?? (assigned.data ?? []).map((a) => a.merchant_store_id);
  return (
    <Dialog open onClose={onClose} title={`${t("stores")} · ${user.display_name}`}
      footer={<>
        <Button variant="secondary" onClick={onClose}>{t("cancel")}</Button>
        <Button disabled={save.pending || assigned.isPending} onClick={async () => {
          const r = await save.run(async (key) => unwrap(await api.PUT("/v1/merchant/staff/{user_id}/stores", {
            params: { path: { user_id: user.id }, header: { "Idempotency-Key": key } }, body: { store_ids: current } })));
          if (r.ok) onClose();
        }}>{t("saveStores")}</Button>
      </>}>
      {assigned.isPending ? <Loading rows={2} /> : (
        <div className="flex flex-col gap-2">
          {stores.map((s) => (
            <Checkbox key={s.id} label={s.name} checked={current.includes(s.id)}
              onChange={(e) => setIds(e.target.checked ? [...current, s.id] : current.filter((x) => x !== s.id))} />
          ))}
          <ErrorBanner error={assigned.error ?? save.error} />
        </div>
      )}
    </Dialog>
  );
}
