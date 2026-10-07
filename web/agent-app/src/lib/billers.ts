"use client";

import { useQuery } from "@tanstack/react-query";
import { biller, unwrap, type Biller } from "@/api/client";

/**
 * Biller catalogue and products: reference data, fetched whenever possible and served from the service worker's
 * reference-data cache when offline (so bills and airtime can still be captured).
 */
export function useBillers() {
  return useQuery({
    queryKey: ["billers"],
    staleTime: 10 * 60_000,
    queryFn: async () => unwrap(await biller.GET("/v1/billers", { params: { query: { status: "ACTIVE", limit: 200 } } })).data,
  });
}

export function useBillerProducts(billerId: string | undefined) {
  return useQuery({
    queryKey: ["biller-products", billerId],
    enabled: !!billerId,
    staleTime: 10 * 60_000,
    queryFn: async () => unwrap(await biller.GET("/v1/billers/{id}/products", { params: { path: { id: billerId! } } })).data,
  });
}

function luhn(s: string): boolean {
  if (!/^\d+$/.test(s)) return false;
  let sum = 0;
  for (let i = 0; i < s.length; i++) {
    let d = Number(s[s.length - 1 - i]);
    if (i % 2 === 1) { d *= 2; if (d > 9) d -= 9; }
    sum += d;
  }
  return sum % 10 === 0;
}

/**
 * Local account-reference check (pattern and, where it can be computed on the device, the check digit). Returns
 * null when valid, else a reason. `offlineCapable` is false when only the biller can validate the reference.
 */
export function checkReference(b: Biller, ref: string): { error: string | null; offlineCapable: boolean } {
  const v = ref.trim();
  if (!v) return { error: "required", offlineCapable: false };
  if (b.account_reference_regex) {
    try {
      if (!new RegExp(b.account_reference_regex).test(v)) return { error: "pattern", offlineCapable: false };
    } catch { /* invalid pattern in the catalogue: leave it to the server */ }
  }
  if (b.account_reference_checksum === "LUHN") return { error: luhn(v) ? null : "checksum", offlineCapable: true };
  if (b.account_reference_checksum === "NONE") return { error: null, offlineCapable: true };
  return { error: null, offlineCapable: false };
}
