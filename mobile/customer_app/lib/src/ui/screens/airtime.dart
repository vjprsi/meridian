import 'package:flutter/material.dart';

import '../../api/models.dart';
import '../../core/ids.dart';
import '../widgets.dart';
import 'wallet.dart';

/// Step 1 of a top-up: pick the operator (billers in the AIRTIME / DATA categories).
class AirtimeOperatorsScreen extends StatelessWidget {
  const AirtimeOperatorsScreen({super.key, required this.wallet});
  final Wallet wallet;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final api = context.services.api;
    return Scaffold(
      appBar: AppBar(title: Text(l.topUpAirtime)),
      body: Loader<List<Biller>>(
        load: () async => (await api.billers()).where((b) => b.isAirtime).toList(),
        isEmpty: (b) => b.isEmpty,
        builder: (context, billers, reload) => RefreshIndicator(
          onRefresh: reload,
          child: ListView(children: [
            for (final b in billers)
              ListTile(
                leading: const CircleAvatar(child: Icon(Icons.sim_card_outlined)),
                title: Text(b.name),
                subtitle: Text(b.category.toLowerCase()),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => Navigator.push(
                    context, MaterialPageRoute(builder: (_) => AirtimeTopUpScreen(wallet: wallet, operator: b))),
              ),
          ]),
        ),
      ),
    );
  }
}

/// Step 2: number + a bundle or custom amount -> confirm (step-up) -> recharge receipt/token.
class AirtimeTopUpScreen extends StatefulWidget {
  const AirtimeTopUpScreen({super.key, required this.wallet, required this.operator});
  final Wallet wallet;
  final Biller operator;

  @override
  State<AirtimeTopUpScreen> createState() => _AirtimeTopUpScreenState();
}

class _AirtimeTopUpScreenState extends State<AirtimeTopUpScreen> {
  final _form = GlobalKey<FormState>();
  final _msisdn = TextEditingController();
  final _amount = TextEditingController();
  BillerProduct? _product;
  bool _busy = false;

  @override
  void dispose() {
    _msisdn.dispose();
    _amount.dispose();
    super.dispose();
  }

  Future<void> _submit(List<BillerProduct> products) async {
    if (!(_form.currentState?.validate() ?? false)) return;
    final l = context.l10n;
    final api = context.services.api;
    final custom = _amount.text.trim();
    final amount = _product?.amount ?? custom;
    final ok = await walletConfirmSheet(context,
        title: l.topUpAirtime,
        amount: amount,
        currency: _product?.currency ?? widget.wallet.currency,
        rows: [
          (l.operator, widget.operator.name),
          (l.phoneNumber, _msisdn.text.trim()),
          if (_product != null) (l.bundle, _product!.name),
        ]);
    if (!ok || !mounted) return;
    final key = uuidV4();
    setState(() => _busy = true);
    final topup = await runWithStepUp(
      context,
      () => api.topUpAirtime(
        walletId: widget.wallet.id,
        billerId: widget.operator.id,
        billerProductId: _product?.id,
        msisdn: _msisdn.text.trim(),
        amount: _product == null ? custom : null,
        idempotencyKey: key,
      ),
    );
    if (!mounted) return;
    setState(() => _busy = false);
    if (topup != null) {
      await showModalBottomSheet<void>(
        context: context,
        showDragHandle: true,
        builder: (_) => _TopUpReceipt(topup: topup),
      );
      if (mounted) Navigator.pop(context);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final api = context.services.api;
    return Scaffold(
      appBar: AppBar(title: Text(widget.operator.name)),
      // Bundles come from the biller detail; a bare operator with no products still allows a custom amount.
      body: Loader<List<BillerProduct>>(
        load: () async {
          try {
            return (await api.biller(widget.operator.id)).products;
          } catch (_) {
            return widget.operator.products;
          }
        },
        builder: (context, products, reload) => Form(
          key: _form,
          child: ListView(padding: const EdgeInsets.all(16), children: [
            TextFormField(
              key: const Key('msisdn'),
              controller: _msisdn,
              keyboardType: TextInputType.phone,
              decoration: InputDecoration(labelText: l.phoneNumber),
              validator: requiredValidator(context),
            ),
            const SizedBox(height: 16),
            if (products.isNotEmpty) ...[
              Text(l.bundle, style: Theme.of(context).textTheme.titleSmall),
              const SizedBox(height: 8),
              Wrap(spacing: 8, runSpacing: 8, children: [
                for (final p in products)
                  ChoiceChip(
                    label: Text(p.amount == null ? p.name : '${p.name} · ${p.amount}'),
                    selected: _product?.id == p.id,
                    onSelected: (_) => setState(() {
                      _product = p;
                      _amount.clear();
                    }),
                  ),
                ChoiceChip(
                  label: Text(l.customAmount),
                  selected: _product == null,
                  onSelected: (_) => setState(() => _product = null),
                ),
              ]),
              const SizedBox(height: 16),
            ],
            if (_product == null)
              TextFormField(
                key: const Key('amount'),
                controller: _amount,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: InputDecoration(labelText: l.amount),
                validator: _product == null ? amountValidator(context) : null,
              ),
            const SizedBox(height: 8),
            FilledButton(key: const Key('review'), onPressed: _busy ? null : () => _submit(products), child: Text(l.review)),
          ]),
        ),
      ),
    );
  }
}

class _TopUpReceipt extends StatelessWidget {
  const _TopUpReceipt({required this.topup});
  final AirtimeTopup topup;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Icon(Icons.check_circle_outline, color: Theme.of(context).colorScheme.primary, size: 48),
          const SizedBox(height: 8),
          Center(child: Text(l.topUpDone, style: Theme.of(context).textTheme.titleMedium)),
          const SizedBox(height: 8),
          Center(child: MoneyText(topup.amount, topup.currency, style: Theme.of(context).textTheme.headlineMedium)),
          if (topup.msisdn != null) ListTile(title: Text(l.phoneNumber), trailing: Text(topup.msisdn!)),
          ListTile(title: Text(l.receipt), trailing: StatusChip(topup.status)),
          if (topup.token != null)
            ListTile(
              title: Text(l.rechargeToken),
              subtitle: SelectableText(topup.token!, style: const TextStyle(fontFeatures: [FontFeature.tabularFigures()])),
            ),
          const SizedBox(height: 8),
          FilledButton(onPressed: () => Navigator.pop(context), child: Text(l.confirm)),
        ]),
      ),
    );
  }
}
