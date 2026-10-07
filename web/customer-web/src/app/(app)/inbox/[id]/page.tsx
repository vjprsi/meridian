"use client";

import { useParams } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { idem, me, unwrap } from "@/api/client";
import { mine, useCustomerId } from "@/api/me";
import { Async, Badge, Button, Card, DateTime, Empty, ErrorBanner, Field, PageHeader, Textarea, cx } from "@/components/ui";
import { useT } from "@/lib/i18n";

export default function Thread() {
  const t = useT();
  const c = useCustomerId();
  const qc = useQueryClient();
  const { id } = useParams<{ id: string }>();
  const thread = useQuery({
    queryKey: ["thread", id], enabled: !!c,
    queryFn: () => mine(async () => unwrap(await me.GET("/v1/me/inbox/threads/{thread_id}", { params: { path: { thread_id: id } } }))),
  });
  const messages = useQuery({
    queryKey: ["thread-messages", id], enabled: thread.isSuccess,
    // the channel never returns staff-only notes
    queryFn: async () => unwrap(await me.GET("/v1/me/inbox/threads/{thread_id}/messages", {
      params: { path: { thread_id: id }, query: { limit: 100 } } })).data,
    refetchInterval: 15_000,
  });
  const read = useMutation({
    mutationFn: async () => unwrap(await me.POST("/v1/me/inbox/threads/{thread_id}/read", { params: { path: { thread_id: id }, header: idem() } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["threads", c] }),
  });
  const marked = useRef(false);
  useEffect(() => {
    if (thread.data && (thread.data.unread_customer ?? 0) > 0 && !marked.current) {
      marked.current = true;
      read.mutate();
    }
  }, [thread.data, read]);

  const [body, setBody] = useState("");
  const [err, setErr] = useState("");
  const send = useMutation({
    mutationFn: async () => unwrap(await me.POST("/v1/me/inbox/threads/{thread_id}/messages", {
      params: { path: { thread_id: id }, header: idem() },
      body: { body: body.trim(), client_message_id: crypto.randomUUID() },
    })),
    onSuccess: () => { setBody(""); qc.invalidateQueries({ queryKey: ["thread-messages", id] }); qc.invalidateQueries({ queryKey: ["thread", id] }); },
  });

  return (
    <>
      <PageHeader title={thread.data?.subject ?? t("nav.inbox")} back={{ href: "/inbox", label: t("nav.inbox") }}
        actions={thread.data && <Badge value={thread.data.status} />} />
      <Async q={thread}>
        {(th) => (
          <div className="flex max-w-3xl flex-col gap-4">
            <Card>
              <Async q={messages} empty={<Empty title={t("inbox.noMessages")} />}>
                {(rows) => (
                  <ol className="flex flex-col gap-3" aria-live="polite">
                    {rows.map((m) => {
                      const mine = m.sender_type === "CUSTOMER";
                      return (
                        <li key={m.id} className={cx("max-w-[85%] rounded-[var(--radius)] px-4 py-2.5", mine ? "self-end bg-accent-soft" : "self-start bg-surface-2")}>
                          <p className="text-xs font-semibold text-muted">{mine ? t("inbox.you") : t("inbox.bank")}</p>
                          <p className="whitespace-pre-wrap text-sm">{m.body}</p>
                          <p className="mt-1 text-xs text-muted"><DateTime value={m.created_at} /></p>
                        </li>
                      );
                    })}
                  </ol>
                )}
              </Async>
            </Card>
            {th.status !== "CLOSED" ? (
              <Card>
                <form noValidate className="flex flex-col gap-3" onSubmit={(e) => {
                  e.preventDefault();
                  if (!body.trim()) { setErr(t("form.required")); return; }
                  setErr("");
                  send.mutate();
                }}>
                  <Field label={t("inbox.reply")} hint={t("inbox.noSecrets")} error={err || undefined}>
                    <Textarea rows={3} value={body} onChange={(e) => setBody(e.target.value)} />
                  </Field>
                  <ErrorBanner error={send.error} />
                  <Button type="submit" disabled={send.isPending} className="self-end">{send.isPending ? t("common.working") : t("inbox.send")}</Button>
                </form>
              </Card>
            ) : <p className="text-sm text-muted">{t("inbox.closed")}</p>}
          </div>
        )}
      </Async>
    </>
  );
}
