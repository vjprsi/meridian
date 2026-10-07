"use client";

import { useParams } from "next/navigation";
import { useT } from "@/lib/i18n";
import { SaleView } from "@/components/sales";
import { PageHeader } from "@/components/ui";

export default function SalePage() {
  const { id } = useParams<{ id: string }>();
  const t = useT();
  return (
    <>
      <PageHeader title={t("sale")} back={{ href: "/sales", label: t("navSales") }} />
      <SaleView saleId={id} />
    </>
  );
}
