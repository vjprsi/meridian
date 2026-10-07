"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { idem, me, unwrap } from "@/api/client";
import { qk, useCustomerId, useMe } from "@/api/me";
import type { CustomerContact, CustomerDevice, NotificationPreference } from "@/api/types";
import { Preferences } from "@/components/shell";
import { StepUpConfirm } from "@/components/step-up";
import {
  Async, Badge, Button, Card, DateTime, Dialog, Empty, ErrorBanner, Field, Input, Notice, PageHeader, Select, Summary,
} from "@/components/ui";
import { useI18n, useT } from "@/lib/i18n";

export default function Profile() {
  const t = useT();
  return (
    <>
      <PageHeader title={t("nav.profile")} subtitle={t("profile.subtitle")} />
      <div className="grid gap-5 lg:grid-cols-2">
        <Details />
        <Contacts />
        <Addresses />
        <NotificationPrefs />
        <Devices />
        <Card title={t("prefs.title")}><Preferences /></Card>
      </div>
    </>
  );
}

function Details() {
  const t = useT();
  const { locale } = useI18n();
  const c = useCustomerId();
  const qc = useQueryClient();
  const self = useMe();
  const save = useMutation({
    mutationFn: async () => unwrap(await me.PATCH("/v1/me/profile", {
      params: { header: idem() }, body: { preferred_language: locale } })),
    onSuccess: (n) => qc.setQueryData(qk.me(c), n),
  });
  return (
    <Card title={t("profile.details")}>
      <Async q={self}>
        {(m) => (
          <>
            <Summary items={[
              { label: t("profile.name"), value: [m.title, m.first_name, m.middle_name, m.last_name].filter(Boolean).join(" ") || m.display_name || "—" },
              { label: t("profile.customerNo"), value: m.customer_number ?? "—" },
              { label: t("profile.dob"), value: <DateTime value={m.date_of_birth} dateOnly /> },
              { label: t("profile.kycTier"), value: m.kyc_tier?.replace("_", " ") ?? "—" },
              { label: t("profile.status"), value: <Badge value={m.status} /> },
              { label: t("profile.language"), value: m.preferred_language ?? "—" },
            ]} />
            <p className="mt-3 text-xs text-muted">{t("profile.nameChange")}</p>
            {m.preferred_language !== locale && (
              <Button variant="secondary" className="mt-3" disabled={save.isPending} onClick={() => save.mutate()}>
                {t("profile.saveLanguage")}
              </Button>
            )}
            <ErrorBanner error={save.error} className="mt-3" />
          </>
        )}
      </Async>
    </Card>
  );
}

