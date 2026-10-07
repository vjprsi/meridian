"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { useMe, usePermissions, useStores, type Permissions } from "@/api/merchant";
import { signOut } from "@/lib/auth";
import { setLang, useLang, useT, type Lang, type MessageKey } from "@/lib/i18n";
import { setSelectedStore } from "@/lib/store";
import { useTheme, type ThemeChoice } from "@/lib/theme";
import { useSession } from "./providers";
import { Badge, Button, ErrorBanner, cx, focusRing } from "./ui";

interface NavItem { href: string; label: MessageKey; icon: string; show?: (p: Permissions) => boolean; primary?: boolean }

/** Role-aware navigation (the server enforces every rule again; this only hides what the user cannot use). */
const NAV: NavItem[] = [
  { href: "/", label: "navHome", icon: "⌂", primary: true },
  { href: "/sale", label: "navSale", icon: "＋", show: (p) => p.canSell, primary: true },
  { href: "/sales", label: "navSales", icon: "≡", primary: true },
  { href: "/transactions", label: "navTransactions", icon: "⇄" },
  { href: "/refunds", label: "navRefunds", icon: "↺", primary: true },
  { href: "/links", label: "navLinks", icon: "🔗", show: (p) => p.canSeeBooks },
  { href: "/settlements", label: "navSettlements", icon: "🏦", show: (p) => p.canSeeBooks },
  { href: "/staff", label: "navStaff", icon: "👥", show: (p) => p.isOwner },
  { href: "/settings", label: "navSettings", icon: "⚙", show: (p) => p.isManager },
  { href: "/developers", label: "navDevelopers", icon: "{}", show: (p) => p.isOwner },
  { href: "/activity", label: "navActivity", icon: "🕑" },
];

function isActive(path: string, href: string) {
  return href === "/" ? path === "/" : path === href || path.startsWith(href + "/");
}

export function LangSelect() {
  const t = useT();
  const lang = useLang();
  return (
    <label className="flex items-center gap-1 text-xs text-muted">
      <span className="sr-only">{t("language")}</span>
      <select value={lang} onChange={(e) => setLang(e.target.value as Lang)} aria-label={t("language")}
        className={cx("min-h-9 rounded border border-border-strong bg-surface px-2 py-1 text-xs text-text", focusRing)}>
        <option value="en">English</option>
        <option value="sw">Kiswahili</option>
      </select>
    </label>
  );
}

function ThemeSelect() {
  const t = useT();
  const { theme, setTheme } = useTheme();
  return (
    <label className="flex items-center gap-2 text-xs text-muted">
      <span>{t("theme")}</span>
      <select value={theme} onChange={(e) => setTheme(e.target.value as ThemeChoice)}
        className={cx("min-h-9 rounded border border-border-strong bg-surface px-2 py-1 text-xs text-text", focusRing)}>
        <option value="system">{t("themeSystem")}</option>
        <option value="light">{t("themeLight")}</option>
        <option value="dark">{t("themeDark")}</option>
      </select>
    </label>
  );
}

