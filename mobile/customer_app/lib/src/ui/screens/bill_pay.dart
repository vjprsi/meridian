import 'package:flutter/material.dart';

import '../../api/models.dart';
import '../../core/ids.dart';
import '../widgets.dart';
import 'wallet.dart';

/// Step 1 of a bill payment: pick the biller (searchable list).
class BillersScreen extends StatefulWidget {
  const BillersScreen({super.key, required this.wallet});
  final Wallet wallet;

  @override
  State<BillersScreen> createState() => _BillersScreenState();
}

class _BillersScreenState extends State<BillersScreen> {
  final _q = TextEditingController();
  Key _reload = UniqueKey();

  @override
  void dispose() {
    _q.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final api = context.services.api;
    return Scaffold(
      appBar: AppBar(title: Text(l.payABill)),
      body: Column(children: [
        Padding(
          padding: const EdgeInsets.all(16),
          child: TextField(
            controller: _q,
            decoration: InputDecoration(
              labelText: l.search,
              prefixIcon: const Icon(Icons.search),
              border: const OutlineInputBorder(),
            ),
            textInputAction: TextInputAction.search,
            onSubmitted: (_) => setState(() => _reload = UniqueKey()),
          ),
        ),
        Expanded(
          child: Loader<List<Biller>>(
            key: ValueKey(_reload),
            load: () => api.billers(q: _q.text.trim().isEmpty ? null : _q.text.trim()),
            isEmpty: (b) => b.isEmpty,
            builder: (context, billers, reload) => RefreshIndicator(
              onRefresh: reload,
              child: ListView(children: [
                for (final b in billers)
                  ListTile(
                    leading: const CircleAvatar(child: Icon(Icons.receipt_long_outlined)),
                    title: Text(b.name),
                    subtitle: Text([b.category.toLowerCase(), if (b.country != null) b.country!].join(' · ')),
                    trailing: const Icon(Icons.chevron_right),
                    onTap: () => Navigator.push(
                        context, MaterialPageRoute(builder: (_) => BillPayDetailScreen(wallet: widget.wallet, biller: b))),
                  ),
              ]),
            ),
          ),
        ),
      ]),
    );
  }
}

/// Step 2: reference (validate) -> inquire (amount due) -> pay (step-up) -> receipt.
class BillPayDetailScreen extends StatefulWidget {
  const BillPayDetailScreen({super.key, required this.wallet, required this.biller});
  final Wallet wallet;
  final Biller biller;

  @override
  State<BillPayDetailScreen> createState() => _BillPayDetailScreenState();
}

class _BillPayDetailScreenState extends State<BillPayDetailScreen> {
  final _form = GlobalKey<FormState>();
  final _reference = TextEditingController();
  final _amount = TextEditingController();
  ReferenceValidation? _validation;
  BillInquiry? _inquiry;
  bool _busy = false;

  @override
  void dispose() {
    _reference.dispose();
    _amount.dispose();
    super.dispose();
  }

  Future<void> _validate() async {
    final ref = _reference.text.trim();
    if (ref.isEmpty) return;
    setState(() => _busy = true);
    final api = context.services.api;
    final v = await runAction(context, () => api.validateBillerReference(widget.biller.id, reference: ref));
    if (!mounted) return;
    final inq = v == null ? null : await runAction(context, () => api.inquireBill(billerId: widget.biller.id, reference: ref));
    if (!mounted) return;
    setState(() {
      _validation = v;
      _inquiry = inq;
      if (inq?.amountDue != null) _amount.text = inq!.amountDue!;
      _busy = false;
    });
  }

  Future<void> _pay() async {
    if (!(_form.currentState?.validate() ?? false)) return;
    final l = context.l10n;
    final api = context.services.api;
    final ref = _reference.text.trim();
    final amount = _amount.text.trim();
    final ok = await walletConfirmSheet(context,
        title: widget.biller.name,
        amount: amount,
        currency: _inquiry?.currency.isNotEmpty == true ? _inquiry!.currency : widget.wallet.currency,
        rows: [
          (l.biller, widget.biller.name),
          (l.referenceNumber, ref),
          if (_validation?.customerName != null) (l.payee, _validation!.customerName!),
        ]);
    if (!ok || !mounted) return;
    final key = uuidV4();
    setState(() => _busy = true);
    final payment = await runWithStepUp(
      context,
      () => api.payBill(
        walletId: widget.wallet.id,
        billerId: widget.biller.id,
        reference: ref,
        amount: amount,
        billInquiryId: _inquiry?.id,
        idempotencyKey: key,
      ),
    );
    if (!mounted) return;
    setState(() => _busy = false);
    if (payment != null) {
      await showModalBottomSheet<void>(
        context: context,
        showDragHandle: true,
        builder: (_) => _BillReceipt(payment: payment, billerName: widget.biller.name),
      );
      if (mounted) Navigator.pop(context);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    return Scaffold(
      appBar: AppBar(title: Text(widget.biller.name)),
      body: Form(
        key: _form,
        child: ListView(padding: const EdgeInsets.all(16), children: [
          TextFormField(
            key: const Key('reference'),
            controller: _reference,
            decoration: InputDecoration(
              labelText: l.referenceNumber,
              suffixIcon: TextButton(onPressed: _busy ? null : _validate, child: Text(l.check)),
            ),
            validator: requiredValidator(context),
          ),
          if (_validation?.customerName != null)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Card(
                child: ListTile(
                  leading: const Icon(Icons.verified_user_outlined),
                  title: Text(_validation!.customerName!),
                  subtitle: _validation!.message == null ? null : Text(_validation!.message!),
                ),
              ),
            ),
          if (_inquiry?.amountDue != null)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Card(
                child: ListTile(
                  title: Text(l.amountDue),
                  trailing: MoneyText(_inquiry!.amountDue, _inquiry!.currency,
                      style: Theme.of(context).textTheme.titleMedium),
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
          const SizedBox(height: 8),
          FilledButton(key: const Key('payBill'), onPressed: _busy ? null : _pay, child: Text(l.payNow)),
        ]),
      ),
    );
  }
}

class _BillReceipt extends StatelessWidget {
  const _BillReceipt({required this.payment, required this.billerName});
  final BillPayment payment;
  final String billerName;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Icon(Icons.check_circle_outline, color: Theme.of(context).colorScheme.primary, size: 48),
          const SizedBox(height: 8),
          Center(child: Text(l.billPaid, style: Theme.of(context).textTheme.titleMedium)),
          const SizedBox(height: 8),
          Center(child: MoneyText(payment.amount, payment.currency, style: Theme.of(context).textTheme.headlineMedium)),
          ListTile(title: Text(l.biller), trailing: Text(payment.billerName ?? billerName)),
          if (payment.reference != null) ListTile(title: Text(l.referenceNumber), trailing: Text(payment.reference!)),
          ListTile(title: Text(l.receipt), trailing: StatusChip(payment.status)),
          if (payment.token != null)
            ListTile(title: Text(l.receipt), subtitle: SelectableText(payment.token!)),
          const SizedBox(height: 8),
          FilledButton(onPressed: () => Navigator.pop(context), child: Text(l.confirm)),
        ]),
      ),
    );
  }
}
