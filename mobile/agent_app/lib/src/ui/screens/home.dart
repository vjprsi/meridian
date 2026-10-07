import 'package:flutter/material.dart';

import '../../core/money.dart';
import '../../outbox/types.dart';
import '../widgets.dart';
import 'operations.dart';
import 'sync.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final agent = context.agent;
    final p = agent.profile!;
    void open(Widget w) => Navigator.push(context, MaterialPageRoute(builder: (_) => w));
    final actions = <(IconData, String, Widget)>[
      (Icons.south_west, l.cashIn, const CashInScreen()),
      (Icons.north_east, l.cashOut, const CashOutScreen()),
      (Icons.receipt_long, l.billPay, const BillPayScreen()),
      (Icons.account_balance_wallet_outlined, l.floatRequest, const FloatRequestScreen()),
      (Icons.history, l.history, const HistoryScreen()),
      (Icons.sync, l.sync, const SyncScreen()),
    ];
    return Scaffold(
      appBar: AppBar(
        title: Text(p.agentName.isEmpty ? l.appTitle : p.agentName),
        actions: [
          IconButton(tooltip: l.lock, onPressed: agent.lock, icon: const Icon(Icons.lock_outline)),
          IconButton(tooltip: l.signOut, onPressed: agent.signOut, icon: const Icon(Icons.logout)),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: () async {
          await agent.probe();
          await agent.refreshFloat();
        },
        child: ListView(padding: const EdgeInsets.all(16), children: [
          Card(
            margin: EdgeInsets.zero,
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Row(children: [
                  Text(l.float, style: Theme.of(context).textTheme.bodySmall),
                  const Spacer(),
                  Chip(
                    key: const Key('connectivity'),
                    avatar: Icon(agent.online ? Icons.cloud_done_outlined : Icons.cloud_off, size: 18),
                    label: Text(agent.online ? l.online : l.offline),
                  ),
                ]),
                Text(Money.format(agent.floatAvailable, p.currency), style: Theme.of(context).textTheme.headlineMedium),
                FutureBuilder(
                  future: agent.outbox.all(),
                  builder: (context, snap) {
                    final n = snap.data?.where((r) => r.status == OpStatus.pending).length ?? 0;
                    return n == 0
                        ? const SizedBox.shrink()
                        : TextButton.icon(
                            onPressed: () => open(const SyncScreen()),
                            icon: const Icon(Icons.schedule_send),
                            label: Text(l.pendingCount(n)));
                  },
                ),
              ]),
            ),
          ),
          const SizedBox(height: 16),
          GridView.count(
            crossAxisCount: 3,
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            mainAxisSpacing: 8,
            crossAxisSpacing: 8,
            children: [
              for (final (icon, label, page) in actions)
                Card(
                  margin: EdgeInsets.zero,
                  child: InkWell(
                    key: Key('action-$label'),
                    borderRadius: BorderRadius.circular(12),
                    onTap: () => open(page),
                    child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
                      Icon(icon, size: 32, color: Theme.of(context).colorScheme.primary),
                      const SizedBox(height: 8),
                      Text(label, textAlign: TextAlign.center),
                    ]),
                  ),
                ),
            ],
          ),
        ]),
      ),
    );
  }
}
