"use client";

import { useState } from "react";
import { accountLabel, useAccounts } from "@/api/business";
import { ExportForm, Statements } from "@/components/account-files";
import { Async, Card, Empty, PageHeader, Select } from "@/components/ui";

export default function StatementsPage() {
  const accounts = useAccounts();
  const [selected, setSelected] = useState("");
  const account = accounts.data?.find((a) => a.id === selected) ?? accounts.data?.[0];
  return (
    <>
      <PageHeader title="Statements & export" subtitle="Statements and accounting exports (CSV, ISO 20022 camt.053) per account" />
      <Async q={accounts} empty={<Empty title="No accounts visible" />}>
        {(rows) => (
          <>
            <div className="mb-5 max-w-md">
              <label className="flex flex-col gap-1.5 text-sm">
                <span className="font-medium">Account</span>
                <Select value={account?.id ?? ""} onChange={(e) => setSelected(e.target.value)}
                  options={rows.map((a) => ({ value: a.id, label: accountLabel(a) }))} />
              </label>
            </div>
            {account && (
              <div className="grid gap-5">
                <Card title="Accounting export"><ExportForm key={`x-${account.id}`} account={account} /></Card>
                <Card title="Statements"><Statements key={`s-${account.id}`} account={account} /></Card>
              </div>
            )}
          </>
        )}
      </Async>
    </>
  );
}
