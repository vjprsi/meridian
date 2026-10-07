"use client";

import { useEffect } from "react";
import { oidc } from "@/lib/auth";

/** Completes the step-up popup sign-in and hands the result back to the opener. */
export default function AuthPopup() {
  useEffect(() => {
    oidc().signinPopupCallback().catch(() => window.close());
  }, []);
  return <main id="main" className="p-6 text-sm text-muted" role="status">…</main>;
}
