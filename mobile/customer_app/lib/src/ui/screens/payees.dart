import 'package:flutter/material.dart';

import '../../api/models.dart';
import '../../core/ids.dart';
import '../widgets.dart';

/// Saved beneficiaries: add (step-up), trust to skip the cooling-off period (step-up), delete.
class PayeesScreen extends StatefulWidget {
  const PayeesScreen({super.key});

  @override
  State<PayeesScreen> createState() => _PayeesScreenState();
}

class _PayeesScreenState extends State<PayeesScreen> {
  Key _reload = UniqueKey();

  void _refresh() => setState(() => _reload = UniqueKey());

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final api = context.services.api;
    return Scaffold(
      appBar: AppBar(title: Text(l.payees)),
      floatingActionButton: FloatingActionButton.extended(
        icon: const Icon(Icons.person_add_alt),
        label: Text(l.addPayee),
        onPressed: () async {
          final added = await Navigator.push<bool>(context, MaterialPageRoute(builder: (_) => const AddPayeeScreen()));
          if (added == true) _refresh();
        },
      ),
      body: Loader<List<Payee>>(
        key: _reload,
        load: api.payees,
        isEmpty: (d) => d.isEmpty,
        builder: (context, payees, reload) => RefreshIndicator(
          onRefresh: reload,
          child: ListView(padding: const EdgeInsets.only(bottom: 96), children: [
            for (final p in payees)
              ListTile(
                leading: CircleAvatar(child: Text(p.label.isEmpty ? '?' : p.label[0].toUpperCase())),
                title: Text(p.label),
                subtitle: Text([
                  '${p.accountNumber}${p.bankName == null ? '' : ' · ${p.bankName}'}',
                  if (p.coolingOffUntil != null && p.coolingOffUntil!.isAfter(DateTime.now()) && !p.trusted)
                    l.coolingOff(shortDate(context, p.coolingOffUntil!)),
                ].join('\n')),
                isThreeLine: p.coolingOffUntil != null && !p.trusted,
                trailing: PopupMenuButton<String>(
                  onSelected: (v) async {
                    if (v == 'trust') {
                      final key = uuidV4();
                      final r = await runWithStepUp(context, () => api.trustPayee(p.id, idempotencyKey: key));
                      if (r != null) reload();
                    } else if (v == 'delete') {
                      if (!await confirmDialog(context, title: '${l.delete} ${p.label}?')) return;
                      if (!context.mounted) return;
                      await runAction(context, () => api.deletePayee(p.id));
                      reload();
                    }
                  },
                  itemBuilder: (_) => [
                    if (!p.trusted) PopupMenuItem(value: 'trust', child: Text(l.trust)),
                    PopupMenuItem(value: 'delete', child: Text(l.delete)),
                  ],
                  child: p.trusted ? Chip(label: Text(l.trusted)) : const Icon(Icons.more_vert),
                ),
              ),
          ]),
        ),
      ),
    );
  }
}

class AddPayeeScreen extends StatefulWidget {
  const AddPayeeScreen({super.key});

  @override
  State<AddPayeeScreen> createState() => _AddPayeeScreenState();
}

class _AddPayeeScreenState extends State<AddPayeeScreen> {
  final _form = GlobalKey<FormState>();
  final _name = TextEditingController();
  final _nickname = TextEditingController();
  final _number = TextEditingController();
  final _bank = TextEditingController();
  final _country = TextEditingController(text: 'KE');
  String _type = 'ACCOUNT_NUMBER';
  final _key = uuidV4();
  bool _busy = false;

  Future<void> _save() async {
    if (!(_form.currentState?.validate() ?? false)) return;
    final api = context.services.api;
    setState(() => _busy = true);
    final r = await runWithStepUp(
      context,
      () => api.addPayee(
        name: _name.text.trim(),
        nickname: _nickname.text.trim(),
        accountIdType: _type,
        accountNumber: _number.text.trim(),
        country: _country.text.trim().toUpperCase(),
        bankName: _bank.text.trim(),
        idempotencyKey: _key,
      ),
      success: context.l10n.payeeAdded,
    );
    if (!mounted) return;
    setState(() => _busy = false);
    if (r != null) Navigator.pop(context, true);
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    return Scaffold(
      appBar: AppBar(title: Text(l.addPayee)),
      body: Form(
        key: _form,
        child: ListView(padding: const EdgeInsets.all(16), children: [
          TextFormField(controller: _name, decoration: InputDecoration(labelText: l.payeeName), validator: requiredValidator(context)),
          const SizedBox(height: 12),
          TextFormField(controller: _nickname, decoration: InputDecoration(labelText: l.nickname)),
          const SizedBox(height: 12),
          DropdownButtonFormField<String>(
            initialValue: _type,
            decoration: InputDecoration(labelText: l.accountIdType),
            items: const ['ACCOUNT_NUMBER', 'IBAN', 'WALLET', 'PROXY']
                .map((t) => DropdownMenuItem(value: t, child: Text(t.replaceAll('_', ' ').toLowerCase())))
                .toList(),
            onChanged: (v) => setState(() => _type = v ?? _type),
          ),
          const SizedBox(height: 12),
          TextFormField(controller: _number, decoration: InputDecoration(labelText: l.accountNumber), validator: requiredValidator(context)),
          const SizedBox(height: 12),
          TextFormField(controller: _bank, decoration: InputDecoration(labelText: l.bankName)),
          const SizedBox(height: 12),
          TextFormField(
            controller: _country,
            maxLength: 2,
            decoration: InputDecoration(labelText: l.country),
            validator: (v) => v == null || !RegExp(r'^[A-Za-z]{2}$').hasMatch(v.trim()) ? l.required : null,
          ),
          const SizedBox(height: 8),
          FilledButton(onPressed: _busy ? null : _save, child: Text(l.save)),
        ]),
      ),
    );
  }
}
