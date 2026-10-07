import { Suspense } from "react";
import { SiteFooter, SiteHeader } from "@/components/site";

/** Public area (no sign-in): landing, guides, API reference, events, search. */
export default function DocsLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-screen flex-col">
      <Suspense fallback={<div className="h-[53px] border-b border-border bg-surface" />}>
        <SiteHeader />
      </Suspense>
      <div className="flex-1">{children}</div>
      <SiteFooter />
    </div>
  );
}
