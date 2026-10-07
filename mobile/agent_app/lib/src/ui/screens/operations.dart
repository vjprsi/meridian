import 'package:flutter/material.dart';

import '../../api/agent_api.dart';
import '../../core/ids.dart';
import '../../core/money.dart';
import '../../outbox/types.dart';
import '../widgets.dart';

/// Cash-in: captured into the outbox (sent at once when online, queued under the offline limits otherwise).
class CashInScreen extends StatefulWidget {
  const CashInScreen({super.key});

  @override
  State<CashInScreen> createState() => _CashInScreenState();
}

class _CashInScreenState extends State<CashInScreen> {
  final _form = GlobalKey<FormState>();
  final _msisdn = TextEditingController();
  final _amount = TextEditingController();
  CustomerLookup? _found;
  bool _busy = false;

  Future<void> _lookup() async {
    if (_msisdn.text.trim().isEmpty) return;
    final r = await runAction(context, () => context.agent.api.lookup(msisdn: _msisdn.text.trim()));
    if (mounted) setState(() => _found = r);
  }

  Future<void> _submit() async {
    if (!(_form.currentState?.validate() ?? false)) return;
    final agent = context.agent;
    final l = context.l10n;
    final p = agent.profile!;
    final amount = _amount.text.trim();
    final ok = await showDialog<bool>(
      context: context,
      builder: (d) => AlertDialog(
        title: Text(l.cashIn),
        content: Text('${_msisdn.text.trim()}\n${Money.format(amount, p.currency)}${_found?.name == null ? '' : '\n${_found!.name}'}'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(d, false), child: Text(l.cancel)),
          FilledButton(key: const Key('confirm'), onPressed: () => Navigator.pop(d, true), child: Text(l.confirm)),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    setState(() => _busy = true);
    final r = await runAction(
        context,
        () => agent.capture(
            OpKind.cashIn,
            OpPayload(
              request: {'payee_msisdn': _msisdn.text.trim(), 'amount': amount, 'agent_outlet_id': p.outletId},
              amount: amount,
              currency: p.currency,
              display: {'customer': _msisdn.text.trim()},
            )));
    if (!mounted) return;
    setState(() => _busy = false);
    if (r != null) {
      await reportCapture(context, r);
      if (mounted) Navigator.pop(context);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final online = context.agent.online;
    return Scaffold(
      appBar: AppBar(title: Text(l.cashIn)),
      body: Form(
        key: _form,
        child: ListView(padding: const EdgeInsets.all(16), children: [
          TextFormField(
            key: const Key('msisdn'),
            controller: _msisdn,
            keyboardType: TextInputType.phone,
            decoration: InputDecoration(
              labelText: l.customerPhone,
              suffixIcon: online ? IconButton(tooltip: l.lookup, onPressed: _lookup, icon: const Icon(Icons.search)) : null,
            ),
            validator: requiredValidator(context),
          ),
          if (_found != null)
            ListTile(
              leading: Icon(_found!.found ? Icons.verified_user_outlined : Icons.help_outline),
              title: Text(_found!.found ? l.customerFound(_found!.name ?? '') : l.customerNotFound),
            ),
          const SizedBox(height: 12),
          TextFormField(
            key: const Key('amount'),
            controller: _amount,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: InputDecoration(labelText: l.amount, suffixText: context.agent.profile?.currency),
            validator: amountValidator(context),
          ),
          const SizedBox(height: 16),
          FilledButton(key: const Key('submit'), onPressed: _busy ? null : _submit, child: Text(l.cashIn)),
        ]),
      ),
    );
  }
}

/// Cash-out: online only. The agent starts it for the customer's wallet; the customer confirms with PIN or OTP.
class CashOutScreen extends StatefulWidget {
  const CashOutScreen({super.key});

  @override
  State<CashOutScreen> createState() => _CashOutScreenState();
}

class _CashOutScreenState extends State<CashOutScreen> {
  final _form = GlobalKey<FormState>();
  final _msisdn = TextEditingController();
  final _amount = TextEditingController();
  final _secret = TextEditingController();
  final _key = uuidV4();
  final _confirmKey = uuidV4();
  CustomerLookup? _found;
  AgentTxn? _txn;
  bool _otp = false;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final agent = context.agent;
    if (!agent.online) {
      return Scaffold(appBar: AppBar(title: Text(l.cashOut)), body: Center(child: Text(l.offlineNotAllowed)));
    }
    return Scaffold(
      appBar: AppBar(title: Text(l.cashOut)),
      body: Form(
        key: _form,
        child: ListView(padding: const EdgeInsets.all(16), children: [
          TextFormField(
            controller: _msisdn,
            enabled: _txn == null,
            keyboardType: TextInputType.phone,
            decoration: InputDecoration(
                labelText: l.customerPhone,
                suffixIcon: IconButton(
                    tooltip: l.lookup,
                    icon: const Icon(Icons.search),
                    onPressed: () async {
                      final r = await runAction(context, () => agent.api.lookup(msisdn: _msisdn.text.trim()));
                      if (mounted) setState(() => _found = r);
                    })),
            validator: requiredValidator(context),
          ),
          if (_found != null)
            ListTile(title: Text(_found!.found ? l.customerFound(_found!.name ?? '') : l.customerNotFound)),
          TextFormField(
            controller: _amount,
            enabled: _txn == null,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: InputDecoration(labelText: l.amount),
            validator: amountValidator(context),
          ),
          const SizedBox(height: 16),
          if (_txn == null)
            FilledButton(
              onPressed: _found?.found == true && _found?.walletId != null
                  ? () async {
                      if (!(_form.currentState?.validate() ?? false)) return;
                      final t = await runAction(
                          context,
                          () => agent.api.cashOut(
                              payerWalletId: _found!.walletId!, amount: _amount.text.trim(),
                              outletId: agent.profile?.outletId, key: _key));
                      if (context.mounted && t != null) {
                        setState(() => _txn = t);
                        showMessage(context, l.cashOutStarted);
                      }
                    }
                  : null,
              child: Text(l.cashOut),
            )
          else ...[
            SegmentedButton<bool>(
              segments: [ButtonSegment(value: false, label: Text(l.customerPin)), ButtonSegment(value: true, label: Text(l.otp))],
              selected: {_otp},
              onSelectionChanged: (s) => setState(() => _otp = s.first),
            ),
            const SizedBox(height: 12),
            TextField(
                controller: _secret,
                obscureText: true,
                keyboardType: TextInputType.number,
                decoration: InputDecoration(labelText: _otp ? l.otp : l.customerPin)),
            const SizedBox(height: 12),
            FilledButton(
              onPressed: () async {
                final secret = _secret.text.trim();
                _secret.clear(); // the customer's secret is never kept
                final t = await runAction(
                    context,
                    () => agent.api.confirmCashOut(_txn!.id,
                        pin: _otp ? null : secret, otp: _otp ? secret : null, key: _confirmKey));
                if (!context.mounted || t == null) return;
                showMessage(context, '${l.completed}: ${t.reference}');
                agent.refreshFloat().ignore();
                Navigator.pop(context);
              },
              child: Text(l.confirm),
            ),
          ],
        ]),
      ),
    );
  }
}

class BillPayScreen extends StatefulWidget {
  const BillPayScreen({super.key});

  @override
  State<BillPayScreen> createState() => _BillPayScreenState();
}

class _BillPayScreenState extends State<BillPayScreen> {
  final _form = GlobalKey<FormState>();
  final _reference = TextEditingController();
  final _name = TextEditingController();
  final _amount = TextEditingController();
  Future<List<Biller>>? _billers;
  String? _biller;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final agent = context.agent;
    _billers ??= agent.api.billers().catchError((Object _) => <Biller>[]);
    return Scaffold(
      appBar: AppBar(title: Text(l.billPay)),
      body: FutureBuilder<List<Biller>>(
        future: _billers,
        builder: (context, snap) {
          final billers = snap.data ?? const <Biller>[];
          return Form(
            key: _form,
            child: ListView(padding: const EdgeInsets.all(16), children: [
              DropdownButtonFormField<String>(
                initialValue: _biller,
                decoration: InputDecoration(labelText: l.biller),
                items: [for (final b in billers) DropdownMenuItem(value: b.id, child: Text(b.name))],
                onChanged: (v) => setState(() => _biller = v),
                validator: requiredValidator(context),
              ),
              const SizedBox(height: 12),
              TextFormField(controller: _reference, decoration: InputDecoration(labelText: l.accountReference),
                  validator: requiredValidator(context)),
              const SizedBox(height: 12),
              TextFormField(controller: _name, decoration: InputDecoration(labelText: l.customerName)),
              const SizedBox(height: 12),
              TextFormField(
                controller: _amount,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: InputDecoration(labelText: l.amount),
                validator: amountValidator(context),
              ),
              const SizedBox(height: 16),
              FilledButton(
                onPressed: () async {
                  if (!(_form.currentState?.validate() ?? false)) return;
                  final p = agent.profile!;
                  final amount = _amount.text.trim();
                  final name = billers.firstWhere((b) => b.id == _biller).name;
                  final r = await runAction(
                      context,
                      () => agent.capture(
                          OpKind.billPayment,
                          OpPayload(
                            request: {
                              'biller_id': _biller,
                              'account_reference': _reference.text.trim(),
                              if (_name.text.trim().isNotEmpty) 'customer_name': _name.text.trim(),
                              'amount': amount,
                            },
                            amount: amount,
                            currency: p.currency,
                            display: {'biller': name, 'reference': _reference.text.trim()},
                          )));
                  if (!context.mounted || r == null) return;
                  await reportCapture(context, r);
                  if (context.mounted) Navigator.pop(context);
                },
                child: Text(l.billPay),
              ),
            ]),
          );
        },
      ),
    );
  }
}

class FloatRequestScreen extends StatefulWidget {
  const FloatRequestScreen({super.key});

