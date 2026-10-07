import 'package:flutter/material.dart';

import '../../api/models.dart';
import '../../core/ids.dart';
import '../widgets.dart';

class CardsScreen extends StatelessWidget {
  const CardsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final api = context.services.api;
    return Scaffold(
      appBar: AppBar(title: Text(l.cards)),
      body: Loader<List<PaymentCard>>(
        load: api.cards,
        isEmpty: (d) => d.isEmpty,
        builder: (context, cards, reload) => RefreshIndicator(
          onRefresh: reload,
          child: ListView(
            children: [
              for (final c in cards)
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
                  child: InkWell(
                    borderRadius: BorderRadius.circular(16),
                    onTap: () async {
                      await Navigator.push(context, MaterialPageRoute(builder: (_) => CardDetailScreen(cardId: c.id)));
                      reload();
                    },
                    child: CardFace(card: c),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Visual of a card (no PAN: only the last four digits ever reach the app).
class CardFace extends StatelessWidget {
  const CardFace({super.key, required this.card});
  final PaymentCard card;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final frozen = card.frozen;
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 400),
        child: AspectRatio(
          aspectRatio: 1.586,
          child: DecoratedBox(
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(16),
              gradient: LinearGradient(colors: frozen ? [cs.outline, cs.outlineVariant] : [cs.primary, cs.tertiary]),
            ),
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: DefaultTextStyle(
                style: TextStyle(color: cs.onPrimary, fontSize: 16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Text(card.nickname ?? card.formFactor.toLowerCase()),
                        const Spacer(),
                        if (frozen) Icon(Icons.ac_unit, color: cs.onPrimary),
                      ],
                    ),
                    const Spacer(),
                    Text('••••  ••••  ••••  ${card.last4}', style: const TextStyle(fontSize: 22, letterSpacing: 1.5)),
                    const SizedBox(height: 8),
                    Row(
                      children: [
                        Expanded(child: Text(card.cardholderName)),
                        Text(card.expiry),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

const _channels = ['ECOM', 'CONTACTLESS', 'ATM', 'POS'];

/// One card: freeze / unfreeze (unfreeze needs step-up), channel blocks, spend limits (step-up), report lost.
class CardDetailScreen extends StatefulWidget {
  const CardDetailScreen({super.key, required this.cardId});
  final String cardId;

  @override
  State<CardDetailScreen> createState() => _CardDetailScreenState();
}

class _CardDetailScreenState extends State<CardDetailScreen> {
  Key _reload = UniqueKey();

  String _channelLabel(BuildContext context, String c) => switch (c) {
    'ECOM' => context.l10n.chEcom,
    'CONTACTLESS' => context.l10n.chContactless,
    'ATM' => context.l10n.chAtm,
    _ => context.l10n.chPos,
  };

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final api = context.services.api;
    final id = widget.cardId;
    return Scaffold(
      appBar: AppBar(title: Text(l.cards)),
      body: Loader<(PaymentCard, List<CardControl>, List<CardSpendLimit>)>(
        key: _reload,
        load: () async {
          final r = await Future.wait([api.card(id), api.cardControls(id), api.cardLimits(id)]);
          return (r[0] as PaymentCard, r[1] as List<CardControl>, r[2] as List<CardSpendLimit>);
        },
        builder: (context, d, reload) {
          final (card, controls, limits) = d;
          return RefreshIndicator(
            onRefresh: reload,
            child: ListView(
              children: [
                Padding(
                  padding: const EdgeInsets.all(16),
                  child: CardFace(card: card),
                ),
                if (card.usable)
                  SwitchListTile(
                    key: const Key('freeze'),
                    secondary: const Icon(Icons.ac_unit),
                    title: Text(card.frozen ? l.frozen : l.freeze),
                    value: card.frozen,
                    onChanged: (freeze) async {
                      final key = uuidV4();
                      final r = freeze
                          ? await runAction(context, () => api.freezeCard(id))
                          : await runWithStepUp(context, () => api.unfreezeCard(id, idempotencyKey: key));
                      if (r != null) reload();
                    },
                  )
                else
                  ListTile(
                    leading: const Icon(Icons.block),
                    title: Text(l.cardBlocked),
                    trailing: StatusChip(card.status),
                  ),
                SectionHeader(l.controls),
                for (final ch in _channels)
                  SwitchListTile(
                    title: Text(_channelLabel(context, ch)),
                    value: !controls.any((c) => c.controlType == 'CHANNEL_DENY' && c.channel == ch && c.isActive),
                    onChanged: !card.usable
                        ? null
                        : (allowed) async {
                            await runAction(context, () => api.setCardChannel(id, ch, blocked: !allowed));
                            reload();
                          },
                  ),
                SectionHeader(
                  l.limits,
                  trailing: TextButton(
                    onPressed: card.usable ? () => _setLimit(context, card, limits, reload) : null,
                    child: Text(l.setLimit),
                  ),
                ),
                if (limits.isEmpty) ListTile(title: Text(l.noLimit)),
                for (final lim in limits.where((x) => x.isActive))
                  ListTile(
                    title: Text('${lim.period.replaceAll('_', ' ').toLowerCase()} · ${lim.channel.toLowerCase()}'),
                    subtitle: lim.maxAmount == null
                        ? Text(l.noLimit)
                        : Text(l.limitUsed(lim.usedAmount, lim.maxAmount!)),
                    trailing: lim.maxAmount == null ? null : MoneyText(lim.maxAmount, lim.currency),
                  ),
                const Divider(),
                if (card.usable)
                  ListTile(
                    leading: Icon(Icons.report_outlined, color: Theme.of(context).colorScheme.error),
                    title: Text(l.reportLost),
                    onTap: () => _reportLost(context, card, reload),
                  ),
                const SizedBox(height: 24),
              ],
            ),
          );
        },
      ),
    );
  }

  Future<void> _setLimit(
    BuildContext context,
    PaymentCard card,
    List<CardSpendLimit> limits,
    Future<void> Function() reload,
  ) async {
    final l = context.l10n;
    final api = context.services.api;
    final amount = TextEditingController();
    final form = GlobalKey<FormState>();
    var period = 'DAILY';
    final currency = limits.isEmpty ? null : limits.first.currency;
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => StatefulBuilder(
        builder: (c, set) => AlertDialog(
          title: Text(l.setLimit),
          content: Form(
            key: form,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                DropdownButtonFormField<String>(
                  initialValue: period,
                  decoration: InputDecoration(labelText: l.period),
                  items: const [
                    'PER_TRANSACTION',
                    'DAILY',
                    'WEEKLY',
                    'MONTHLY',
                  ].map((p) => DropdownMenuItem(value: p, child: Text(p.replaceAll('_', ' ').toLowerCase()))).toList(),
                  onChanged: (v) => set(() => period = v ?? period),
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: amount,
                  keyboardType: const TextInputType.numberWithOptions(decimal: true),
                  decoration: InputDecoration(labelText: l.amount),
                  validator: amountValidator(context),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(c, false), child: Text(l.cancel)),
            FilledButton(
              onPressed: () {
                if (form.currentState?.validate() ?? false) Navigator.pop(c, true);
              },
              child: Text(l.save),
            ),
          ],
        ),
      ),
    );
    if (ok != true || !context.mounted) return;
    final key = uuidV4();
    final r = await runWithStepUp(
      context,
      () => api.setCardLimit(
        card.id,
        period: period,
        maxAmount: amount.text.trim(),
        currency: currency,
        idempotencyKey: key,
      ),
    );
    if (r != null) reload();
  }

  Future<void> _reportLost(BuildContext context, PaymentCard card, Future<void> Function() reload) async {
    final l = context.l10n;
    final api = context.services.api;
    var reason = 'LOST';
    var replace = true;
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => StatefulBuilder(
        builder: (c, set) => AlertDialog(
          title: Text(l.reportLost),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              SegmentedButton<String>(
                segments: [
                  ButtonSegment(value: 'LOST', label: Text(l.lost)),
                  ButtonSegment(value: 'STOLEN', label: Text(l.stolen)),
                ],
                selected: {reason},
                onSelectionChanged: (s) => set(() => reason = s.first),
              ),
              CheckboxListTile(
                value: replace,
                title: Text(l.orderReplacement),
                onChanged: (v) => set(() => replace = v ?? true),
              ),
            ],
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(c, false), child: Text(l.cancel)),
            FilledButton(onPressed: () => Navigator.pop(c, true), child: Text(l.confirm)),
          ],
        ),
      ),
    );
    if (ok != true || !context.mounted) return;
    final r = await runAction(
      context,
      () => api.reportLost(card.id, reason: reason, replace: replace),
      success: l.cardBlocked,
    );
    if (r != null) setState(() => _reload = UniqueKey());
  }
}