function SideNav({ items, onNavigate }: { items: NavItem[]; onNavigate?: () => void }) {
  const path = usePathname();
  const t = useT();
  return (
    <nav aria-label="Main">
      <ul className="flex flex-col gap-0.5">
        {items.map((i) => {
          const active = isActive(path, i.href);
          return (
            <li key={i.href}>
              <Link href={i.href} onClick={onNavigate} aria-current={active ? "page" : undefined}
                className={cx("flex min-h-11 items-center gap-3 rounded-[var(--radius)] px-3 text-sm", focusRing,
                  active ? "bg-accent-soft font-semibold text-accent" : "text-text hover:bg-surface-2")}>
                <span aria-hidden="true" className="w-5 text-center">{i.icon}</span>
                <span>{t(i.label)}</span>
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}

/** Bottom tab bar on phones: the cashier's main screens plus "More" for the rest. */
function BottomNav({ items, onMore, moreOpen }: { items: NavItem[]; onMore: () => void; moreOpen: boolean }) {
  const path = usePathname();
  const t = useT();
  const primary = items.filter((i) => i.primary);
  return (
    <nav aria-label="Quick" className="fixed inset-x-0 bottom-0 z-30 border-t border-border bg-surface/95 pb-[env(safe-area-inset-bottom)] backdrop-blur lg:hidden">
      <ul className="grid" style={{ gridTemplateColumns: `repeat(${primary.length + 1}, minmax(0, 1fr))` }}>
        {primary.map((i) => {
          const active = isActive(path, i.href);
          return (
            <li key={i.href}>
              <Link href={i.href} aria-current={active ? "page" : undefined}
                className={cx("flex min-h-14 flex-col items-center justify-center gap-0.5 text-xs", focusRing,
                  active ? "font-semibold text-accent" : "text-muted")}>
                <span aria-hidden="true" className="text-lg leading-none">{i.icon}</span>
                <span>{t(i.label)}</span>
              </Link>
            </li>
          );
        })}
        <li>
          <button type="button" onClick={onMore} aria-expanded={moreOpen}
            className={cx("flex min-h-14 w-full flex-col items-center justify-center gap-0.5 text-xs text-muted", focusRing)}>
            <span aria-hidden="true" className="text-lg leading-none">⋯</span>
            <span>{t("more")}</span>
          </button>
        </li>
      </ul>
    </nav>
  );
}

/**
 * Signed-in frame: skip link, header with the merchant, the current store and the user, role-aware navigation (side
 * on desktop, bottom tabs on phones), the session guard and the store guard (a user with several stores picks one).
 */
export function Shell({ children }: { children: ReactNode }) {
  const { session, ready } = useSession();
  const router = useRouter();
  const path = usePathname();
  const t = useT();
  const me = useMe();
  const perms = usePermissions();
  const { stores, store, loaded } = useStores();
  const [menu, setMenu] = useState(false);

  useEffect(() => {
    if (ready && !session) router.replace("/login");
  }, [ready, session, router]);

  useEffect(() => {
    if (!loaded || store) return;
    if (stores.length === 1) setSelectedStore(stores[0].id);
    else if (stores.length > 1 && path !== "/stores") router.replace("/stores");
  }, [loaded, store, stores, path, router]);

  if (!ready || !session) {
    return <p role="status" className="p-8 text-sm text-muted">{t("loading")}</p>;
  }
  if (!session.merchantId || !session.merchantUserId) {
    return (
      <main id="main" className="mx-auto max-w-lg p-6">
        <h1 className="text-xl font-semibold">{t("noMerchantUser")}</h1>
        <p className="mt-2 text-sm text-muted">{t("noMerchantUserHint")}</p>
        <Button className="mt-4" onClick={() => signOut(session)}>{t("signOut")}</Button>
      </main>
    );
  }

  const user = me.data?.user;
  const merchant = me.data?.merchant;
  const items = NAV.filter((i) => !i.show || (perms.loaded && i.show(perms)));

  return (
    <div className="flex min-h-screen flex-col">
      <a href="#main" className="sr-only focus:not-sr-only focus:fixed focus:left-3 focus:top-3 focus:z-50 focus:rounded focus:bg-surface focus:px-4 focus:py-2 focus:text-accent">
        {t("skipToContent")}
      </a>
      <header className="sticky top-0 z-30 border-b border-border bg-surface/95 backdrop-blur">
        <div className="flex items-center justify-between gap-2 px-3 py-2">
          <div className="flex min-w-0 items-center gap-2">
            <Link href="/" className={cx("flex shrink-0 items-center gap-2 rounded", focusRing)}>
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src="/icon.svg" alt="" width={28} height={28} />
              <span className="hidden font-semibold sm:inline">{t("appName")}</span>
            </Link>
            <div className="min-w-0">
              <p className="truncate text-sm font-medium" data-testid="merchant-name">{merchant?.trading_name ?? ""}</p>
              {stores.length > 0 && (
                <Link href="/stores" data-testid="current-store"
                  className={cx("block truncate text-xs text-accent underline-offset-4 hover:underline", focusRing)}>
                  {t("currentStore")}: {store?.name ?? t("selectStore")}
                </Link>
              )}
            </div>
          </div>
          <div className="flex shrink-0 items-center gap-2">
            {session.mode === "dev" && (
              <span className="hidden rounded bg-warn-soft px-2 py-0.5 text-xs font-medium text-warn md:inline">{t("devMode")}</span>
            )}
            <span className="hidden text-sm md:inline" data-testid="current-user">
              {user?.display_name ?? session.user} {user?.role && <Badge value={t(`role${user.role}`)} tone="info" />}
            </span>
            <LangSelect />
            <Button variant="secondary" size="sm" className="hidden sm:inline-flex" onClick={() => signOut(session)}>{t("signOut")}</Button>
          </div>
        </div>
      </header>

      <div className="flex w-full flex-1">
        <aside className="hidden w-60 shrink-0 border-r border-border bg-surface px-2 py-4 lg:block">
          <SideNav items={items} />
          <div className="mt-6 px-3"><ThemeSelect /></div>
        </aside>
        {menu && (
          <div className="fixed inset-0 z-40 bg-black/40 lg:hidden" onClick={() => setMenu(false)}>
            <div role="dialog" aria-label={t("menu")} onClick={(e) => e.stopPropagation()}
              className="absolute inset-x-0 bottom-0 max-h-[80vh] overflow-y-auto rounded-t-2xl bg-surface p-4 pb-8">
              <SideNav items={items} onNavigate={() => setMenu(false)} />
              <div className="mt-4 flex flex-wrap items-center justify-between gap-3 border-t border-border pt-4">
                <span className="text-sm" data-testid="menu-user">
                  {user?.display_name ?? session.user} {user?.role && <Badge value={t(`role${user.role}`)} tone="info" />}
                </span>
                <ThemeSelect />
                <Button variant="secondary" onClick={() => signOut(session)}>{t("signOut")}</Button>
              </div>
            </div>
          </div>
        )}
        <main id="main" tabIndex={-1} className="min-w-0 flex-1 px-4 pb-28 pt-5 outline-none lg:px-8 lg:pb-12">
          {me.error ? (
            <div className="max-w-xl">
              <h1 className="mb-3 text-xl font-semibold">{t("accessNotLoaded")}</h1>
              <ErrorBanner error={me.error} />
            </div>
          ) : children}
        </main>
      </div>
      <BottomNav items={items} onMore={() => setMenu((m) => !m)} moreOpen={menu} />
    </div>
  );
}
