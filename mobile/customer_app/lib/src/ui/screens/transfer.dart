import 'package:flutter/material.dart';

import '../../api/models.dart';
import '../../core/ids.dart';
import '../widgets.dart';
import 'payees.dart';

/// Move money: own transfer or payment to a payee. Flow: form -> server validation (route, cut-off, fees,
/// Confirmation of Payee) -> confirmation sheet -> step-up -> payment with one Idempotency-Key per confirmation.
class TransferScreen extends StatelessWidget {
  const TransferScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final api = context.services.api;
    return Scaffold(
      appBar: AppBar(title: Text(l.moveMoney), actions: [
        IconButton(
          tooltip: l.payees,
          icon: const Icon(Icons.people_outline),
          onPressed: () => Navigator.push(context, MaterialPageRoute(builder: (_) => const PayeesScreen())),
        ),
      ]),
      body: Loader<(List<DepositAccount>, List<Payee>)>(
        load: () async => (await api.accounts(), await api.payees()),
        builder: (context, data, reload) => TransferForm(accounts: data.$1.where((a) => a.active).toList(), payees: data.$2),
      ),
    );
  }
}

class TransferForm extends StatefulWidget {
  const TransferForm({super.key, required this.accounts, required this.payees});
  final List<DepositAccount> accounts;
  final List<Payee> payees;

  @override
  State<TransferForm> createState() => _TransferFormState();
}

class _TransferFormState extends State<TransferForm> {
  final _form = GlobalKey<FormState>();
  final _amount = TextEditingController();
  final _reference = TextEditingController();
  String _kind = 'PAYEE';
  String? _from;
  String? _to;
  String? _payee;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _from = widget.accounts.isEmpty ? null : widget.accounts.first.id;
    if (widget.payees.isEmpty) _kind = 'OWN_TRANSFER';
  }

  PaymentRequest _request() => _kind == 'OWN_TRANSFER'
      ? PaymentRequest.own(fromAccountId: _from!, toAccountId: _to!, amount: _amount.text.trim(), reference: _reference.text.trim())
      : PaymentRequest.payee(fromAccountId: _from!, payeeId: _payee!, amount: _amount.text.trim(), reference: _reference.text.trim());

  Future<void> _review() async {
    if (!(_form.currentState?.validate() ?? false)) return;
    final api = context.services.api;
    final req = _request();
    setState(() => _busy = true);
    final check = await runAction(context, () => api.validatePayment(req));
    if (!mounted) return;
    setState(() => _busy = false);
    if (check == null) return;
    // One Idempotency-Key for this confirmation: a double tap, a resubmit after a lost response or the retry after
    // step-up cannot create a second payment.
    final key = uuidV4();
    final from = widget.accounts.firstWhere((a) => a.id == _from);
    final ok = await showModalBottomSheet<bool>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (c) => _ConfirmSheet(request: req, from: from, check: check, toLabel: _toLabel()),
    );
    if (ok != true || !mounted) return;
    setState(() => _busy = true);
    final payment = await runWithStepUp(context, () => api.pay(req, idempotencyKey: key));
    if (!mounted) return;
    setState(() => _busy = false);
    if (payment != null) {
      showMessage(context, context.l10n.paymentSubmitted(payment.status.toLowerCase()));
      _amount.clear();
      _reference.clear();
    }
  }

  String _toLabel() => _kind == 'OWN_TRANSFER'
      ? widget.accounts.firstWhere((a) => a.id == _to).label
      : widget.payees.firstWhere((p) => p.id == _payee).label;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    return Form(
      key: _form,
      child: ListView(padding: const EdgeInsets.all(16), children: [
        SegmentedButton<String>(
          segments: [
            ButtonSegment(value: 'PAYEE', label: Text(l.kindPayee), icon: const Icon(Icons.person_outline)),
            ButtonSegment(value: 'OWN_TRANSFER', label: Text(l.kindOwn), icon: const Icon(Icons.swap_horiz)),
          ],
          selected: {_kind},
          onSelectionChanged: (s) => setState(() => _kind = s.first),
        ),
        const SizedBox(height: 16),
        DropdownButtonFormField<String>(
          key: const Key('from'),
          initialValue: _from,
          decoration: InputDecoration(labelText: l.fromAccount),
          items: [for (final a in widget.accounts) DropdownMenuItem(value: a.id, child: Text('${a.label} (${a.currency})'))],
          onChanged: (v) => setState(() => _from = v),
          validator: requiredValidator(context),
        ),
        const SizedBox(height: 16),
        if (_kind == 'OWN_TRANSFER')
          DropdownButtonFormField<String>(
            key: const Key('toAccount'),
            initialValue: _to,
            decoration: InputDecoration(labelText: l.toAccount),
            items: [
              for (final a in widget.accounts.where((a) => a.id != _from))
                DropdownMenuItem(value: a.id, child: Text('${a.label} (${a.currency})'))
            ],
            onChanged: (v) => setState(() => _to = v),
            validator: requiredValidator(context),
          )
        else
          DropdownButtonFormField<String>(
            key: const Key('toPayee'),
            initialValue: _payee,
            decoration: InputDecoration(labelText: l.toAccount),
            items: [
              for (final p in widget.payees.where((p) => p.status == 'ACTIVE'))
                DropdownMenuItem(value: p.id, child: Text('${p.label} · ${p.accountNumber}'))
            ],
            onChanged: (v) => setState(() => _payee = v),
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
        FilledButton(key: const Key('review'), onPressed: _busy ? null : _review, child: Text(l.review)),
      ]),
    );
  }
}

class _ConfirmSheet extends StatelessWidget {
  const _ConfirmSheet({required this.request, required this.from, required this.check, required this.toLabel});
  final PaymentRequest request;
  final DepositAccount from;
  final Json check;
  final String toLabel;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final fee = check['fee_amount']?.toString() ?? (check['fees'] is Map ? (check['fees'] as Map)['amount']?.toString() : null);
    final cop = check['confirmation_of_payee'] is Map ? (check['confirmation_of_payee'] as Map)['result']?.toString() : null;
    final warnings = (check['warnings'] as List?)?.map((e) => e is Map ? (e['message'] ?? e['code']).toString() : '$e');
    return SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Text(l.confirmPayment, style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 8),
          Center(child: MoneyText(request.amount, from.currency, style: Theme.of(context).textTheme.headlineMedium)),
          ListTile(title: Text(l.fromAccount), trailing: Text(from.label)),
          ListTile(title: Text(l.toAccount), trailing: Text(toLabel)),
          if (request.reference?.isNotEmpty ?? false) ListTile(title: Text(l.reference), trailing: Text(request.reference!)),
          if (fee != null) ListTile(title: Text(l.fee), trailing: MoneyText(fee, from.currency)),
          if (cop != null) ListTile(leading: const Icon(Icons.verified_user_outlined), title: Text(cop)),
          for (final w in warnings ?? const <String>[])
            ListTile(leading: const Icon(Icons.warning_amber_outlined), title: Text(w)),
          const SizedBox(height: 8),
          FilledButton(key: const Key('confirmPayment'), onPressed: () => Navigator.pop(context, true), child: Text(l.confirm)),
          TextButton(onPressed: () => Navigator.pop(context, false), child: Text(l.cancel)),
        ]),
      ),
    );
  }
}
