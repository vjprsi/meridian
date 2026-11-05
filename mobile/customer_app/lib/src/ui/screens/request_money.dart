import 'package:flutter/material.dart';

import '../../api/models.dart';
import '../../core/ids.dart';
import '../widgets.dart';

/// Request money: ask by phone/alias, and see incoming/outgoing requests. An incoming request can be paid from the
/// wallet (step-up) or declined.
class RequestMoneyScreen extends StatefulWidget {
  const RequestMoneyScreen({super.key, required this.wallet});
  final Wallet wallet;

  @override
  State<RequestMoneyScreen> createState() => _RequestMoneyScreenState();
}

class _RequestMoneyScreenState extends State<RequestMoneyScreen> {
  String _direction = 'incoming';
  Key _reload = UniqueKey();

  void _refresh() => setState(() => _reload = UniqueKey());

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final api = context.services.api;
    return Scaffold(
      appBar: AppBar(title: Text(l.requestMoney)),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () async {
          final created = await showModalBottomSheet<bool>(
            context: context,
            showDragHandle: true,
            isScrollControlled: true,
            builder: (_) => _NewRequestSheet(wallet: widget.wallet),
          );
          if (created == true) _refresh();
        },
        icon: const Icon(Icons.request_page_outlined),
        label: Text(l.request),
      ),
      body: Column(children: [
        Padding(
          padding: const EdgeInsets.all(16),
          child: SegmentedButton<String>(
            segments: [
              ButtonSegment(value: 'incoming', label: Text(l.incoming)),
              ButtonSegment(value: 'outgoing', label: Text(l.outgoing)),
            ],
            selected: {_direction},
            onSelectionChanged: (s) => setState(() => _direction = s.first),
          ),
        ),
        Expanded(
          child: Loader<List<MoneyRequest>>(
            key: ValueKey('$_direction-$_reload'),
            load: () => api.moneyRequests(direction: _direction),
            isEmpty: (d) => d.isEmpty,
            builder: (context, requests, reload) => RefreshIndicator(
              onRefresh: reload,
              child: requests.isEmpty
                  ? ListView(children: [EmptyPanel(text: l.noRequests)])
                  : ListView(
                      children: [
                        for (final r in requests)
                          _RequestTile(request: r, wallet: widget.wallet, onChanged: _refresh),
                      ],
                    ),
            ),
          ),
        ),
      ]),
    );
  }
}

class _RequestTile extends StatelessWidget {
  const _RequestTile({required this.request, required this.wallet, required this.onChanged});
  final MoneyRequest request;
  final Wallet wallet;
  final VoidCallback onChanged;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final api = context.services.api;
    return Card(
      child: Column(children: [
        ListTile(
          leading: CircleAvatar(child: Icon(request.incoming ? Icons.south_west : Icons.north_east, size: 18)),
          title: Text(request.counterpartyName ?? request.value ?? '—'),
          subtitle: Text([shortDate(context, request.createdAt), if (request.note != null) request.note!].join(' · ')),
          trailing: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              MoneyText(request.amount, request.currency),
              StatusChip(request.status),
            ],
          ),
        ),
        if (request.payable)
          OverflowBar(alignment: MainAxisAlignment.end, children: [
            TextButton(
              onPressed: () async {
                final reason = await _reasonDialog(context);
                if (reason == null || !context.mounted) return;
                final r = await runAction(context, () => api.declineMoneyRequest(request.id, reason: reason));
                if (r != null) onChanged();
              },
              child: Text(l.decline),
            ),
            FilledButton(
              onPressed: () async {
                final key = uuidV4();
                final r = await runWithStepUp(
                  context,
                  () => api.payMoneyRequest(request.id, walletId: wallet.id, idempotencyKey: key),
                );
                if (r != null) {
                  if (context.mounted) showMessage(context, l.paymentSent);
                  onChanged();
                }
              },
              child: Text(l.payNow),
            ),
          ]),
      ]),
    );
  }

  Future<String?> _reasonDialog(BuildContext context) async {
    final l = context.l10n;
    final reason = TextEditingController();
    final r = await showDialog<String>(
      context: context,
      builder: (c) => AlertDialog(
        title: Text(l.decline),
        content: TextField(controller: reason, decoration: InputDecoration(labelText: l.declineReason)),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c), child: Text(l.cancel)),
          FilledButton(onPressed: () => Navigator.pop(c, reason.text.trim()), child: Text(l.decline)),
        ],
      ),
    );
    return r;
  }
}

class _NewRequestSheet extends StatefulWidget {
  const _NewRequestSheet({required this.wallet});
  final Wallet wallet;

  @override
  State<_NewRequestSheet> createState() => _NewRequestSheetState();
}

class _NewRequestSheetState extends State<_NewRequestSheet> {
  final _form = GlobalKey<FormState>();
  final _value = TextEditingController();
  final _amount = TextEditingController();
  final _note = TextEditingController();
  String _type = 'MSISDN';
  bool _busy = false;

  @override
  void dispose() {
    _value.dispose();
    _amount.dispose();
    _note.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!(_form.currentState?.validate() ?? false)) return;
    setState(() => _busy = true);
    final r = await runAction(
      context,
      () => context.services.api.requestMoney(
          type: _type, value: _value.text.trim(), amount: _amount.text.trim(), note: _note.text.trim()),
    );
    if (!mounted) return;
    setState(() => _busy = false);
    if (r != null) {
      showMessage(context, context.l10n.requestSent);
      Navigator.pop(context, true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    return SafeArea(
      child: Padding(
        padding: EdgeInsets.fromLTRB(16, 0, 16, 16 + MediaQuery.of(context).viewInsets.bottom),
        child: Form(
          key: _form,
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Text(l.requestMoney, style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 12),
            SegmentedButton<String>(
              segments: [
                ButtonSegment(value: 'MSISDN', label: Text(l.byPhone)),
                ButtonSegment(value: 'WALLET_NUMBER', label: Text(l.byWallet)),
              ],
              selected: {_type},
              onSelectionChanged: (s) => setState(() => _type = s.first),
            ),
            const SizedBox(height: 12),
            TextFormField(
              controller: _value,
              decoration: InputDecoration(labelText: l.recipient),
              validator: requiredValidator(context),
            ),
            const SizedBox(height: 12),
            TextFormField(
              controller: _amount,
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              decoration: InputDecoration(labelText: l.amount),
              validator: amountValidator(context),
            ),
            const SizedBox(height: 12),
            TextFormField(controller: _note, maxLength: 140, decoration: InputDecoration(labelText: l.note)),
            const SizedBox(height: 8),
            FilledButton(onPressed: _busy ? null : _submit, child: Text(l.send)),
          ]),
        ),
      ),
    );
  }
}
