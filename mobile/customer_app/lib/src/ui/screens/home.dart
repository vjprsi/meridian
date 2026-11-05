import 'package:flutter/material.dart';

import '../../api/customer_api.dart';
import '../../api/models.dart';
import '../../core/money.dart';
import '../widgets.dart';
import 'account_detail.dart';
import 'cards.dart';
import 'loans.dart';
import 'wallet.dart';

/// Everything the home screen shows, loaded together (balances in parallel).
class HomeSummary {
  HomeSummary({
    this.customer,
    required this.accounts,
    required this.accountBalances,
    required this.wallets,
    required this.walletBalances,
    required this.cards,
    required this.loans,
  });

  final Customer? customer;
  final List<DepositAccount> accounts;
  final Map<String, Balance> accountBalances;
  final List<Wallet> wallets;
  final Map<String, Balance> walletBalances;
  final List<PaymentCard> cards;
  final List<Loan> loans;

  static Future<HomeSummary> load(CustomerApi api) async {
    Future<T?> soft<T>(Future<T> f) => f.then<T?>((v) => v).catchError((Object _) => null);
    final profile = soft(api.profile());
    final accounts = api.accounts();
    final wallets = soft(api.wallets());
    final cards = soft(api.cards());
    final loans = soft(api.loans());
    final acc = await accounts;
    final wal = await wallets ?? const <Wallet>[];
    final accBal = await Future.wait(acc.map((a) => soft(api.accountBalance(a.id))));
    final walBal = await Future.wait(wal.map((w) => soft(api.walletBalance(w.id))));
    return HomeSummary(
      customer: await profile,
      accounts: acc,
      accountBalances: {
        for (var i = 0; i < acc.length; i++)
          if (accBal[i] != null) acc[i].id: accBal[i]!,
      },
      wallets: wal,
      walletBalances: {
        for (var i = 0; i < wal.length; i++)
          if (walBal[i] != null) wal[i].id: walBal[i]!,
      },
      cards: await cards ?? const [],
      loans: (await loans ?? const <Loan>[]).where((l) => l.status != 'CLOSED' && l.status != 'CANCELLED').toList(),
    );
  }
}

class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key, this.onMoveMoney});
  final VoidCallback? onMoveMoney;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final api = context.services.api;
    return Scaffold(
      appBar: AppBar(title: Text(l.appTitle)),
      floatingActionButton: onMoveMoney == null
          ? null
          : FloatingActionButton.extended(
              onPressed: onMoveMoney, icon: const Icon(Icons.swap_horiz), label: Text(l.moveMoney)),
      body: Loader<HomeSummary>(
        load: () => HomeSummary.load(api),
        builder: (context, s, reload) => RefreshIndicator(
          onRefresh: reload,
          child: ListView(padding: const EdgeInsets.only(bottom: 96), children: [
            if (s.customer != null)
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
                child: Text(l.greeting(s.customer!.name), style: Theme.of(context).textTheme.headlineSmall),
              ),
            SectionHeader(l.accounts),
            if (s.accounts.isEmpty) EmptyPanel(text: l.empty),
            for (final a in s.accounts)
              Card(
                child: ListTile(
                  key: Key('account-${a.id}'),
                  leading: const Icon(Icons.account_balance_outlined),
                  title: Text(a.label),
                  subtitle: Text('${a.accountNumber} · ${a.accountType.toLowerCase()}'),
                  trailing: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.end, children: [
                    MoneyText(s.accountBalances[a.id]?.available, a.currency,
                        style: Theme.of(context).textTheme.titleMedium),
                    Text(l.available, style: Theme.of(context).textTheme.bodySmall),
                  ]),
                  onTap: () => Navigator.push(context,
                      MaterialPageRoute(builder: (_) => AccountDetailScreen.account(a))),
                ),
              ),
            if (s.wallets.isNotEmpty) ...[
              SectionHeader(l.wallet),
              for (final w in s.wallets)
                Card(
                  child: ListTile(
                    leading: const Icon(Icons.account_balance_wallet_outlined),
                    title: Text(w.displayName),
                    subtitle: Text(w.walletNumber),
                    trailing: MoneyText(s.walletBalances[w.id]?.available, w.currency,
                        style: Theme.of(context).textTheme.titleMedium),
                    onTap: () =>
                        Navigator.push(context, MaterialPageRoute(builder: (_) => WalletHomeScreen(wallet: w))),
                  ),
                ),
              WalletActions(wallet: s.wallets.first),
            ],
            if (s.cards.isNotEmpty) ...[
              SectionHeader(l.cards),
              for (final c in s.cards.where((c) => c.usable))
                Card(
                  child: ListTile(
                    leading: Icon(c.frozen ? Icons.ac_unit : Icons.credit_card),
                    title: Text(c.nickname ?? '•••• ${c.last4}'),
                    subtitle: Text('${c.formFactor.toLowerCase()} · ${c.expiry}'),
                    trailing: StatusChip(c.status),
                    onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => CardDetailScreen(cardId: c.id))),
                  ),
                ),
            ],
            if (s.loans.isNotEmpty) ...[
              SectionHeader(l.loans),
              for (final ln in s.loans)
                Card(
                  child: ListTile(
                    leading: const Icon(Icons.request_quote_outlined),
                    title: Text('${ln.loanType.toLowerCase()} · ${ln.loanNumber}'),
                    subtitle: ln.nextDueDate == null ? null : Text(l.nextDue(ln.nextDueDate!)),
                    trailing: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.end, children: [
                      MoneyText(ln.totalOutstanding, ln.currency, style: Theme.of(context).textTheme.titleMedium),
                      Text(Money.compare(ln.overdueAmount, '0') > 0 ? l.overdue : l.outstanding,
                          style: Theme.of(context).textTheme.bodySmall),
                    ]),
                    onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => LoanDetailScreen(loanId: ln.id))),
                  ),
                ),
            ],
          ]),
        ),
      ),
    );
  }
}
