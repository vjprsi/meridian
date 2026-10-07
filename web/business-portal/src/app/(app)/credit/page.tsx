"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { accountOptions, useAccounts, usePermissions } from "@/api/business";
import { api, idem, unwrap } from "@/api/client";
import type { CreditLine, Loan } from "@/api/types";
import { ActionButton } from "@/components/actions";
import { useHeldNotice } from "@/components/held";
import { Async, Badge, Button, Card, DateTime, Empty, Money, PageHeader, Table, Tabs } from "@/components/ui";

function LoanSchedule({ loan }: { loan: Loan }) {
  const q = useQuery({
    queryKey: ["loan-schedule", loan.id],
    queryFn: async () => unwrap(await api.GET("/v1/business/loans/{loan_id}/schedule",
      { params: { path: { loan_id: loan.id }, query: { limit: 200 } } })).data,
  });
  return (
    <Async q={q} empty={<Empty title="No schedule" />}>
      {(rows) => (
        <Table rows={rows} rowKey={(i) => i.id} caption="Repayment schedule" columns={[
          { header: "#", cell: (i) => i.installment_no },
          { header: "Due", cell: (i) => <DateTime value={i.due_date} dateOnly /> },
          { header: "Principal", align: "right", cell: (i) => <Money amount={i.principal_due} currency={loan.currency} /> },
          { header: "Interest", align: "right", cell: (i) => <Money amount={i.interest_due} currency={loan.currency} /> },
          { header: "Total due", align: "right", cell: (i) => <Money amount={i.total_due} currency={loan.currency} /> },
          { header: "Status", cell: (i) => <Badge value={i.status} /> },
        ]} />
      )}
    </Async>
  );
}

function Drawdowns({ line }: { line: CreditLine }) {
  const q = useQuery({
    queryKey: ["drawdowns", line.id],
    queryFn: async () => unwrap(await api.GET("/v1/business/credit-lines/{credit_line_id}/drawdowns",
      { params: { path: { credit_line_id: line.id }, query: { limit: 100 } } })).data,
  });
  return (
    <Async q={q} empty={<Empty title="No draws yet" />}>
      {(rows) => (
        <Table rows={rows} rowKey={(d) => d.id} caption="Draws" columns={[
          { header: "When", cell: (d) => <DateTime value={d.posted_at ?? d.created_at} /> },
          { header: "Type", cell: (d) => d.draw_type },
          { header: "Amount", align: "right", cell: (d) => <Money amount={d.amount} currency={d.currency} /> },
          { header: "Status", cell: (d) => <Badge value={d.status} /> },
        ]} />
      )}
    </Async>
  );
}

