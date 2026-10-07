import 'package:flutter/material.dart';

import '../../api/models.dart';
import '../../core/money.dart';
import '../widgets.dart';

/// Spending by category this month and the customer's insight cards.
class InsightsScreen extends StatelessWidget {
  const InsightsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final api = context.services.api;
    final now = DateTime.now();
    final from = DateTime(now.year, now.month);
    return Scaffold(
      appBar: AppBar(title: Text(l.insights)),
      body: Loader<(List<SpendingCategory>, List<FinancialInsight>)>(
        load: () async => (await api.spending(from: from, to: now), await api.insightCards()),
        builder: (context, d, reload) {
          final spend = d.$1.where((c) => !c.isIncome && Money.compare(c.spend, '0') > 0).toList()
            ..sort((a, b) => Money.compare(b.spend, a.spend));
          final max = spend.isEmpty ? BigInt.one : Money.toMinor(spend.first.spend);
          return RefreshIndicator(
            onRefresh: reload,
            child: ListView(children: [
              for (final i in d.$2)
                Card(
                  child: ListTile(
                    leading: Icon(switch (i.severity) {
                      'WARNING' || 'CRITICAL' => Icons.warning_amber_outlined,
                      'POSITIVE' => Icons.thumb_up_alt_outlined,
                      _ => Icons.lightbulb_outline,
                    }),
                    title: Text(i.title),
                    subtitle: Text(i.body),
                    trailing: IconButton(
                      tooltip: l.dismiss,
                      icon: const Icon(Icons.close),
                      onPressed: () async {
                        await runAction(context, () => api.dismissInsight(i.id));
                        reload();
                      },
                    ),
                  ),
                ),
              SectionHeader(l.spending),
              if (spend.isEmpty) EmptyPanel(text: l.noSpending),
              for (final c in spend)
                ListTile(
                  title: Text(c.name),
                  trailing: MoneyText(c.spend, c.currency),
                  subtitle: Padding(
                    padding: const EdgeInsets.only(top: 6),
                    child: LinearProgressIndicator(
                      value: (Money.toMinor(c.spend) * BigInt.from(1000) ~/ max).toInt() / 1000,
                      minHeight: 6,
                      borderRadius: BorderRadius.circular(3),
                    ),
                  ),
                ),
              const SizedBox(height: 24),
            ]),
          );
        },
      ),
    );
  }
}
