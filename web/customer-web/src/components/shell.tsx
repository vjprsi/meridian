"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { signOut } from "@/lib/auth";
import { LOCALES, useI18n, type Locale } from "@/lib/i18n";
import { useTheme, type ThemeChoice } from "@/lib/theme";
import type { MessageKey } from "@/messages/en";
import { useSession } from "./providers";
import { Button, Dialog, cx } from "./ui";

interface NavItem { href: string; label: MessageKey; icon: string }

const NAV: NavItem[] = [
  { href: "/", label: "nav.home", icon: "M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z" },
  { href: "/move", label: "nav.move", icon: "M4 8h13l-4-4M20 16H7l4 4" },
  { href: "/wallet", label: "nav.wallet", icon: "M4 7h13a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h11M16 12h.5" },
  { href: "/cards", label: "nav.cards", icon: "M3 6h18v12H3zM3 10h18" },
  { href: "/loans", label: "nav.loans", icon: "M4 20h16M6 16V9m4 7V9m4 7V9m4 7V9M3 8l9-5 9 5" },
  { href: "/insights", label: "nav.insights", icon: "M4 20V10m6 10V4m6 16v-7m4 7H2" },
  { href: "/rewards", label: "nav.rewards", icon: "M12 3l2.6 5.6 6 .7-4.5 4.1 1.2 6L12 16.5 6.7 19.4l1.2-6L3.4 9.3l6-.7z" },
  { href: "/inbox", label: "nav.inbox", icon: "M3 5h18v14H3zM3 5l9 7 9-7" },
  { href: "/profile", label: "nav.profile", icon: "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm-8 9a8 8 0 0 1 16 0" },
  { href: "/security", label: "nav.security", icon: "M12 3l8 3v6c0 4.5-3.4 8.3-8 9-4.6-.7-8-4.5-8-9V6zM9 12l2 2 4-4" },
];
const MOBILE = ["/", "/move", "/wallet", "/cards"];

function Icon({ d }: { d: string }) {
  return (
    <svg aria-hidden="true" viewBox="0 0 24 24" className="h-5 w-5 shrink-0" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d={d} /></svg>
  );
}

function isActive(path: string, href: string) {
  return href === "/" ? path === "/" || path.startsWith("/accounts") : path === href || path.startsWith(href + "/");
}

export function Preferences() {
  const { t, locale, setLocale } = useI18n();
  const { theme, setTheme } = useTheme();
  return (
    <div className="grid gap-4 sm:grid-cols-2">
      <label className="flex flex-col gap-1.5 text-sm">
        <span className="font-medium">{t("prefs.language")}</span>
        <select value={locale} onChange={(e) => setLocale(e.target.value as Locale)}
          className="min-h-11 rounded-[var(--radius)] border border-border-strong bg-surface px-3 focus-visible:outline-3 focus-visible:outline-focus">
          {LOCALES.map((l) => <option key={l.code} value={l.code} lang={l.code}>{l.label}</option>)}
        </select>
      </label>
      <label className="flex flex-col gap-1.5 text-sm">
        <span className="font-medium">{t("prefs.theme")}</span>
        <select value={theme} onChange={(e) => setTheme(e.target.value as ThemeChoice)}
          className="min-h-11 rounded-[var(--radius)] border border-border-strong bg-surface px-3 focus-visible:outline-3 focus-visible:outline-focus">
          <option value="system">{t("prefs.themeSystem")}</option>
          <option value="light">{t("prefs.themeLight")}</option>
          <option value="dark">{t("prefs.themeDark")}</option>
        </select>
      </label>
    </div>
  );
}

