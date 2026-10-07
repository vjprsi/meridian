import { Suspense } from "react";
import { ConsoleShell } from "@/components/console";

/** Authenticated developer console; pages read the URL, so they render inside Suspense under Cache Components. */
export default function ConsoleLayout({ children }: { children: React.ReactNode }) {
  return (
    <Suspense fallback={<p className="p-8 text-sm text-muted">Loading…</p>}>
      <ConsoleShell>{children}</ConsoleShell>
    </Suspense>
  );
}
