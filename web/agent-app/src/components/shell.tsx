"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState, type FormEvent, type ReactNode } from "react";
import { useSession } from "@/components/providers";
import { Button, Field, Icon, Notice, PinInput, cx } from "@/components/ui";
import { bindingOf, useProfile } from "@/lib/agent-context";
import { useConnectivity } from "@/lib/connectivity";
import { useT } from "@/lib/i18n";
import { outbox, syncNow, unlock, useOutbox } from "@/lib/outbox/runtime";
import { OfflineLimitError } from "@/lib/outbox/outbox";
import { useHeartbeat } from "@/lib/heartbeat";

export function ConnectivityPill() {
  const t = useT();
  const c = useConnectivity();
  const on = c === "online";
  return (
    <span data-testid="connectivity" data-state={c}
      className={cx("inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-sm font-bold",
        on ? "bg-good-soft text-good" : "bg-warn-soft text-warn")}>
      <span aria-hidden className={cx("h-2.5 w-2.5 rounded-full", on ? "bg-good" : "bg-warn")} />
      {on ? t("online") : t("offline")}
    </span>
  );
}

function UnlockScreen() {
  const t = useT();
  const [pin, setPin] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setErr(null);
    try {
      if (!(await unlock(pin))) setErr(t("wrongPin"));
    } catch (x) {
      setErr(x instanceof OfflineLimitError ? x.message : String(x));
    } finally {
      setPin("");
      setBusy(false);
    }
  }
  return (
    <form onSubmit={submit} className="mx-auto mt-10 flex max-w-sm flex-col gap-4">
      <h1 className="text-2xl font-bold">{t("unlockTitle")}</h1>
      <Field label={t("appPin")} error={err}>
        <PinInput autoFocus value={pin} onChange={(e) => setPin(e.target.value.replace(/\D/g, ""))} required minLength={6} maxLength={6} />
      </Field>
      <Button type="submit" block disabled={busy || pin.length !== 6}>{t("unlock")}</Button>
      <Link href="/login" className="text-center text-accent underline">{t("signIn")}</Link>
    </form>
  );
}

const nav = [
  { href: "/", key: "home" as const, icon: Icon.home },
  { href: "/cash-in", key: "cashIn" as const, icon: Icon.in },
  { href: "/cash-out", key: "cashOut" as const, icon: Icon.out },
  { href: "/history", key: "history" as const, icon: Icon.list },
  { href: "/more", key: "more" as const, icon: Icon.more },
];

/**
 * App frame: connectivity and sync status always visible, offline banner, unlock gate, bottom navigation sized for
 * thumbs. Routes to sign-in or setup when the device has no agent profile yet.
 */
export function Shell({ children }: { children: ReactNode }) {
  const t = useT();
  const router = useRouter();
  const path = usePathname();
  const { session, ready } = useSession();
  const profile = useProfile();
  const conn = useConnectivity();
  const ob = useOutbox();
  const [initialized, setInitialized] = useState<boolean | null>(null);
  useHeartbeat();

  useEffect(() => {
    if (!ready) return;
    if (!profile) {
      router.replace(session ? "/setup" : "/login");
      return;
    }
    outbox.isInitialized(bindingOf(profile)).then((ok) => {
      setInitialized(ok);
      if (!ok) router.replace("/setup");
    });
  }, [ready, profile, session, router]);

  if (!ready || !profile || initialized !== true) {
    return <p role="status" className="p-8 text-center text-muted">…</p>;
  }

  const blocked = ob.last?.blocked;
  return (
    <div className="mx-auto flex min-h-screen max-w-xl flex-col">
      <header className="no-print sticky top-0 z-10 flex items-center justify-between gap-2 border-b border-border bg-surface px-4 py-2">
        <Link href="/" className="min-w-0">
          <p className="truncate text-base font-bold">{profile.agentName}</p>
          <p className="truncate text-xs text-muted">{profile.agentCode}{profile.outletName ? ` · ${profile.outletName}` : ""}</p>
        </Link>
        <div className="flex items-center gap-2">
          {ob.pending > 0 && (
            <Link href="/sync" data-testid="pending-badge" className="rounded-full bg-warn-soft px-3 py-1 text-sm font-bold text-warn">
              {ob.pending} {t("pendingSync")}
            </Link>
          )}
          <ConnectivityPill />
        </div>
      </header>
      {conn === "offline" && (
        <div role="status" data-testid="offline-banner" className="no-print border-b-2 border-warn bg-warn-soft px-4 py-2 text-sm font-semibold text-warn">
          {t("offlineBanner")}
        </div>
      )}
      {conn === "online" && !session && (
        <div className="no-print border-b border-border bg-accent-soft px-4 py-2 text-sm">
          <Link href="/login" className="font-semibold text-accent underline">{t("needLoginToSync")}</Link>
        </div>
      )}
      {ob.unlocked && ob.pending > 0 && conn === "online" && (blocked === "NEEDS_PIN" || blocked === "AUTH") && path !== "/sync" && (
        <div className="no-print px-4 pt-3">
          <Notice tone="warn"><Link href="/sync" className="underline">{t("queueBlocked")}: {blocked === "AUTH" ? t("needLoginToSync") : t("needPinToSync")}</Link></Notice>
        </div>
      )}
      <main className="flex-1 px-4 pb-28 pt-4">
        {ob.unlocked ? children : <UnlockScreen />}
      </main>
      {ob.unlocked && (
        <nav aria-label="Main" className="no-print fixed inset-x-0 bottom-0 z-10 border-t border-border bg-surface">
          <ul className="mx-auto grid max-w-xl grid-cols-5">
            {nav.map((n) => {
              const active = n.href === "/" ? path === "/" : path.startsWith(n.href);
              return (
                <li key={n.href}>
                  <Link href={n.href} aria-current={active ? "page" : undefined}
                    className={cx("flex min-h-16 flex-col items-center justify-center gap-0.5 text-xs font-semibold",
                      active ? "text-accent" : "text-muted")}>
                    <span aria-hidden className="[&_svg]:h-6 [&_svg]:w-6">{n.icon}</span>
                    {t(n.key)}
                  </Link>
                </li>
              );
            })}
          </ul>
        </nav>
      )}
    </div>
  );
}

/** "Sync now" button with live state. */
export function SyncButton({ className }: { className?: string }) {
  const t = useT();
  const ob = useOutbox();
  const conn = useConnectivity();
  return (
    <Button variant="secondary" className={className} disabled={ob.syncing || conn !== "online" || ob.pending === 0}
      onClick={() => void syncNow()}>
      {ob.syncing ? t("syncing") : t("syncNow")}
    </Button>
  );
}
