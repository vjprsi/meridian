import 'package:flutter/material.dart';

import '../../l10n/app_localizations.dart';
import '../agent_controller.dart';
import '../api/agent_api.dart';
import '../auth/auth.dart';
import '../core/money.dart';
import '../outbox/outbox.dart';
import '../outbox/types.dart';

class AgentScope extends InheritedNotifier<AgentController> {
  const AgentScope({super.key, required AgentController controller, required super.child}) : super(notifier: controller);

  static AgentController of(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<AgentScope>()!.notifier!;
}

extension Ctx on BuildContext {
  AppLocalizations get l10n => AppLocalizations.of(this);
  AgentController get agent => AgentScope.of(this);
}

String describeError(BuildContext context, Object e) {
  if (e is ApiException || e is OfflineLimitError || e is OutboxLockedError) return e.toString();
  if (e is ArgumentError) return '${e.message}';
  if (e is StateError) return e.message;
  return context.l10n.errorGeneric;
}

void showMessage(BuildContext context, String text) =>
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(text)));

Future<T?> runAction<T>(BuildContext context, Future<T> Function() op) async {
  final messenger = ScaffoldMessenger.of(context);
  String describe(Object e) => describeError(context, e);
  try {
    return await op();
  } on SignInCancelled {
    return null;
  } catch (e) {
    messenger.showSnackBar(SnackBar(content: Text(describe(e))));
    return null;
  }
}

String? Function(String?) amountValidator(BuildContext context) => (v) {
      if (v == null || v.trim().isEmpty) return context.l10n.required;
      if (!Money.isAmount(v) || Money.toMinor(v) <= BigInt.zero) return context.l10n.invalidAmount;
      return null;
    };

String? Function(String?) requiredValidator(BuildContext context) =>
    (v) => v == null || v.trim().isEmpty ? context.l10n.required : null;

String statusLabel(BuildContext context, OpStatus s) => switch (s) {
      OpStatus.pending => context.l10n.statusPending,
      OpStatus.synced => context.l10n.statusSynced,
      OpStatus.rejected => context.l10n.statusRejected,
      OpStatus.needsReview => context.l10n.statusNeedsReview,
    };

String kindLabel(BuildContext context, OpKind k) => switch (k) {
      OpKind.cashIn => context.l10n.cashIn,
      OpKind.billPayment => context.l10n.billPay,
      OpKind.floatRequest => context.l10n.floatRequest,
    };

Future<String?> askPin(BuildContext context, {required String title, String? hint, int? length}) async {
  final c = TextEditingController();
  return showDialog<String>(
    context: context,
    builder: (d) => AlertDialog(
      title: Text(title),
      content: TextField(
        key: const Key('pinDialogField'),
        controller: c,
        autofocus: true,
        obscureText: true,
        keyboardType: TextInputType.number,
        maxLength: length,
        decoration: InputDecoration(helperText: hint),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(d), child: Text(context.l10n.cancel)),
        FilledButton(onPressed: () => Navigator.pop(d, c.text.trim()), child: Text(context.l10n.confirm)),
      ],
    ),
  );
}

/// Shows what happened to a captured operation. When the server asked for the agent wallet PIN, asks for it (kept
/// in memory only) and syncs again.
Future<void> reportCapture(BuildContext context, CaptureResult r) async {
  final l = context.l10n;
  final agent = context.agent;
  var status = r.view.record.status;
  if (status == OpStatus.pending && r.view.record.blocker == Blocker.needsPin) {
    final pin = await askPin(context, title: l.agentPin, hint: l.agentPinHint);
    if (pin == null || pin.isEmpty || !context.mounted) return;
    agent.setAgentPin(pin);
    await agent.sync();
    final rec = await agent.outbox.store.get(r.record.id);
    status = rec?.status ?? status;
    if (!context.mounted) return;
  }
  final text = switch (status) {
    OpStatus.synced => l.completed,
    OpStatus.pending => l.queued,
    OpStatus.rejected => l.rejected(r.view.result?.problem?.toString() ?? ''),
    OpStatus.needsReview => l.statusNeedsReview,
  };
  showMessage(context, text);
}
