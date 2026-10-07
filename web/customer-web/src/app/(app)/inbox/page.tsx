"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { idem, inbox, notifications, unwrap } from "@/api/client";
import { useCustomerId } from "@/api/me";
import { Async, Badge, Button, Card, DateTime, Empty, ErrorBanner, Field, Input, PageHeader, Select, Textarea } from "@/components/ui";
import { useT } from "@/lib/i18n";

const TOPICS = ["GENERAL", "ACCOUNTS", "PAYMENTS", "CARDS", "LOANS", "COMPLAINT"] as const;

export default function Inbox() {
  const t = useT();
  const c = useCustomerId();
  const qc = useQueryClient();
  const router = useRouter();
  const threads = useQuery({
    queryKey: ["threads", c], enabled: !!c,
    queryFn: async () => unwrap(await inbox.GET("/v1/inbox/threads", { params: { query: { customer_id: c, limit: 50 } } }))
      .data.filter((x) => x.customer_id === c),
  });
  const notes = useQuery({
    queryKey: ["notifications", c], enabled: !!c,
    queryFn: async () => unwrap(await notifications.GET("/v1/notifications", {
      params: { query: { recipient_type: "CUSTOMER", recipient_id: c, channel: "IN_APP", limit: 30 } } }))
      .data.filter((n) => n.recipient_id === c),
  });
  const markRead = useMutation({
    mutationFn: async (id: string) => unwrap(await notifications.POST("/v1/notifications/{id}/mark-read", { params: { path: { id }, header: idem() } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["notifications", c] }),
  });

  const [form, setForm] = useState({ subject: "", topic: "GENERAL", message: "" });
  const [err, setErr] = useState<Record<string, string>>({});
  const create = useMutation({
    mutationFn: async () => unwrap(await inbox.POST("/v1/inbox/threads", {
      params: { header: idem() },
      body: { customer_id: c, subject: form.subject.trim(), topic: form.topic, opened_by: "CUSTOMER", initial_message: form.message.trim() },
    })),
    onSuccess: (th) => { qc.invalidateQueries({ queryKey: ["threads", c] }); router.push(`/inbox/${th.id}`); },
  });

  return (
    <>
      <PageHeader title={t("nav.inbox")} subtitle={t("inbox.subtitle")} />
      <div className="grid gap-5 lg:grid-cols-[1fr_22rem]">
        <div className="flex flex-col gap-5">
          <Card title={t("inbox.messages")}>
            <Async q={threads} empty={<Empty title={t("inbox.none")} />}>
              {(rows) => (
                <ul className="divide-y divide-border">
                  {rows.map((th) => (
                    <li key={th.id}>
                      <Link href={`/inbox/${th.id}`} className="flex min-h-14 items-center justify-between gap-3 rounded px-1 py-2.5 hover:bg-surface-2 focus-visible:outline-3 focus-visible:outline-focus">
                        <span className="min-w-0">
                          <span className={`block truncate ${(th.unread_customer ?? 0) > 0 ? "font-semibold" : ""}`}>{th.subject}</span>
                          <span className="block text-xs text-muted"><DateTime value={th.last_message_at ?? th.created_at} /> · <Badge value={th.status} /></span>
                        </span>
                        {(th.unread_customer ?? 0) > 0 && (
                          <span className="rounded-full bg-accent px-2 py-0.5 text-xs font-semibold text-accent-fg">
                            {th.unread_customer}<span className="sr-only"> {t("inbox.unread")}</span>
                          </span>
                        )}
                      </Link>
                    </li>
                  ))}
                </ul>
              )}
            </Async>
          </Card>
          <Card title={t("inbox.notifications")}>
            <ErrorBanner error={markRead.error} className="mb-3" />
            <Async q={notes} empty={<Empty title={t("inbox.noNotifications")} />}>
              {(rows) => (
                <ul className="divide-y divide-border">
                  {rows.map((n) => (
                    <li key={n.id} className="flex items-start justify-between gap-3 py-2.5">
                      <span className="min-w-0">
                        <span className={`block ${n.status !== "READ" ? "font-semibold" : ""}`}>{n.subject ?? n.category.toLowerCase()}</span>
                        <span className="block text-sm">{n.body}</span>
                        <span className="block text-xs text-muted"><DateTime value={n.created_at} /></span>
                      </span>
                      {n.status !== "READ" && (
                        <Button variant="ghost" onClick={() => markRead.mutate(n.id!)}>{t("inbox.markRead")}</Button>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </Async>
          </Card>
        </div>

        <Card title={t("inbox.new")}>
          <form noValidate className="flex flex-col gap-4" onSubmit={(e) => {
            e.preventDefault();
            const er: Record<string, string> = {};
            if (!form.subject.trim()) er.subject = t("form.required");
            if (!form.message.trim()) er.message = t("form.required");
            setErr(er);
            if (!Object.keys(er).length) create.mutate();
          }}>
            <Field label={t("inbox.topic")}>
              <Select value={form.topic} onChange={(e) => setForm({ ...form, topic: e.target.value })}
                options={TOPICS.map((x) => ({ value: x, label: t(`topic.${x}`) }))} />
            </Field>
            <Field label={t("inbox.subject")} error={err.subject}>
              <Input maxLength={200} value={form.subject} onChange={(e) => setForm({ ...form, subject: e.target.value })} />
            </Field>
            <Field label={t("inbox.message")} hint={t("inbox.noSecrets")} error={err.message}>
              <Textarea rows={5} value={form.message} onChange={(e) => setForm({ ...form, message: e.target.value })} />
            </Field>
            <ErrorBanner error={create.error} />
            <Button type="submit" disabled={create.isPending}>{create.isPending ? t("common.working") : t("inbox.send")}</Button>
          </form>
        </Card>
      </div>
    </>
  );
}