  @override
  State<FloatRequestScreen> createState() => _FloatRequestScreenState();
}

class _FloatRequestScreenState extends State<FloatRequestScreen> {
  final _form = GlobalKey<FormState>();
  final _amount = TextEditingController();
  final _bankRef = TextEditingController();
  String _type = 'TOP_UP';

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final agent = context.agent;
    return Scaffold(
      appBar: AppBar(title: Text(l.floatRequest)),
      body: Form(
        key: _form,
        child: ListView(padding: const EdgeInsets.all(16), children: [
          DropdownButtonFormField<String>(
            initialValue: _type,
            decoration: InputDecoration(labelText: l.requestType),
            items: const ['TOP_UP', 'REBALANCE', 'WITHDRAW']
                .map((t) => DropdownMenuItem(value: t, child: Text(t.replaceAll('_', ' ').toLowerCase())))
                .toList(),
            onChanged: (v) => setState(() => _type = v ?? _type),
          ),
          const SizedBox(height: 12),
          TextFormField(
            controller: _amount,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: InputDecoration(labelText: l.amount),
            validator: amountValidator(context),
          ),
          const SizedBox(height: 12),
          TextFormField(controller: _bankRef, decoration: InputDecoration(labelText: l.bankReference)),
          const SizedBox(height: 16),
          FilledButton(
            onPressed: () async {
              if (!(_form.currentState?.validate() ?? false)) return;
              final amount = _amount.text.trim();
              final r = await runAction(
                  context,
                  () => agent.capture(
                      OpKind.floatRequest,
                      OpPayload(
                        request: {
                          'request_type': _type,
                          'amount': amount,
                          if (_bankRef.text.trim().isNotEmpty) 'bank_reference': _bankRef.text.trim(),
                        },
                        amount: amount,
                        currency: agent.profile?.currency,
                        display: {'type': _type},
                      )));
              if (!context.mounted || r == null) return;
              await reportCapture(context, r);
              if (context.mounted) Navigator.pop(context);
            },
            child: Text(l.floatRequest),
          ),
        ]),
      ),
    );
  }
}

