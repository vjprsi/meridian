"use client";

import { useEffect, useState, type ReactNode } from "react";
import { cx } from "./ui";

/** Monospace block with a copy button. Code is shown on a fixed dark surface in both themes. */
export function CodeBlock({ code, lang, className, title }: { code: string; lang?: string; className?: string; title?: ReactNode }) {
  const [copied, setCopied] = useState(false);
  return (
    <div className={cx("group relative my-3 overflow-hidden rounded-[var(--radius)] border border-border", className)}>
      {(title || lang) && (
        <div className="flex items-center justify-between bg-surface-2 px-3 py-1.5 text-xs text-muted">
          <span>{title ?? lang}</span>
        </div>
      )}
      <pre className="max-h-[32rem] overflow-auto bg-[var(--code-bg)] p-4 font-mono text-[12.5px] leading-relaxed text-[var(--code-fg)]">
        <code>{code}</code>
      </pre>
      <button type="button"
        onClick={async () => {
          try { await navigator.clipboard.writeText(code); setCopied(true); setTimeout(() => setCopied(false), 1500); } catch { /* clipboard blocked */ }
        }}
        className="absolute right-2 bottom-2 rounded bg-white/10 px-2 py-0.5 text-[11px] text-white/80 opacity-0 transition group-hover:opacity-100 hover:bg-white/20 focus-visible:opacity-100">
        {copied ? "Copied" : "Copy"}
      </button>
    </div>
  );
}

/** Same snippet in several languages; the selected language is remembered for the session. */
export function CodeTabs({ tabs }: { tabs: { label: string; code: string }[] }) {
  const [active, setActive] = useState(tabs[0].label);
  useEffect(() => {
    try {
      const saved = sessionStorage.getItem("devportal.lang");
      if (saved) setActive(saved);
    } catch { /* storage unavailable */ }
  }, []);
  const cur = tabs.find((t) => t.label === active) ?? tabs[0];
  return (
    <div className="my-3">
      <div className="flex gap-1 border-b border-border" role="tablist">
        {tabs.map((t) => (
          <button key={t.label} type="button" role="tab" aria-selected={t.label === cur.label}
            onClick={() => { setActive(t.label); try { sessionStorage.setItem("devportal.lang", t.label); } catch { /* ignore */ } }}
            className={cx("-mb-px border-b-2 px-3 py-1.5 text-xs font-medium",
              t.label === cur.label ? "border-accent text-accent" : "border-transparent text-muted hover:text-text")}>
            {t.label}
          </button>
        ))}
      </div>
      <CodeBlock code={cur.code} className="mt-0 rounded-t-none border-t-0" />
    </div>
  );
}

const METHOD_TONES: Record<string, string> = {
  GET: "bg-good-soft text-good",
  POST: "bg-accent-soft text-accent",
  PUT: "bg-warn-soft text-warn",
  PATCH: "bg-warn-soft text-warn",
  DELETE: "bg-bad-soft text-bad",
  EVENT: "bg-surface-2 text-muted",
};

export function MethodBadge({ method, className }: { method: string; className?: string }) {
  const m = method.toUpperCase();
  return (
    <span className={cx("inline-flex w-14 shrink-0 justify-center rounded px-1.5 py-0.5 font-mono text-[10.5px] font-semibold",
      METHOD_TONES[m] ?? "bg-surface-2 text-muted", className)}>{m}</span>
  );
}

export function Callout({ tone = "info", title, children }: { tone?: "info" | "warn" | "good"; title?: string; children: ReactNode }) {
  const cls = { info: "border-accent/30 bg-accent-soft", warn: "border-warn/30 bg-warn-soft", good: "border-good/30 bg-good-soft" }[tone];
  return (
    <div className={cx("my-4 rounded-[var(--radius)] border px-4 py-3 text-sm", cls)}>
      {title && <p className="mb-1 font-semibold">{title}</p>}
      <div className="[&>p]:my-1">{children}</div>
    </div>
  );
}
