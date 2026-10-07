import Link from "next/link";
import type { ReactNode } from "react";
import { GUIDES, type GuideSlug } from "@/lib/guides";

/** Long-form guide page: title, lead paragraph, prose body and previous / next links. Server component. */
export function Guide({ slug, lead, children }: { slug: GuideSlug; lead: ReactNode; children: ReactNode }) {
  const i = GUIDES.findIndex((g) => g.slug === slug);
  const prev = GUIDES[i - 1];
  const next = GUIDES[i + 1];
  return (
    <article className="prose">
      <p className="!mb-1 text-xs text-muted"><Link href="/docs" className="!text-muted !no-underline hover:!text-text">Guides</Link></p>
      <h1>{GUIDES[i].title}</h1>
      <p className="text-muted">{lead}</p>
      {children}
      <nav className="mt-12 flex justify-between gap-4 border-t border-border pt-4 text-sm not-prose">
        {prev ? <Link href={`/docs/guides/${prev.slug}`} className="!no-underline">← {prev.title}</Link> : <span />}
        {next ? <Link href={`/docs/guides/${next.slug}`} className="!no-underline">{next.title} →</Link> : <span />}
      </nav>
    </article>
  );
}
