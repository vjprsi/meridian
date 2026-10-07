import 'package:flutter/material.dart';

import '../../api/models.dart';
import '../widgets.dart';

/// One transaction row, common to account ledger entries and wallet transactions.
class TxnRow {
  const TxnRow({required this.title, required this.subtitle, required this.amount, required this.currency,
      required this.credit, this.status});
  final String title;
  final String subtitle;
  final String amount;
  final String currency;
  final bool credit;
  final String? status;
}

/// Balance and transactions of one deposit account or wallet, with cursor paging.
class AccountDetailScreen extends StatefulWidget {
  AccountDetailScreen.account(DepositAccount a, {super.key})
      : id = a.id,
        title = a.label,
        subtitle = a.iban ?? a.accountNumber,
        currency = a.currency,
        isWallet = false;

  AccountDetailScreen.wallet(Wallet w, {super.key})
      : id = w.id,
        title = w.displayName,
        subtitle = w.walletNumber,
        currency = w.currency,
        isWallet = true;

  final String id;
  final String title;
  final String subtitle;
  final String currency;
  final bool isWallet;

  @override
  State<AccountDetailScreen> createState() => _AccountDetailScreenState();
}

class _AccountDetailScreenState extends State<AccountDetailScreen> {
  Balance? _balance;
  final _rows = <TxnRow>[];
  String? _cursor;
  bool _more = false;
  bool _loading = true;
  Object? _error;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_loading && _rows.isEmpty && _error == null && _balance == null) _load(first: true);
  }

  Future<void> _load({bool first = false}) async {
    final api = context.services.api;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      if (first) {
        _balance = widget.isWallet ? await api.walletBalance(widget.id) : await api.accountBalance(widget.id);
        _rows.clear();
        _cursor = null;
      }
      if (widget.isWallet) {
        final p = await api.walletTransactions(widget.id, cursor: _cursor);
        _rows.addAll(p.data.map((t) => TxnRow(
            title: t.payeeName ?? t.description ?? t.txnType.replaceAll('_', ' ').toLowerCase(),
            subtitle: '${shortDate(context, t.createdAt)} · ${t.reference}',
            amount: t.amount,
            currency: t.currency,
            credit: t.payerWalletId != widget.id,
            status: t.status == 'COMPLETED' ? null : t.status)));
        _cursor = p.nextCursor;
        _more = p.hasMore;
      } else {
        final p = await api.accountTransactions(widget.id, cursor: _cursor);
        _rows.addAll(p.data.map((e) => TxnRow(
            title: e.title,
            subtitle: shortDate(context, e.effectiveAt),
            amount: e.amount,
            currency: e.currency,
            credit: e.credit)));
        _cursor = p.nextCursor;
        _more = p.hasMore;
      }
    } catch (e) {
      _error = e;
    }
    if (mounted) setState(() => _loading = false);
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(widget.title)),
      body: RefreshIndicator(
        onRefresh: () => _load(first: true),
        child: ListView(children: [
          Padding(
            padding: const EdgeInsets.all(16),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(widget.subtitle, style: theme.textTheme.bodyMedium),
              const SizedBox(height: 8),
              MoneyText(_balance?.available, widget.currency, style: theme.textTheme.headlineMedium),
              Text(l.available, style: theme.textTheme.bodySmall),
            ]),
          ),
          SectionHeader(l.transactions),
          if (_error != null) ErrorPanel(error: _error!, onRetry: () => _load(first: _balance == null)),
          if (!_loading && _error == null && _rows.isEmpty) EmptyPanel(text: l.noTransactions),
          for (final r in _rows)
            ListTile(
              leading: CircleAvatar(child: Icon(r.credit ? Icons.south_west : Icons.north_east, size: 18)),
              title: Text(r.title, maxLines: 1, overflow: TextOverflow.ellipsis),
              subtitle: Text(r.subtitle),
              trailing: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.end, children: [
                MoneyText(r.amount, r.currency, signed: true, credit: r.credit),
                if (r.status != null) Text(r.status!.toLowerCase(), style: theme.textTheme.bodySmall),
              ]),
            ),
          if (_loading) const Padding(padding: EdgeInsets.all(16), child: Center(child: CircularProgressIndicator())),
          if (!_loading && _more)
            Center(child: TextButton(onPressed: _load, child: Text(l.loadMore))),
          const SizedBox(height: 24),
        ]),
      ),
    );
  }
}