export default function CreditPage() {
  const perms = usePermissions();
  const accounts = useAccounts();
  const [tab, setTab] = useState<"loans" | "lines">("loans");
  const [openLoan, setOpenLoan] = useState<Loan | null>(null);
  const [openLine, setOpenLine] = useState<CreditLine | null>(null);
  const loans = useQuery({
    queryKey: ["loans"],
    queryFn: async () => unwrap(await api.GET("/v1/business/loans", { params: { query: { limit: 100 } } })).data,
  });
  const lines = useQuery({
    queryKey: ["credit-lines"], enabled: tab === "lines",
    queryFn: async () => unwrap(await api.GET("/v1/business/credit-lines", { params: { query: { limit: 100 } } })).data,
  });
  const source = (currency: string) => accountOptions(accounts.data, (a) => a.currency === currency && perms.initiateOn(a.id));
  const [held, onDone] = useHeldNotice();

  return (
    <>
      <PageHeader title="Loans & credit lines" subtitle="Business loans, working capital and overdraft facilities" />
      {held}
      <Tabs value={tab} onChange={setTab} tabs={[{ key: "loans", label: "Loans" }, { key: "lines", label: "Credit lines" }]} />
      {tab === "loans" ? (
        <Card flush>
          <Async q={loans} empty={<div className="p-4"><Empty title="No loans" /></div>}>
            {(rows) => (
              <Table rows={rows} rowKey={(l) => l.id} caption="Loans" columns={[
                { header: "Loan", cell: (l) => <span className="font-mono text-xs">{l.loan_number}</span> },
                { header: "Type", cell: (l) => l.loan_type },
                { header: "Principal", align: "right", cell: (l) => <Money amount={l.principal_amount} currency={l.currency} /> },
                { header: "Outstanding", align: "right", cell: (l) => <Money amount={l.total_outstanding} currency={l.currency} /> },
                { header: "Next due", cell: (l) => <DateTime value={l.next_due_date} dateOnly /> },
                { header: "Status", cell: (l) => <Badge value={l.status} /> },
                { header: "", align: "right", cell: (l) => (
                  <div className="flex justify-end gap-2">
                    <Button size="sm" variant="secondary" onClick={() => setOpenLoan(l)}>Schedule</Button>
                    {perms.canMake && (
                      <ActionButton label="Repay" variant="primary" title={`Repay ${l.loan_number}`} confirmLabel="Repay"
                        fields={[
                          { name: "source_account_id", label: "From account", type: "select", required: true, options: source(l.currency) },
                          { name: "amount", label: `Amount (${l.currency})`, type: "number", required: true },
                        ]}
                        run={async (v, key) => unwrap(await api.POST("/v1/business/loans/{loan_id}/repay", {
                          params: { path: { loan_id: l.id }, header: idem(key) }, body: { amount: v.amount, source_account_id: v.source_account_id } }))}
                        onDone={onDone} />
                    )}
                  </div>
                ) },
              ]} />
            )}
          </Async>
        </Card>
      ) : (
        <Card flush>
          <Async q={lines} empty={<div className="p-4"><Empty title="No credit lines" /></div>}>
            {(rows) => (
              <Table rows={rows} rowKey={(l) => l.id} caption="Credit lines" columns={[
                { header: "Line", cell: (l) => <span className="font-mono text-xs">{l.credit_line_number}</span> },
                { header: "Type", cell: (l) => l.line_type },
                { header: "Limit", align: "right", cell: (l) => <Money amount={l.credit_limit} currency={l.currency} /> },
                { header: "Used", align: "right", cell: (l) => <Money amount={l.utilised_amount} currency={l.currency} /> },
                { header: "Available", align: "right", cell: (l) => <Money amount={l.available_amount} currency={l.currency} /> },
                { header: "Status", cell: (l) => <Badge value={l.status} /> },
                { header: "", align: "right", cell: (l) => (
                  <div className="flex justify-end gap-2">
                    <Button size="sm" variant="secondary" onClick={() => setOpenLine(l)}>Draws</Button>
                    {perms.isAdmin && l.status === "ACTIVE" && (
                      <ActionButton label="Draw" variant="primary" title={`Draw from ${l.credit_line_number}`} confirmLabel="Draw funds"
                        fields={[
                          { name: "destination_account_id", label: "Into account", type: "select", required: true, options: source(l.currency) },
                          { name: "amount", label: `Amount (${l.currency})`, type: "number", required: true },
                        ]}
                        run={async (v, key) => unwrap(await api.POST("/v1/business/credit-lines/{credit_line_id}/draw", {
                          params: { path: { credit_line_id: l.id }, header: idem(key) },
                          body: { amount: v.amount, destination_account_id: v.destination_account_id } }))}
                        onDone={onDone} />
                    )}
                    {perms.canMake && (
                      <ActionButton label="Repay" title={`Repay ${l.credit_line_number}`} confirmLabel="Repay"
                        fields={[
                          { name: "source_account_id", label: "From account", type: "select", required: true, options: source(l.currency) },
                          { name: "amount", label: `Amount (${l.currency})`, type: "number", required: true },
                        ]}
                        run={async (v, key) => unwrap(await api.POST("/v1/business/credit-lines/{credit_line_id}/repay", {
                          params: { path: { credit_line_id: l.id }, header: idem(key) },
                          body: { amount: v.amount, source_account_id: v.source_account_id } }))}
                        onDone={onDone} />
                    )}
                  </div>
                ) },
              ]} />
            )}
          </Async>
        </Card>
      )}
      {tab === "loans" && openLoan && (
        <Card className="mt-5" title={`Schedule · ${openLoan.loan_number}`}
          actions={<Button size="sm" variant="ghost" onClick={() => setOpenLoan(null)}>Close</Button>}>
          <LoanSchedule loan={openLoan} />
        </Card>
      )}
      {tab === "lines" && openLine && (
        <Card className="mt-5" title={`Draws · ${openLine.credit_line_number}`}
          actions={<Button size="sm" variant="ghost" onClick={() => setOpenLine(null)}>Close</Button>}>
          <Drawdowns line={openLine} />
        </Card>
      )}
    </>
  );
}
