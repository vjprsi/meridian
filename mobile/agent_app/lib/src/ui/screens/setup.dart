import 'package:flutter/material.dart';

import '../../api/agent_api.dart';
import '../../outbox/outbox.dart';
import '../widgets.dart';

/// First use: pick the outlet, register the device key, set the 6-digit app PIN.
class SetupScreen extends StatefulWidget {
  const SetupScreen({super.key});

  @override
  State<SetupScreen> createState() => _SetupScreenState();
}

class _SetupScreenState extends State<SetupScreen> {
  final _form = GlobalKey<FormState>();
  final _pin = TextEditingController();
  final _pin2 = TextEditingController();
  Future<List<Outlet>>? _outlets;
  String? _outlet;
  bool _busy = false;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final agent = context.agent;
    _outlets ??= agent.api.outlets();
    return Scaffold(
      appBar: AppBar(title: Text(l.setupTitle), actions: [
        IconButton(tooltip: l.signOut, onPressed: agent.signOut, icon: const Icon(Icons.logout)),
      ]),
      body: FutureBuilder<List<Outlet>>(
        future: _outlets,
        builder: (context, snap) {
          if (snap.connectionState != ConnectionState.done) return const Center(child: CircularProgressIndicator());
          if (snap.hasError) {
            return Center(
              child: Column(mainAxisSize: MainAxisSize.min, children: [
                Text(describeError(context, snap.error!)),
                TextButton(onPressed: () => setState(() => _outlets = null), child: Text(l.retry)),
              ]),
            );
          }
          final outlets = snap.data!.where((o) => o.status == 'ACTIVE').toList();
          _outlet ??= outlets.isEmpty ? null : outlets.first.id;
          return Form(
            key: _form,
            child: ListView(padding: const EdgeInsets.all(16), children: [
              DropdownButtonFormField<String>(
                initialValue: _outlet,
                decoration: InputDecoration(labelText: l.chooseOutlet),
                items: [for (final o in outlets) DropdownMenuItem(value: o.id, child: Text('${o.name} (${o.code})'))],
                onChanged: (v) => _outlet = v,
                validator: requiredValidator(context),
              ),
              const SizedBox(height: 16),
              TextFormField(
                key: const Key('pin'),
                controller: _pin,
                obscureText: true,
                maxLength: 6,
                keyboardType: TextInputType.number,
                decoration: InputDecoration(labelText: l.appPin, helperText: l.appPinHint),
                validator: (v) => RegExp(r'^\d{6}$').hasMatch(v ?? '') ? null : l.pinInvalid,
              ),
              TextFormField(
                key: const Key('pin2'),
                controller: _pin2,
                obscureText: true,
                maxLength: 6,
                keyboardType: TextInputType.number,
                decoration: InputDecoration(labelText: l.confirmPin),
                validator: (v) => v == _pin.text ? null : l.pinMismatch,
              ),
              const SizedBox(height: 8),
              FilledButton(
                key: const Key('finishSetup'),
                onPressed: _busy
                    ? null
                    : () async {
                        if (!(_form.currentState?.validate() ?? false)) return;
                        setState(() => _busy = true);
                        await runAction(context, () => agent.completeSetup(outletId: _outlet!, pin: _pin.text));
                        if (mounted) setState(() => _busy = false);
                      },
                child: Text(l.finishSetup),
              ),
            ]),
          );
        },
      ),
    );
  }
}

class UnlockScreen extends StatefulWidget {
  const UnlockScreen({super.key});

  @override
  State<UnlockScreen> createState() => _UnlockScreenState();
}

class _UnlockScreenState extends State<UnlockScreen> {
  final _pin = TextEditingController();
  String? _error;
  bool _busy = false;

  Future<void> _unlock() async {
    final agent = context.agent;
    final l = context.l10n;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final ok = await agent.unlock(_pin.text.trim());
      if (!ok) _error = l.wrongPin;
    } on OfflineLimitError catch (e) {
      _error = e.message;
    }
    if (mounted) {
      setState(() => _busy = false);
      _pin.clear();
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final agent = context.agent;
    return Scaffold(
      appBar: AppBar(title: Text(l.unlockTitle), actions: [
        IconButton(tooltip: l.signOut, onPressed: agent.signOut, icon: const Icon(Icons.logout)),
      ]),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 320),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            const Icon(Icons.lock_outline, size: 48),
            const SizedBox(height: 8),
            Text(agent.profile?.agentName ?? ''),
            const SizedBox(height: 16),
            TextField(
              key: const Key('unlockPin'),
              controller: _pin,
              obscureText: true,
              maxLength: 6,
              keyboardType: TextInputType.number,
              textAlign: TextAlign.center,
              decoration: InputDecoration(labelText: l.appPin, errorText: _error),
              onSubmitted: (_) => _unlock(),
            ),
            FilledButton(key: const Key('unlock'), onPressed: _busy ? null : _unlock, child: Text(l.unlock)),
          ]),
        ),
      ),
    );
  }
}
