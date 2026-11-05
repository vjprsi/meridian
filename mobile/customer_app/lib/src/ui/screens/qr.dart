import 'package:flutter/material.dart';

import '../../api/models.dart';
import '../../core/ids.dart';
import '../widgets.dart';
import 'wallet.dart';

/// Scan to pay: for the demo the payload is pasted/typed, decoded by the BFF, then confirmed and paid under step-up.
class ScanToPayScreen extends StatefulWidget {
  const ScanToPayScreen({super.key, required this.wallet});
  final Wallet wallet;

  @override
  State<ScanToPayScreen> createState() => _ScanToPayScreenState();
}

class _ScanToPayScreenState extends State<ScanToPayScreen> {
  final _payload = TextEditingController();
  final _amount = TextEditingController();
  QrDecoded? _decoded;
  bool _busy = false;

  @override
  void dispose() {
    _payload.dispose();
    _amount.dispose();
    super.dispose();
  }

  Future<void> _decode() async {
    final payload = _payload.text.trim();
    if (payload.isEmpty) return;
    setState(() => _busy = true);
    final decoded = await runAction(context, () => context.services.api.decodeQr(payload));
    if (!mounted) return;
    setState(() {
      _decoded = decoded;
      if (decoded?.amount != null) _amount.text = decoded!.amount!;
      _busy = false;
    });
  }

  Future<void> _pay() async {
    final d = _decoded;
    if (d == null) return;
    final l = context.l10n;
    final api = context.services.api;
    final amount = d.amountFixed ? d.amount! : _amount.text.trim();
    if (amount.isEmpty) {
      showMessage(context, l.invalidAmount);
      return;
    }
    final ok = await walletConfirmSheet(context,
        title: l.scanToPay,
        amount: amount,
        currency: d.currency ?? widget.wallet.currency,
        rows: [
          (l.payee, d.title),
          if (d.merchantTill != null) (l.tillNumber, d.merchantTill!),
          if (d.note != null) (l.note, d.note!),
        ]);
    if (!ok || !mounted) return;
    final key = uuidV4();
    setState(() => _busy = true);
    final txn = await runWithStepUp(
      context,
      () => api.payQr(
        walletId: widget.wallet.id,
        payload: d.payload,
        amount: d.amountFixed ? null : amount,
        idempotencyKey: key,
      ),
    );
    if (!mounted) return;
    setState(() => _busy = false);
    if (txn != null) {
      showMessage(context, l.paymentSent);
      Navigator.pop(context);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final d = _decoded;
    return Scaffold(
      appBar: AppBar(title: Text(l.scanToPay)),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        TextField(
          key: const Key('qrPayload'),
          controller: _payload,
          minLines: 2,
          maxLines: 4,
          decoration: InputDecoration(
            labelText: l.qrData,
            helperText: l.pasteQrHint,
            border: const OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 12),
        FilledButton.tonal(onPressed: _busy ? null : _decode, child: Text(l.continueLabel)),
        if (d != null) ...[
          const SizedBox(height: 16),
          Card(
            child: ListTile(
              leading: const Icon(Icons.qr_code_2),
              title: Text(d.title),
              subtitle: Text([if (d.type != null) d.type!, if (d.merchantTill != null) d.merchantTill!].join(' · ')),
            ),
          ),
          const SizedBox(height: 16),
          if (!d.amountFixed)
            TextField(
              key: const Key('amount'),
              controller: _amount,
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              decoration: InputDecoration(labelText: l.amount),
            )
          else
            Center(child: MoneyText(d.amount, d.currency ?? widget.wallet.currency, style: Theme.of(context).textTheme.headlineSmall)),
          const SizedBox(height: 12),
          FilledButton(key: const Key('payQr'), onPressed: _busy ? null : _pay, child: Text(l.payNow)),
        ],
      ]),
    );
  }
}

/// My QR: generate a payload for this wallet (optionally with a fixed amount/note) for someone else to scan and pay.
class MyQrScreen extends StatefulWidget {
  const MyQrScreen({super.key, required this.wallet});
  final Wallet wallet;

  @override
  State<MyQrScreen> createState() => _MyQrScreenState();
}

class _MyQrScreenState extends State<MyQrScreen> {
  final _amount = TextEditingController();
  final _note = TextEditingController();
  QrCode? _qr;
  bool _busy = false;

  @override
  void dispose() {
    _amount.dispose();
    _note.dispose();
    super.dispose();
  }

  Future<void> _generate() async {
    setState(() => _busy = true);
    final qr = await runAction(
      context,
      () => context.services.api.generateQr(
          walletId: widget.wallet.id, amount: _amount.text.trim(), note: _note.text.trim()),
    );
    if (!mounted) return;
    setState(() {
      _qr = qr;
      _busy = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final qr = _qr;
    return Scaffold(
      appBar: AppBar(title: Text(l.myQrTitle)),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        Text('${widget.wallet.displayName} · ${widget.wallet.walletNumber}', style: Theme.of(context).textTheme.bodyMedium),
        const SizedBox(height: 16),
        TextField(
          controller: _amount,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: InputDecoration(labelText: l.amountOptional),
        ),
        const SizedBox(height: 12),
        TextField(controller: _note, maxLength: 140, decoration: InputDecoration(labelText: l.note)),
        const SizedBox(height: 8),
        FilledButton(onPressed: _busy ? null : _generate, child: Text(l.createQr)),
        if (qr != null) ...[
          const SizedBox(height: 24),
          Center(
            child: Column(children: [
              // Placeholder visual: the payload itself is shown below for the demo (no QR-rendering dependency).
              Container(
                width: 220,
                height: 220,
                decoration: BoxDecoration(
                  color: Theme.of(context).colorScheme.surfaceContainerHighest,
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(color: Theme.of(context).colorScheme.outlineVariant),
                ),
                child: const Center(child: Icon(Icons.qr_code_2, size: 160)),
              ),
              const SizedBox(height: 12),
              Text(l.showThisQr, style: Theme.of(context).textTheme.bodySmall),
              if (qr.amount != null && qr.amount!.isNotEmpty) ...[
                const SizedBox(height: 8),
                MoneyText(qr.amount, qr.currency ?? widget.wallet.currency, style: Theme.of(context).textTheme.titleLarge),
              ],
              const SizedBox(height: 12),
              SelectableText(qr.payload, textAlign: TextAlign.center, style: const TextStyle(fontFamily: 'monospace')),
            ]),
          ),
        ],
      ]),
    );
  }
}
