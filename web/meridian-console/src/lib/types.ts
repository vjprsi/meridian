import type { components } from "@/api/schema";

/** API model types, generated from the bundled OpenAPI contract. */
type S = components["schemas"];
export type Ledger = S["Ledger"];
export type LedgerAccount = S["LedgerAccount"];
export type AccountBalance = S["AccountBalance"];
export type JournalEntry = S["JournalEntry"];
export type LedgerEntry = S["LedgerEntry"];
export type Customer = S["Customer"];
export type ApprovalRequest = S["ApprovalRequest"];
export type Product = S["Product"];
export type ProductVersion = S["ProductVersion"];
export type AuditEvent = S["AuditEvent"];

export interface Page<T> {
  data: T[];
  has_more: boolean;
  next_cursor?: string | null;
}
