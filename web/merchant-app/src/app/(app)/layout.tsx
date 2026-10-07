import { Suspense } from "react";
import { Shell } from "@/components/shell";

/** Signed-in pages read the URL and session, so they render inside Suspense under Cache Components. */
export default function AppLayout({ children }: { children: React.ReactNode }) {
  return (
    <Suspense fallback={<p role="status" className="p-8 text-sm text-muted">Loading…</p>}>
      <Shell>{children}</Shell>
    </Suspense>
  );
}
