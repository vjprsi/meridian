"use client";

import Link from "next/link";
import { useMyLoans } from "@/api/me";
import { Async, Badge, Card, DateTime, Empty, Money, PageHeader } from "@/components/ui";
import { useT } from "@/lib/i18n";

export default function Loans() {
  const t = useT();
  const loansQ = useMyLoans();
  return (
    <>
      <PageHeader title={t("nav.loans")} subtitle={t("loans.subtitle")} />
      <Card>
        <Async q={loansQ} empty={<Empty title={t("loans.none")} />}>
          {(list) => (
            <ul className="flex flex-col gap-2">
              {list.map((l) => (
                <li key={l.id}>
                  <Link href={`/loans/${l.id}`} className="flex min-h-16 flex-wrap items-center justify-between gap-3 rounded-[var(--radius)] border border-border px-4 py-3 hover:border-accent focus-visible:outline-3 focus-visible:outline-focus">
                    <span>
                      <span className="block font-medium">{l.loan_number} · {l.loan_type.toLowerCase().replaceAll("_", " ")}</span>
                      <span className="block text-xs text-muted">
                        {t("loans.principal")} <Money amount={l.principal_amount} currency={l.currency} />
                        {l.maturity_date && <> · {t("loans.matures")} <DateTime value={l.maturity_date} dateOnly /></>}
                      </span>
                    </span>
                    <span className="text-right">
                      <Money amount={l.total_outstanding} currency={l.currency} className="block font-semibold" />
                      <span className="flex justify-end gap-1"><Badge value={l.status} />
                        {Number(l.overdue_amount ?? 0) > 0 && <Badge value="OVERDUE" />}</span>
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </Async>
      </Card>
    </>
  );
}
