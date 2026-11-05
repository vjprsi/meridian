import 'package:flutter/material.dart';

import '../../api/models.dart';
import '../../core/ids.dart';
import '../../core/money.dart';
import '../widgets.dart';
import 'wallet.dart';

/// Send money wallet-to-wallet (P2P): enter phone/alias -> lookup names the payee -> quote shows the fee ->
/// confirm under step-up. One Idempotency-Key is held for the whole confirmation.
class SendMoneyScreen extends StatefulWidget {
  const SendMoneyScreen({super.key, required this.wallet});
  final Wallet wallet;

  @override
  State<SendMoneyScreen> createState() => _SendMoneyScreenState();
}

class _SendMoneyScreenState extends State<SendMoneyScreen> {
  final _form = GlobalKey<FormState>();
  final _value = TextEditingController();
  final _amount = TextEditingController();
  final _note = TextEditingController();
  String _type = 'MSISDN';
  WalletPayee? _payee;
  bool _busy = false;

  @override
  void dispose() {
    _value.dispose();
    _amount.dispose();
    _note.dispose();
    super.dispose();
  }

  Future<void> _lookup() async {
    final v = _value.text.trim();
    if (v.isEmpty) return;
    setState(() => _busy = true);
    final payee = await runAction(context, () => context.services.api.walletLookup(type: _type, value: v));
    if (!mounted) return;
    setState(() {
      _payee = payee;
      _busy = false;
    });
  }

  Future<void> _review() async {
    if (!(_form.currentState?.validate() ?? false)) return;
    final l = context.l10n;
    final api = context.services.api;
    final value = _value.text.trim();
    final amount = _amount.text.trim();
    setState(() => _busy = true);
    final quote = await runAction(context, () => api.p2pQuote(widget.wallet.id, type: _type, value: value, amount: amount));
    if (!mounted) return;
    setState(() => _busy = false);
    if (quote == null) return;
    final payeeName = _payee?.name ?? quote.payeeName ?? value;
    final locale = Localizations.localeOf(context).toLanguageTag();
    final ok = await walletConfirmSheet(context,
        title: l.sendMoney,
        amount: quote.amount,
        currency: quote.currency,
        rows: [
          (l.payee, payeeName),
          (l.recipient, value),
          if (quote.fee != null) (l.fee, Money.format(quote.fee, quote.currency, locale: locale)),
          if (quote.total != null) (l.total, Money.format(quote.total, quote.currency, locale: locale)),
        ]);
    if (!ok || !mounted) return;
    // One key for the whole confirmation: a resubmit or the retry after step-up cannot send twice.
    final key = uuidV4();
    setState(() => _busy = true);
    final txn = await runWithStepUp(
      context,
      () => api.p2pSend(widget.wallet.id,
          type: _type, value: value, amount: amount, note: _note.text.trim(), idempotencyKey: key),
    );
    if (!mounted) return;
    setState(() => _busy = false);
    if (txn != null) {
      showMessage(context, l.moneySent);
      Navigator.pop(context);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    return Scaffold(
      appBar: AppBar(title: Text(l.sendMoney)),
      body: Form(
        key: _form,
        child: ListView(padding: const EdgeInsets.all(16), children: [
          SegmentedButton<String>(
            segments: [
              ButtonSegment(value: 'MSISDN', label: Text(l.byPhone), icon: const Icon(Icons.phone_outlined)),
              ButtonSegment(value: 'WALLET_NUMBER', label: Text(l.byWallet), icon: const Icon(Icons.account_balance_wallet_outlined)),
            ],
            selected: {_type},
            onSelectionChanged: (s) => setState(() {
              _type = s.first;
              _payee = null;
            }),
          ),
          const SizedBox(height: 16),
          TextFormField(
            key: const Key('recipient'),
            controller: _value,
            keyboardType: _type == 'MSISDN' ? TextInputType.phone : TextInputType.text,
            decoration: InputDecoration(
              labelText: l.recipient,
              suffixIcon: TextButton(onPressed: _busy ? null : _lookup, child: Text(l.lookUp)),
            ),
            onChanged: (_) {
              if (_payee != null) setState(() => _payee = null);
            },
            validator: requiredValidator(context),
          ),
          if (_payee != null)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Card(
                child: ListTile(
                  leading: const Icon(Icons.verified_user_outlined),
                  title: Text(_payee!.name),
                  subtitle: Text(_payee!.maskedValue ?? _value.text.trim()),
                ),
              ),
            ),
          const SizedBox(height: 16),
          TextFormField(
            key: const Key('amount'),
            controller: _amount,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: InputDecoration(labelText: l.amount),
            validator: amountValidator(context),
          ),
          const SizedBox(height: 16),
          TextFormField(controller: _note, maxLength: 140, decoration: InputDecoration(labelText: l.note)),
          const SizedBox(height: 8),
          FilledButton(key: const Key('review'), onPressed: _busy ? null : _review, child: Text(l.review)),
        ]),
      ),
    );
  }
}