function Contacts() {
  const t = useT();
  const c = useCustomerId();
  const qc = useQueryClient();
  const list = useQuery({
    queryKey: ["contacts", c], enabled: !!c,
    queryFn: async () => unwrap(await me.GET("/v1/me/contacts", { params: { query: { limit: 50 } } })).data,
  });
  const refresh = () => qc.invalidateQueries({ queryKey: ["contacts", c] });
  const [adding, setAdding] = useState(false);
  const [form, setForm] = useState({ contact_type: "MOBILE" as CustomerContact["contact_type"], value: "" });
  const [formErr, setFormErr] = useState("");
  const add = useMutation({
    mutationFn: async () => unwrap(await me.POST("/v1/me/contacts", {
      params: { header: idem() },
      body: { contact_type: form.contact_type, ...(form.contact_type === "EMAIL" ? { email: form.value.trim() } : { phone: form.value.trim() }) },
    })),
    onSuccess: () => { setAdding(false); setForm({ ...form, value: "" }); refresh(); },
  });

  const [verifying, setVerifying] = useState<CustomerContact | null>(null);
  const [code, setCode] = useState("");
  const send = useMutation({
    mutationFn: async (id: string) => unwrap(await me.POST("/v1/me/contacts/{contact_id}/send-verification", {
      params: { path: { contact_id: id }, header: idem() } })),
  });
  const verify = useMutation({
    mutationFn: async () => unwrap(await me.POST("/v1/me/contacts/{contact_id}/verify", {
      params: { path: { contact_id: verifying!.id! }, header: idem() }, body: { code: code.trim() } })),
    onSuccess: () => { setVerifying(null); setCode(""); refresh(); },
  });

  return (
    <Card title={t("profile.contacts")} actions={<Button variant="secondary" onClick={() => setAdding(true)}>{t("profile.addContact")}</Button>}>
      <Async q={list} empty={<Empty title={t("profile.noContacts")} />}>
        {(rows) => (
          <ul className="divide-y divide-border">
            {rows.map((x) => (
              <li key={x.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
                <span>
                  <span className="block font-medium">{x.email ?? x.phone}</span>
                  <span className="mt-1 flex flex-wrap gap-1 text-xs text-muted">
                    {x.contact_type.toLowerCase()}
                    {x.is_primary && <Badge value="PRIMARY" tone="info" />}
                    <Badge value={x.is_verified ? "VERIFIED" : "UNVERIFIED"} tone={x.is_verified ? "good" : "warn"} />
                  </span>
                </span>
                {!x.is_verified && (
                  <Button variant="secondary" onClick={() => { setVerifying(x); setCode(""); send.reset(); verify.reset(); send.mutate(x.id!); }}>
                    {t("profile.verify")}
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}
      </Async>

      <StepUpConfirm open={adding} onClose={() => setAdding(false)} title={t("profile.addContact")} confirmLabel={t("common.save")}
        onConfirm={() => {
          const ok = form.contact_type === "EMAIL" ? /^\S+@\S+\.\S+$/.test(form.value) : /^\+?[0-9 ]{7,16}$/.test(form.value);
          if (!ok) { setFormErr(t("profile.contactInvalid")); return; }
          setFormErr("");
          add.mutate();
        }} pending={add.isPending} error={add.error}
        items={[
          { label: t("profile.contactType"), value: (
            <Select aria-label={t("profile.contactType")} value={form.contact_type}
              onChange={(e) => setForm({ ...form, contact_type: e.target.value as CustomerContact["contact_type"] })}
              options={[{ value: "MOBILE", label: t("profile.mobile") }, { value: "EMAIL", label: t("profile.email") }]} />
          ) },
          { label: form.contact_type === "EMAIL" ? t("profile.email") : t("profile.mobile"), value: (
            <Field label={form.contact_type === "EMAIL" ? t("profile.email") : t("profile.mobile")} error={formErr || undefined}>
              <Input type={form.contact_type === "EMAIL" ? "email" : "tel"} autoComplete={form.contact_type === "EMAIL" ? "email" : "tel"}
                value={form.value} onChange={(e) => setForm({ ...form, value: e.target.value })} />
            </Field>
          ) },
        ]} />

      <Dialog open={!!verifying} onClose={() => setVerifying(null)} title={t("profile.verifyTitle")}
        footer={<>
          <Button variant="secondary" onClick={() => verifying && send.mutate(verifying.id!)} disabled={send.isPending}>{t("profile.resend")}</Button>
          <Button onClick={() => verify.mutate()} disabled={verify.isPending || code.trim().length < 4}>{verify.isPending ? t("common.working") : t("profile.verify")}</Button>
        </>}>
        {send.data && <Notice>{t("profile.codeSent", { to: send.data.destination_masked ?? "" })}</Notice>}
        <ErrorBanner error={send.error} className="mt-2" />
        <div className="mt-3">
          <Field label={t("profile.code")}>
            <Input inputMode="numeric" autoComplete="one-time-code" maxLength={8} value={code} onChange={(e) => setCode(e.target.value)} />
          </Field>
        </div>
        <ErrorBanner error={verify.error} className="mt-3" />
      </Dialog>
    </Card>
  );
}

function Addresses() {
  const t = useT();
  const c = useCustomerId();
  const list = useQuery({
    queryKey: ["addresses", c], enabled: !!c,
    queryFn: async () => unwrap(await me.GET("/v1/me/addresses", { params: { query: { limit: 20 } } })).data,
  });
  return (
    <Card title={t("profile.addresses")}>
      <Async q={list} empty={<Empty title={t("profile.noAddresses")} />}>
        {(rows) => (
          <ul className="divide-y divide-border">
            {rows.map((a) => (
              <li key={a.id} className="py-3 text-sm">
                <address className="not-italic">
                  {[a.line1, a.line2, a.city, a.postal_code, a.country].filter(Boolean).join(", ")}
                </address>
                <span className="mt-1 flex gap-1 text-xs text-muted">{a.address_type.toLowerCase()} {a.is_primary && <Badge value="PRIMARY" tone="info" />}</span>
              </li>
            ))}
          </ul>
        )}
      </Async>
      <p className="mt-3 text-xs text-muted">{t("profile.addressChange")}</p>
    </Card>
  );
}

const CATEGORIES = ["TRANSACTIONAL", "SECURITY", "SERVICE", "MARKETING"] as const satisfies readonly NotificationPreference["category"][];
const CHANNELS = ["PUSH", "SMS", "EMAIL", "IN_APP"] as const satisfies readonly NotificationPreference["channel"][];

function NotificationPrefs() {
  const t = useT();
  const c = useCustomerId();
  const qc = useQueryClient();
  const prefs = useQuery({
    queryKey: ["notif-prefs", c], enabled: !!c,
    queryFn: async () => unwrap(await me.GET("/v1/me/notification-preferences", { params: { query: { limit: 100 } } })).data,
  });
  const set = useMutation({
    // one call: the channel creates or updates the customer's preference for this category and channel
    mutationFn: async ({ category, channel, enabled }: { category: NotificationPreference["category"]; channel: NotificationPreference["channel"]; enabled: boolean }) =>
      unwrap(await me.PUT("/v1/me/notification-preferences", {
        params: { header: idem() }, body: { category, channel, is_enabled: enabled } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["notif-prefs", c] }),
  });
  return (
    <Card title={t("profile.notifications")}>
      <Async q={prefs}>
        {(rows) => (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[22rem] text-sm">
              <caption className="sr-only">{t("profile.notifications")}</caption>
              <thead>
                <tr className="text-left text-xs text-muted">
                  <th scope="col" className="py-2 font-medium">{t("profile.category")}</th>
                  {CHANNELS.map((ch) => <th key={ch} scope="col" className="py-2 text-center font-medium">{t(`channel.${ch}`)}</th>)}
                </tr>
              </thead>
              <tbody>
                {CATEGORIES.map((cat) => (
                  <tr key={cat} className="border-t border-border">
                    <th scope="row" className="py-2 text-left font-medium">{t(`notifCat.${cat}`)}</th>
                    {CHANNELS.map((ch) => {
                      const p = rows.find((r) => r.category === cat && r.channel === ch);
                      // Security messages cannot be switched off: the bank must be able to reach the customer.
                      const locked = cat === "SECURITY";
                      const on = locked ? true : p ? p.is_enabled : cat !== "MARKETING";
                      return (
                        <td key={ch} className="py-1 text-center">
                          <input type="checkbox" checked={on} disabled={locked || set.isPending}
                            aria-label={`${t(`notifCat.${cat}`)} · ${t(`channel.${ch}`)}`}
                            onChange={(e) => set.mutate({ category: cat, channel: ch, enabled: e.target.checked })}
                            className="h-5 w-5 accent-[var(--accent)]" />
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Async>
      <p className="mt-3 text-xs text-muted">{t("profile.securityLocked")}</p>
      <ErrorBanner error={set.error} className="mt-3" />
    </Card>
  );
}

function Devices() {
  const t = useT();
  const c = useCustomerId();
  const qc = useQueryClient();
  const list = useQuery({
    queryKey: ["devices", c], enabled: !!c,
    queryFn: async () => unwrap(await me.GET("/v1/me/devices", { params: { query: { limit: 50 } } }))
      .data.filter((d) => d.status !== "REVOKED"),
  });
  const [revoking, setRevoking] = useState<CustomerDevice | null>(null);
  const revoke = useMutation({
    mutationFn: async () => unwrap(await me.POST("/v1/me/devices/{device_id}/revoke", {
      params: { path: { device_id: revoking!.id! }, header: idem() }, body: { reason: "Revoked by customer from web banking" } })),
    onSuccess: () => { setRevoking(null); qc.invalidateQueries({ queryKey: ["devices", c] }); },
  });
  return (
    <Card title={t("profile.devices")}>
      <p className="mb-3 text-xs text-muted">{t("profile.devicesHint")}</p>
      <Async q={list} empty={<Empty title={t("profile.noDevices")} />}>
        {(rows) => (
          <ul className="divide-y divide-border">
            {rows.map((d) => (
              <li key={d.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
                <span>
                  <span className="block font-medium">{d.name || d.model || d.platform}</span>
                  <span className="block text-xs text-muted">
                    {d.platform.toLowerCase()} · {t("profile.lastSeen")} <DateTime value={d.last_seen_at} /> · <Badge value={d.status} />
                  </span>
                </span>
                <Button variant="secondary" onClick={() => setRevoking(d)}>{t("profile.revoke")}</Button>
              </li>
            ))}
          </ul>
        )}
      </Async>
      <StepUpConfirm open={!!revoking} onClose={() => setRevoking(null)} title={t("profile.revokeTitle")} confirmLabel={t("profile.revoke")}
        onConfirm={() => revoke.mutate()} pending={revoke.isPending} error={revoke.error}
        items={revoking ? [{ label: t("profile.device"), value: revoking.name || revoking.model || revoking.platform }] : []} />
    </Card>
  );
}
