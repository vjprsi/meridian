"use client";

import { useId } from "react";
import { useT } from "@/lib/i18n";

export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
export const ACCEPT = "image/jpeg,image/png,application/pdf";

/** Accessible file picker (camera capture on phones) with the size/type rules stated up front. */
export function FileField({ label, file, onChange, error }:
  { label: string; file: File | null; onChange: (f: File | null) => void; error?: string }) {
  const t = useT();
  const id = useId();
  return (
    <div className="flex flex-col gap-1.5 text-sm">
      <label htmlFor={id} className="font-medium">{label}</label>
      <input id={id} type="file" accept={ACCEPT} capture="environment" aria-describedby={`${id}-hint${error ? ` ${id}-err` : ""}`}
        aria-invalid={error ? true : undefined}
        onChange={(e) => onChange(e.target.files?.[0] ?? null)}
        className="min-h-11 rounded-[var(--radius)] border border-border-strong bg-surface px-3 py-2 file:mr-3 file:rounded file:border-0 file:bg-accent-soft file:px-3 file:py-1.5 file:font-medium file:text-accent focus-visible:outline-3 focus-visible:outline-focus" />
      <p id={`${id}-hint`} className="text-xs text-muted">{t("upload.hint")}{file ? ` · ${file.name}` : ""}</p>
      {error && <p id={`${id}-err`} className="text-xs font-medium text-bad">{error}</p>}
    </div>
  );
}

export function checkFile(file: File | null, t: (k: "form.required" | "upload.tooBig" | "upload.badType") => string): string | undefined {
  if (!file) return t("form.required");
  if (file.size > MAX_UPLOAD_BYTES) return t("upload.tooBig");
  if (!ACCEPT.split(",").includes(file.type)) return t("upload.badType");
  return undefined;
}
