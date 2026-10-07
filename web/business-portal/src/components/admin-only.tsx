"use client";

import type { ReactNode } from "react";
import { usePermissions } from "@/api/business";
import { Loading, Notice } from "./ui";

/** Administration screens: shown to ADMIN users only (the server enforces the same and answers 403 role_not_allowed). */
export function AdminOnly({ children }: { children: ReactNode }) {
  const perms = usePermissions();
  if (!perms.loaded) return <Loading rows={3} />;
  if (!perms.isAdmin) return <Notice tone="warn">You are not entitled to do this: administration is for ADMIN users of your business.</Notice>;
  return <>{children}</>;
}
