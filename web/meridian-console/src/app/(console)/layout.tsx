import { Suspense } from "react";
import { Shell } from "@/components/shell";

/** Console pages read the URL (pathname, params), so they render inside Suspense under Cache Components. */
export default function ConsoleLayout({ children }: { children: React.ReactNode }) {
  return (
    <Suspense fallback={<p className="p-8 text-sm text-muted">Loading…</p>}>
      <Shell>{children}</Shell>
    </Suspense>
  );
}
