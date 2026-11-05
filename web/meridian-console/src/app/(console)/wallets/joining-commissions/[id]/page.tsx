"use client";

import { Suspense } from "react";
import { Badge, Money, Mono } from "@/components/ui";
import { str, type Row } from "@/components/ops";
import { HierarchyDetail } from "../../../config/_hierarchy";

// WE-PRICE-1 (G-05): joining commission detail — same maker-checker lifecycle as the hierarchy config entities.
export default function Page() {
  return (
    <Suspense>
      <HierarchyDetail
        entity="Joining commission"
        navKey="joining-commissions"
        basePath="/v1/joining-commissions"
        detailItems={(r: Row) => [
          ["Code", <Mono key="c">{str(r.code)}</Mono>],
          ["Name", r.name],
          ["Beneficiary", str(r.beneficiary).replaceAll("_", " ")],
          ["Register amount", <Money key="ra" amount={r.register_amount as string} currency={r.currency as string} />],
          ["First cash-in", <Money key="fci" amount={r.first_cash_in_amount as string} currency={r.currency as string} />],
          ["First debit", <Money key="fd" amount={r.first_debit_amount as string} currency={r.currency as string} />],
          ["Currency", r.currency],
          ["Valid from", r.valid_from],
          ["Created", r.created_at],
          ["Status", <Badge key="s" value={str(r.status)} />],
        ]} />
    </Suspense>
  );
}
