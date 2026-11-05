import 'package:flutter/material.dart';

import '../../api/customer_api.dart';
import '../../api/models.dart';
import '../../core/ids.dart';
import '../widgets.dart';
import 'airtime.dart';
import 'bill_pay.dart';
import 'merchant_pay.dart';
import 'qr.dart';
import 'request_money.dart';
import 'send_money.dart';

/// Everything the wallet home shows, loaded together.
class WalletSummary {
  WalletSummary({required this.balance, required this.transactions});
  final Balance? balance;
  final List<WalletTransaction> transactions;

  static Future<WalletSummary> load(CustomerApi api, String walletId) async {
    Future<T?> soft<T>(Future<T> f) => f.then<T?>((v) => v).catchError((Object _) => null);
    final balance = soft(api.walletBalance(walletId));
    final txns = soft(api.walletTransactions(walletId));
    return WalletSummary(balance: await balance, transactions: (await txns)?.data ?? const []);
  }
}

/// Wallet home: balance, the Send / Request / Top up / Pay bill / Scan QR action row and recent transactions.
/// Reachable from the Home wallet section (with a wallet) and from More (loads the default wallet itself).
class WalletHomeScreen extends StatefulWidget {
  const WalletHomeScreen({super.key, this.wallet});
  final Wallet? wallet;

  @override
  State<WalletHomeScreen> createState() => _WalletHomeScreenState();
}

class _WalletHomeScreenState extends State<WalletHomeScreen> {
  Wallet? _selected;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final api = context.services.api;
    return Scaffold(
      appBar: AppBar(title: Text(l.wallet)),
      body: Loader<List<Wallet>>(
        load: () async => widget.wallet != null ? [widget.wallet!] : await api.wallets(),
        isEmpty: (w) => w.isEmpty,
        builder: (context, wallets, _) {
          final wallet = _selected ??
              (widget.wallet ??
                  wallets.firstWhere((w) => w.isDefault, orElse: () => wallets.first));
          return _WalletBody(
            key: ValueKey(wallet.id),
            wallet: wallet,
            allWallets: wallets,
            onSwitch: (w) => setState(() => _selected = w),
          );
        },
      ),
    );
  }
}

class _WalletBody extends StatelessWidget {
  const _WalletBody({super.key, required this.wallet, required this.allWallets, required this.onSwitch});
  final Wallet wallet;
  final List<Wallet> allWallets;
  final ValueChanged<Wallet> onSwitch;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final theme = Theme.of(context);
    final api = context.services.api;
    return Loader<WalletSummary>(
      load: () => WalletSummary.load(api, wallet.id),
      builder: (context, s, reload) => RefreshIndicator(
        onRefresh: reload,
        child: ListView(padding: const EdgeInsets.only(bottom: 24), children: [
          Padding(
            padding: const EdgeInsets.all(16),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              if (allWallets.length > 1)
                DropdownButton<String>(
                  value: wallet.id,
                  underline: const SizedBox.shrink(),
                  items: [
                    for (final w in allWallets)
                      DropdownMenuItem(value: w.id, child: Text('${w.displayName} · ${w.walletNumber}')),
                  ],
                  onChanged: (v) {
                    if (v != null) onSwitch(allWallets.firstWhere((w) => w.id == v));
                  },
                )
              else
                Text('${wallet.displayName} · ${wallet.walletNumber}', style: theme.textTheme.bodyMedium),
              const SizedBox(height: 8),
              MoneyText(s.balance?.available, wallet.currency, style: theme.textTheme.headlineMedium),
              Text(l.available, style: theme.textTheme.bodySmall),
            ]),
          ),
          WalletActions(wallet: wallet),
          SectionHeader(l.recent),
          if (s.transactions.isEmpty) EmptyPanel(text: l.noTransactions),
          for (final t in s.transactions)
            ListTile(
              leading: CircleAvatar(
                  child: Icon(t.payerWalletId == wallet.id ? Icons.north_east : Icons.south_west, size: 18)),
              title: Text(t.payeeName ?? t.description ?? t.txnType.replaceAll('_', ' ').toLowerCase(),
                  maxLines: 1, overflow: TextOverflow.ellipsis),
              subtitle: Text('${shortDate(context, t.createdAt)} · ${t.reference}'),
              trailing: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  MoneyText(t.amount, t.currency, signed: true, credit: t.payerWalletId != wallet.id),
                  if (t.status != 'COMPLETED') Text(t.status.toLowerCase(), style: theme.textTheme.bodySmall),
                ],
              ),
            ),
        ]),
      ),
    );
  }
}

