"use client";

import Link from "next/link";
import { useState, type ReactNode } from "react";
import { KIND_LABEL } from "./payments";
import { Button, Notice } from "./ui";

/**
 * Operations (loan / credit-line repayments, draws, mandates, collections, virtual accounts, payment links) that a rule
 * of the approval matrix covers are not executed at once: the business channel answers 202 with the business payment
 * holding them (status PENDING_APPROVAL). This shows that outcome instead of pretending the operation happened.
 */
export interface HeldOperation { id: string; kind: keyof typeof KIND_LABEL; status: string; required_approvals?: number }

export function heldForApproval(result: unknown): HeldOperation | null {
  const r = result as Partial<HeldOperation> | null | undefined;
  return r && typeof r.id === "string" && r.status === "PENDING_APPROVAL" && typeof r.kind === "string" && r.kind in KIND_LABEL
    ? (r as HeldOperation) : null;
}

/** [notice, onDone]: pass onDone to an ActionButton whose operation may be held for approval. */
export function useHeldNotice(): [ReactNode, (result: unknown) => void] {
  const [held, setHeld] = useState<HeldOperation | null>(null);
  const approvals = held?.required_approvals ?? 1;
  const notice = held && (
    <div className="mb-4" data-testid="held-for-approval">
      <Notice tone="warn">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <span>
            <strong>{KIND_LABEL[held.kind]} sent for approval.</strong> Your approval rules require {approvals}{" "}
            approval{approvals === 1 ? "" : "s"} before the bank executes it.
          </span>
          <span className="flex gap-2">
            <Link className="prose-link" href={`/payments/${held.id}`}>View approval</Link>
            <Button size="sm" variant="ghost" onClick={() => setHeld(null)}>Dismiss</Button>
          </span>
        </div>
      </Notice>
    </div>
  );
  return [notice, (result) => setHeld(heldForApproval(result))];
}
