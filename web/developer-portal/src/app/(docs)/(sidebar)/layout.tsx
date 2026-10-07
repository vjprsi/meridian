import { Suspense } from "react";
import { DocsSidebar } from "@/components/site";

/** Guides, reference and events share the documentation sidebar. */
export default function SidebarLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="mx-auto flex max-w-[90rem]">
      <Suspense fallback={<div className="hidden w-60 shrink-0 lg:block" />}>
        <DocsSidebar />
      </Suspense>
      <main className="min-w-0 flex-1 px-4 py-8 md:px-8">
        <Suspense fallback={<p className="text-sm text-muted">Loading…</p>}>{children}</Suspense>
      </main>
    </div>
  );
}
