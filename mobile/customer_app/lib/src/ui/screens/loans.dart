import 'package:flutter/material.dart';

import '../../api/models.dart';
import '../../core/ids.dart';
import '../../core/money.dart';
import '../widgets.dart';

class LoansScreen extends StatelessWidget {
  const LoansScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    return Scaffold(
      appBar: AppBar(title: Text(l.loans)),
      body: Loader<List<Loan>>(
        load: context.services.api.loans,
        isEmpty: (d) => d.isEmpty,
        builder: (context, loans, reload) => RefreshIndicator(
          onRefresh: reload,
          child: ListView(children: [
            for (final ln in loans)
              Card(
                child: ListTile(
                  title: Text('${ln.loanType.toLowerCase()} · ${ln.loanNumber}'),
                  subtitle: Text(ln.nextDueDate == null ? ln.status.toLowerCase() : l.nextDue(ln.nextDueDate!)),
                  trailing: MoneyText(ln.totalOutstanding, ln.currency),
                  onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => LoanDetailScreen(loanId: ln.id))),
                ),
              ),
          ]),
        ),
      ),
    );
  }
}

/// One loan: summary, repayment schedule, repayments, repay from an own account (step-up).
class LoanDetailScreen extends StatefulWidget {
  const LoanDetailScreen({super.key, required this.loanId});
  final String loanId;

  @override
  State<LoanDetailScreen> createState() => _LoanDetailScreenState();
}

class _LoanDetailScreenState extends State<LoanDetailScreen> {
  Key _reload = UniqueKey();

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final api = context.services.api;
    final id = widget.loanId;
    return DefaultTabController(
      length: 2,
      child: Scaffold(
        appBar: AppBar(
          title: Text(l.loans),
          bottom: TabBar(tabs: [Tab(text: l.schedule), Tab(text: l.repayments)]),
        ),
        body: Loader<(Loan, List<ScheduleInstallment>, List<LoanRepayment>)>(
          key: _reload,
          load: () async {
            final r = await Future.wait([api.loan(id), api.loanSchedule(id), api.loanRepayments(id)]);
            return (r[0] as Loan, r[1] as List<ScheduleInstallment>, r[2] as List<LoanRepayment>);
          },
          builder: (context, d, reload) {
            final (loan, schedule, repayments) = d;
            final theme = Theme.of(context);
            return Column(children: [
              Padding(
                padding: const EdgeInsets.all(16),
                child: Row(children: [
                  Expanded(
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Text(l.outstanding, style: theme.textTheme.bodySmall),
                      MoneyText(loan.totalOutstanding, loan.currency, style: theme.textTheme.headlineSmall),
                      if (Money.compare(loan.overdueAmount, '0') > 0)
                        Text('${l.overdue}: ${Money.format(loan.overdueAmount, loan.currency)}',
                            style: TextStyle(color: theme.colorScheme.error)),
                    ]),
                  ),
                  FilledButton(
                    key: const Key('repay'),
                    onPressed: loan.repayable ? () => _repay(context, loan) : null,
                    child: Text(l.repay),
                  ),
                ]),
              ),
              Expanded(
                child: TabBarView(children: [
                  ListView(children: [
                    for (final i in schedule)
                      ListTile(
                        leading: CircleAvatar(child: Text('${i.installmentNo}')),
                        title: Text(i.dueDate),
                        subtitle: Text(l.installment(i.installmentNo)),
                        trailing: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.end, children: [
                          MoneyText(i.totalDue, loan.currency),
                          Text(i.status.toLowerCase(), style: theme.textTheme.bodySmall),
                        ]),
                      ),
                  ]),
                  repayments.isEmpty
                      ? const EmptyPanel()
                      : ListView(children: [
                          for (final r in repayments)
                            ListTile(
                              title: Text(r.valueDate),
                              trailing: MoneyText(r.amount, r.currency),
                              subtitle: Text(r.status.toLowerCase()),
                            ),
                        ]),
                ]),
              ),
            ]);
          },
        ),
      ),
    );
  }

  Future<void> _repay(BuildContext context, Loan loan) async {
    final l = context.l10n;
    final api = context.services.api;
    final accounts = await runAction(context, api.accounts);
    if (accounts == null || !context.mounted) return;
    final usable = accounts.where((a) => a.active && a.currency == loan.currency).toList();
    final amount = TextEditingController(text: Money.compare(loan.overdueAmount, '0') > 0 ? loan.overdueAmount : '');
    final form = GlobalKey<FormState>();
    String? from = usable.isEmpty ? null : usable.first.id;
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: Text(l.repay),
        content: Form(
          key: form,
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            DropdownButtonFormField<String>(
              initialValue: from,
              decoration: InputDecoration(labelText: l.payFrom),
              items: [for (final a in usable) DropdownMenuItem(value: a.id, child: Text(a.label))],
              onChanged: (v) => from = v,
              validator: requiredValidator(context),
            ),
            const SizedBox(height: 12),
            TextFormField(
              key: const Key('repayAmount'),
              controller: amount,
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              decoration: InputDecoration(labelText: l.amount, suffixText: loan.currency),
              validator: amountValidator(context),
            ),
          ]),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c, false), child: Text(l.cancel)),
          FilledButton(
              onPressed: () {
                if (form.currentState?.validate() ?? false) Navigator.pop(c, true);
              },
              child: Text(l.confirm)),
        ],
      ),
    );
    if (ok != true || from == null || !context.mounted) return;
    final key = uuidV4(); // one key for this confirmation (and the retry after step-up)
    final r = await runWithStepUp(
        context, () => api.repayLoan(loan.id, amount: amount.text.trim(), sourceAccountId: from!, idempotencyKey: key),
        success: l.repaymentPosted);
    if (r != null) setState(() => _reload = UniqueKey());
  }
}
