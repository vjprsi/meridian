import Link from "next/link";
import type { Metadata } from "next";
import { PageHeader } from "@/components/ui";
import { GUIDES } from "@/lib/guides";

export const metadata: Metadata = { title: "Guides" };

export default function DocsIndex() {
  return (
    <>
      <PageHeader title="Guides" subtitle="Everything you need before your first production call." />
      <div className="grid max-w-4xl gap-3 sm:grid-cols-2">
        {GUIDES.map((g, i) => (
          <Link key={g.slug} href={`/docs/guides/${g.slug}`} className="rounded-[var(--radius)] border border-border bg-surface p-4 hover:border-accent">
            <p className="text-xs text-muted">{String(i + 1).padStart(2, "0")}</p>
            <h2 className="font-semibold">{g.title}</h2>
            <p className="mt-1 text-sm text-muted">{g.blurb}</p>
          </Link>
        ))}
      </div>
    </>
  );
}