/// The wallet action row: one tap into each money-movement flow.
class WalletActions extends StatelessWidget {
  const WalletActions({super.key, required this.wallet});
  final Wallet wallet;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    void open(Widget w) => Navigator.push(context, MaterialPageRoute(builder: (_) => w));
    final actions = <(IconData, String, VoidCallback)>[
      (Icons.send, l.send, () => open(SendMoneyScreen(wallet: wallet))),
      (Icons.request_page_outlined, l.request, () => open(RequestMoneyScreen(wallet: wallet))),
      (Icons.phone_android, l.topUp, () => open(AirtimeOperatorsScreen(wallet: wallet))),
      (Icons.receipt_long_outlined, l.payBill, () => open(BillersScreen(wallet: wallet))),
      (Icons.qr_code_scanner, l.scanQr, () => open(ScanToPayScreen(wallet: wallet))),
      (Icons.storefront_outlined, l.payMerchant, () => open(MerchantPayScreen(wallet: wallet))),
      (Icons.qr_code_2, l.myQr, () => open(MyQrScreen(wallet: wallet))),
      (Icons.add_card, l.topUpWallet, () => open(WalletBankTransferScreen(wallet: wallet, toBank: false))),
      (Icons.account_balance_outlined, l.toBank, () => open(WalletBankTransferScreen(wallet: wallet, toBank: true))),
    ];
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8),
      child: Wrap(
        alignment: WrapAlignment.start,
        children: [
          for (final (icon, label, onTap) in actions)
            SizedBox(
              width: 88,
              child: InkWell(
                key: Key('walletAction-$label'),
                borderRadius: BorderRadius.circular(12),
                onTap: onTap,
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 10),
                  child: Column(children: [
                    CircleAvatar(
                      radius: 22,
                      backgroundColor: Theme.of(context).colorScheme.secondaryContainer,
                      child: Icon(icon, color: Theme.of(context).colorScheme.onSecondaryContainer),
                    ),
                    const SizedBox(height: 6),
                    Text(label, textAlign: TextAlign.center, style: Theme.of(context).textTheme.labelSmall),
                  ]),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

/// Shared confirmation sheet for wallet money movement: headline amount + labelled rows + warnings.
Future<bool> walletConfirmSheet(
  BuildContext context, {
  required String title,
  required String amount,
  required String currency,
  required List<(String, String)> rows,
  List<String> warnings = const [],
}) async {
  final l = context.l10n;
  final ok = await showModalBottomSheet<bool>(
    context: context,
    showDragHandle: true,
    isScrollControlled: true,
    builder: (c) => SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Text(title, style: Theme.of(c).textTheme.titleLarge),
          const SizedBox(height: 8),
          Center(child: MoneyText(amount, currency, style: Theme.of(c).textTheme.headlineMedium)),
          for (final (label, value) in rows) ListTile(title: Text(label), trailing: Text(value)),
          for (final w in warnings)
            ListTile(leading: const Icon(Icons.warning_amber_outlined), title: Text(w)),
          const SizedBox(height: 8),
          FilledButton(key: const Key('confirmWallet'), onPressed: () => Navigator.pop(c, true), child: Text(l.confirm)),
          TextButton(onPressed: () => Navigator.pop(c, false), child: Text(l.cancel)),
        ]),
      ),
    ),
  );
  return ok ?? false;
}

/// Add money to the wallet from a bank account (from-bank) or send wallet money to a bank account (to-bank).
class WalletBankTransferScreen extends StatefulWidget {
  const WalletBankTransferScreen({super.key, required this.wallet, required this.toBank});
  final Wallet wallet;
  final bool toBank;

  @override
  State<WalletBankTransferScreen> createState() => _WalletBankTransferScreenState();
}

class _WalletBankTransferScreenState extends State<WalletBankTransferScreen> {
  final _form = GlobalKey<FormState>();
  final _amount = TextEditingController();
  final _reference = TextEditingController();
  String? _accountId;
  bool _busy = false;

  @override
  void dispose() {
    _amount.dispose();
    _reference.dispose();
    super.dispose();
  }

  Future<void> _submit(List<DepositAccount> accounts) async {
    if (!(_form.currentState?.validate() ?? false)) return;
    final l = context.l10n;
    final api = context.services.api;
    final account = accounts.firstWhere((a) => a.id == _accountId);
    final amount = _amount.text.trim();
    final ok = await walletConfirmSheet(context,
        title: widget.toBank ? l.transferToBank : l.addMoneyToWallet,
        amount: amount,
        currency: widget.wallet.currency,
        rows: [
          if (widget.toBank) (l.fromWallet, widget.wallet.displayName) else (l.fromAccount, account.label),
          if (widget.toBank) (l.toAccount, account.label) else (l.toWallet, widget.wallet.displayName),
        ]);
    if (!ok || !mounted) return;
    final key = uuidV4();
    setState(() => _busy = true);
    final r = await runWithStepUp(
      context,
      () => widget.toBank
          ? api.walletToBank(widget.wallet.id,
              beneficiaryAccountId: account.id, amount: amount, reference: _reference.text.trim(), idempotencyKey: key)
          : api.walletFromBank(widget.wallet.id, sourceAccountId: account.id, amount: amount, idempotencyKey: key),
    );
    if (!mounted) return;
    setState(() => _busy = false);
    if (r != null) {
      showMessage(context, l.paymentSent);
      Navigator.pop(context);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final api = context.services.api;
    return Scaffold(
      appBar: AppBar(title: Text(widget.toBank ? l.transferToBank : l.addMoneyToWallet)),
      body: Loader<List<DepositAccount>>(
        load: () async => (await api.accounts()).where((a) => a.active).toList(),
        isEmpty: (a) => a.isEmpty,
        builder: (context, accounts, reload) {
          _accountId ??= accounts.first.id;
          return Form(
            key: _form,
            child: ListView(padding: const EdgeInsets.all(16), children: [
              DropdownButtonFormField<String>(
                initialValue: _accountId,
                decoration: InputDecoration(labelText: widget.toBank ? l.toAccount : l.fromAccount),
                items: [
                  for (final a in accounts) DropdownMenuItem(value: a.id, child: Text('${a.label} (${a.currency})')),
                ],
                onChanged: (v) => setState(() => _accountId = v),
                validator: requiredValidator(context),
              ),
              const SizedBox(height: 16),
              TextFormField(
                key: const Key('amount'),
                controller: _amount,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: InputDecoration(labelText: l.amount),
                validator: amountValidator(context),
              ),
              if (widget.toBank) ...[
                const SizedBox(height: 16),
                TextFormField(controller: _reference, maxLength: 140, decoration: InputDecoration(labelText: l.reference)),
              ],
              const SizedBox(height: 8),
              FilledButton(
                onPressed: _busy ? null : () => _submit(accounts),
                child: Text(l.confirm),
              ),
            ]),
          );
        },
      ),
    );
  }
}
