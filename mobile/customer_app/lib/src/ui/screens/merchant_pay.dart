import 'package:flutter/material.dart';

import '../../api/models.dart';
import '../../core/ids.dart';
import '../widgets.dart';
import 'wallet.dart';

/// Pay a merchant by till number: till + amount -> confirm (step-up). One Idempotency-Key per confirmation.
class MerchantPayScreen extends StatefulWidget {
  const MerchantPayScreen({super.key, required this.wallet});
  final Wallet wallet;

  @override
  State<MerchantPayScreen> createState() => _MerchantPayScreenState();
}

class _MerchantPayScreenState extends State<MerchantPayScreen> {
  final _form = GlobalKey<FormState>();
  final _till = TextEditingController();
  final _amount = TextEditingController();
  final _reference = TextEditingController();
  bool _busy = false;

  @override
  void dispose() {
    _till.dispose();
    _amount.dispose();
    _reference.dispose();
    super.dispose();
  }

  Future<void> _pay() async {
    if (!(_form.currentState?.validate() ?? false)) return;
    final l = context.l10n;
    final api = context.services.api;
    final till = _till.text.trim();
    final amount = _amount.text.trim();
    final ok = await walletConfirmSheet(context,
        title: l.merchantPayTitle,
        amount: amount,
        currency: widget.wallet.currency,
        rows: [
          (l.tillNumber, till),
          if (_reference.text.trim().isNotEmpty) (l.reference, _reference.text.trim()),
        ]);
    if (!ok || !mounted) return;
    final key = uuidV4();
    setState(() => _busy = true);
    final txn = await runWithStepUp(
      context,
      () => api.payMerchant(
          walletId: widget.wallet.id,
          merchantTill: till,
          amount: amount,
          reference: _reference.text.trim(),
          idempotencyKey: key),
    );
    if (!mounted) return;
    setState(() => _busy = false);
    if (txn != null) {
      showMessage(context, l.paymentSent);
      Navigator.pop(context);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    return Scaffold(
      appBar: AppBar(title: Text(l.merchantPayTitle)),
      body: Form(
        key: _form,
        child: ListView(padding: const EdgeInsets.all(16), children: [
          TextFormField(
            key: const Key('till'),
            controller: _till,
            keyboardType: TextInputType.number,
            decoration: InputDecoration(labelText: l.tillNumber),
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
          const SizedBox(height: 16),
          TextFormField(controller: _reference, maxLength: 140, decoration: InputDecoration(labelText: l.reference)),
          const SizedBox(height: 8),
          FilledButton(key: const Key('payMerchant'), onPressed: _busy ? null : _pay, child: Text(l.payNow)),
        ]),
      ),
    );
  }
}