/** Signed-in frame: skip link, header, desktop side navigation, mobile bottom tab bar, and the session guard. */
export function Shell({ children }: { children: ReactNode }) {
  const { session, ready } = useSession();
  const { t } = useI18n();
  const router = useRouter();
  const path = usePathname();
  const [menu, setMenu] = useState(false);

  useEffect(() => {
    if (ready && !session) router.replace("/login");
  }, [ready, session, router]);

  if (!ready || !session) {
    return <p role="status" className="p-8 text-sm text-muted">{t("common.loading")}</p>;
  }
  if (!session.customerId) {
    return (
      <main id="main" className="mx-auto max-w-lg p-6">
        <h1 className="text-xl font-semibold">{t("auth.noCustomerTitle")}</h1>
        <p className="mt-2 text-sm text-muted">{t("auth.noCustomerBody")}</p>
        <Button className="mt-4" onClick={() => signOut(session)}>{t("nav.signOut")}</Button>
      </main>
    );
  }

  return (
    <div className="flex min-h-screen flex-col">
      <a href="#main" className="sr-only focus:not-sr-only focus:fixed focus:left-3 focus:top-3 focus:z-50 focus:rounded focus:bg-surface focus:px-4 focus:py-2 focus:text-accent">
        {t("nav.skip")}
      </a>
      <header className="sticky top-0 z-30 border-b border-border bg-surface/95 backdrop-blur">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-3 px-4 py-2.5">
          <Link href="/" className="flex items-center gap-2 rounded focus-visible:outline-3 focus-visible:outline-focus">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src="/icon.svg" alt="" width={28} height={28} />
            <span className="font-semibold">Meridian</span>
          </Link>
          <div className="flex items-center gap-1">
            {session.mode === "dev" && (
              <span className="hidden rounded bg-warn-soft px-2 py-0.5 text-xs font-medium text-warn sm:inline">{t("auth.devBadge")}</span>
            )}
            <Button variant="ghost" onClick={() => setMenu(true)} aria-haspopup="dialog">{t("nav.menu")}</Button>
          </div>
        </div>
      </header>

      <div className="mx-auto flex w-full max-w-6xl flex-1 gap-6 px-4">
        <nav aria-label={t("nav.main")} className="sticky top-16 hidden h-fit w-52 shrink-0 py-6 md:block">
          <ul className="flex flex-col gap-1">
            {NAV.map((i) => {
              const active = isActive(path, i.href);
              return (
                <li key={i.href}>
                  <Link href={i.href} aria-current={active ? "page" : undefined}
                    className={cx("flex min-h-11 items-center gap-3 rounded-[var(--radius)] px-3 text-sm",
                      "focus-visible:outline-3 focus-visible:outline-focus",
                      active ? "bg-accent-soft font-semibold text-accent" : "text-text hover:bg-surface-2")}>
                    <Icon d={i.icon} />{t(i.label)}
                  </Link>
                </li>
              );
            })}
          </ul>
        </nav>
        <main id="main" tabIndex={-1} className="min-w-0 flex-1 pb-28 pt-5 outline-none md:pb-10 md:pt-6">{children}</main>
      </div>

      <nav aria-label={t("nav.main")} className="fixed inset-x-0 bottom-0 z-30 border-t border-border bg-surface md:hidden"
        style={{ paddingBottom: "env(safe-area-inset-bottom)" }}>
        <ul className="grid grid-cols-5">
          {NAV.filter((i) => MOBILE.includes(i.href)).map((i) => {
            const active = isActive(path, i.href);
            return (
              <li key={i.href}>
                <Link href={i.href} aria-current={active ? "page" : undefined}
                  className={cx("flex min-h-14 flex-col items-center justify-center gap-0.5 text-[11px] focus-visible:outline-3 focus-visible:outline-focus",
                    active ? "font-semibold text-accent" : "text-muted")}>
                  <Icon d={i.icon} />{t(i.label)}
                </Link>
              </li>
            );
          })}
          <li>
            <button type="button" onClick={() => setMenu(true)} aria-haspopup="dialog"
              className="flex min-h-14 w-full flex-col items-center justify-center gap-0.5 text-[11px] text-muted focus-visible:outline-3 focus-visible:outline-focus">
              <Icon d="M4 6h16M4 12h16M4 18h16" />{t("nav.more")}
            </button>
          </li>
        </ul>
      </nav>

      <Dialog open={menu} onClose={() => setMenu(false)} title={t("nav.menu")}
        footer={<>
          <Button variant="secondary" onClick={() => signOut(session)}>{t("nav.signOut")}</Button>
          <Button onClick={() => setMenu(false)}>{t("common.close")}</Button>
        </>}>
        <nav aria-label={t("nav.all")}>
          <ul className="grid grid-cols-2 gap-2">
            {NAV.map((i) => (
              <li key={i.href}>
                <Link href={i.href} onClick={() => setMenu(false)}
                  className="flex min-h-11 items-center gap-2 rounded-[var(--radius)] border border-border px-3 hover:bg-surface-2 focus-visible:outline-3 focus-visible:outline-focus">
                  <Icon d={i.icon} />{t(i.label)}
                </Link>
              </li>
            ))}
            <li>
              <Link href="/standing-orders" onClick={() => setMenu(false)}
                className="flex min-h-11 items-center gap-2 rounded-[var(--radius)] border border-border px-3 hover:bg-surface-2 focus-visible:outline-3 focus-visible:outline-focus">
                {t("nav.standingOrders")}
              </Link>
            </li>
            <li>
              <Link href="/kyc" onClick={() => setMenu(false)}
                className="flex min-h-11 items-center gap-2 rounded-[var(--radius)] border border-border px-3 hover:bg-surface-2 focus-visible:outline-3 focus-visible:outline-focus">
                {t("nav.kyc")}
              </Link>
            </li>
          </ul>
        </nav>
        <div className="mt-5 border-t border-border pt-4"><Preferences /></div>
        <p className="mt-4 text-xs text-muted">{t("auth.signedInAs", { user: session.user })}</p>
      </Dialog>
    </div>
  );
}