/// Completed transactions from the server (when online) and the operations kept on this device.
class HistoryScreen extends StatelessWidget {
  const HistoryScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final agent = context.agent;
    return DefaultTabController(
      length: 2,
      child: Scaffold(
        appBar: AppBar(
            title: Text(l.history), bottom: TabBar(tabs: [Tab(text: l.onServer), Tab(text: l.onDevice)])),
        body: TabBarView(children: [
          FutureBuilder<List<AgentTxn>>(
            future: agent.api.transactions(),
            builder: (context, snap) {
              if (snap.connectionState != ConnectionState.done) return const Center(child: CircularProgressIndicator());
              if (snap.hasError) return Center(child: Text(describeError(context, snap.error!)));
              final rows = snap.data!;
              if (rows.isEmpty) return Center(child: Text(l.empty));
              return ListView(children: [
                for (final t in rows)
                  ListTile(
                    title: Text('${t.txnType.replaceAll('_', ' ').toLowerCase()} · ${t.reference}'),
                    subtitle: Text('${t.status.toLowerCase()}${t.payeeMsisdn == null ? '' : ' · ${t.payeeMsisdn}'}'),
                    trailing: Text(Money.format(t.amount, t.currency)),
                  ),
              ]);
            },
          ),
          FutureBuilder<List<OutboxView>>(
            future: agent.outbox.list(),
            builder: (context, snap) {
              final rows = (snap.data ?? const <OutboxView>[]).reversed.toList();
              if (rows.isEmpty) return Center(child: Text(l.empty));
              return ListView(children: [for (final v in rows) OutboxTile(view: v)]);
            },
          ),
        ]),
      ),
    );
  }
}

class OutboxTile extends StatelessWidget {
  const OutboxTile({super.key, required this.view, this.trailing});
  final OutboxView view;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final r = view.record;
    final icon = switch (r.status) {
      OpStatus.pending => Icons.schedule,
      OpStatus.synced => Icons.check_circle_outline,
      OpStatus.rejected => Icons.error_outline,
      OpStatus.needsReview => Icons.help_outline,
    };
    return ListTile(
      leading: Icon(icon),
      title: Text('${kindLabel(context, r.kind)} · ${Money.format(view.payload.amount, view.payload.currency)}'),
      subtitle: Text([
        statusLabel(context, r.status),
        ...view.payload.display.values,
        if (r.status == OpStatus.rejected && view.result?.problem != null) '${view.result!.problem}',
        if (r.blocker != null) r.blocker!.name,
      ].join(' · ')),
      trailing: trailing,
    );
  }
}
