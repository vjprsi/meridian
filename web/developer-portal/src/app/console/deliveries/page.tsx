"use client";

import { DeliveryLog } from "@/components/webhooks";
import { PageHeader } from "@/components/ui";

export default function DeliveriesPage() {
  return (
    <>
      <PageHeader title="Delivery log" subtitle="Every webhook delivery across your endpoints: status, HTTP attempts, payload and manual retry." />
      <DeliveryLog />
    </>
  );
}
