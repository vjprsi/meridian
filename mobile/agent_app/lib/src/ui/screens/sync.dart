import 'package:flutter/material.dart';

import '../../outbox/types.dart';
import '../widgets.dart';
import 'operations.dart';

/// The outbox: what waits to be sent (in capture order), what was sent or refused; sync now; remove final records.
class SyncScreen extends StatefulWidget {
  const SyncScreen({super.key});

  @override
  State<SyncScreen> createState() => _SyncScreenState();
}

class _SyncScreenState extends State<SyncScreen> {
  bool _busy = false;
  int _rev = 0;

  Future<void> _sync() async {
    final agent = context.agent;
    final l = context.l10n;
    setState(() => _busy = true);
    var r = await runAction(context, agent.sync);
    if (r?.blocked == Blocker.needsPin && mounted) {
      final pin = await askPin(context, title: l.agentPin, hint: l.agentPinHint);
      if (pin != null && pin.isNotEmpty && mounted) {
        agent.setAgentPin(pin);
        r = await runAction(context, agent.sync);
      }
    }
    if (!mounted) return;
    setState(() {
      _busy = false;
      _rev++;
    });
    if (r != null) {
      showMessage(context, r.blocked == null || r.blocked == Blocker.needsPin
          ? l.syncResult(r.synced, r.remaining)
          : '${l.syncResult(r.synced, r.remaining)} · ${l.blocked(r.blocked!.name)}');
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final agent = context.agent;
    return Scaffold(
      appBar: AppBar(title: Text(l.sync)),
      floatingActionButton: FloatingActionButton.extended(
        key: const Key('syncNow'),
        onPressed: _busy ? null : _sync,
        icon: const Icon(Icons.sync),
        label: Text(l.syncNow),
      ),
      body: FutureBuilder<List<OutboxView>>(
        key: ValueKey(_rev),
        future: agent.outbox.list(),
        builder: (context, snap) {
          if (snap.hasError) return Center(child: Text(describeError(context, snap.error!)));
          final rows = snap.data ?? const <OutboxView>[];
          final pending = rows.where((v) => v.record.status == OpStatus.pending).toList();
          final done = rows.where((v) => v.record.status != OpStatus.pending).toList().reversed;
          return ListView(padding: const EdgeInsets.only(bottom: 96), children: [
            ListTile(
              leading: Icon(agent.online ? Icons.cloud_done_outlined : Icons.cloud_off),
              title: Text(pending.isEmpty ? l.nothingPending : l.pendingCount(pending.length)),
              subtitle: Text(agent.online ? l.online : l.offline),
            ),
            for (final v in pending) OutboxTile(view: v),
            if (done.isNotEmpty) const Divider(),
            for (final v in done)
              OutboxTile(
                view: v,
                trailing: IconButton(
                  tooltip: l.remove,
                  icon: const Icon(Icons.delete_outline),
                  onPressed: () async {
                    await agent.outbox.remove(v.record.id);
                    setState(() => _rev++);
                  },
                ),
              ),
          ]);
        },
      ),
    );
  }
}
