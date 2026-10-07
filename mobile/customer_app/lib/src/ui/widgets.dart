import 'package:flutter/material.dart';

import '../../l10n/app_localizations.dart';
import '../api/api_client.dart';
import '../app_services.dart';
import '../auth/auth_controller.dart';
import '../auth/authenticator.dart';
import '../core/money.dart';

extension L10nX on BuildContext {
  AppLocalizations get l10n => AppLocalizations.of(this);
  AppServices get services => AppServices.of(this);
}

/// Loads data with [load] and builds it; shows progress, errors with retry, and supports pull-to-refresh.
class Loader<T> extends StatefulWidget {
  const Loader({super.key, required this.load, required this.builder, this.isEmpty});

  final Future<T> Function() load;
  final Widget Function(BuildContext context, T data, Future<void> Function() reload) builder;
  final bool Function(T data)? isEmpty;

  @override
  State<Loader<T>> createState() => _LoaderState<T>();
}

class _LoaderState<T> extends State<Loader<T>> {
  late Future<T> _future;

  @override
  void initState() {
    super.initState();
    _future = widget.load();
  }

  Future<void> _reload() async {
    final f = widget.load();
    setState(() {
      _future = f;
    });
    try {
      await f;
    } catch (_) {
      // shown by the builder
    }
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<T>(
      future: _future,
      builder: (context, snap) {
        if (snap.connectionState != ConnectionState.done) {
          return const Center(child: Padding(padding: EdgeInsets.all(24), child: CircularProgressIndicator()));
        }
        if (snap.hasError) return ErrorPanel(error: snap.error!, onRetry: _reload);
        final data = snap.data as T;
        if (widget.isEmpty?.call(data) ?? false) {
          return EmptyPanel(onRefresh: _reload);
        }
        return widget.builder(context, data, _reload);
      },
    );
  }
}

class ErrorPanel extends StatelessWidget {
  const ErrorPanel({super.key, required this.error, this.onRetry});
  final Object error;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          Icon(Icons.error_outline, color: Theme.of(context).colorScheme.error, size: 40),
          const SizedBox(height: 8),
          Text(describeError(context, error), textAlign: TextAlign.center),
          if (onRetry != null) ...[
            const SizedBox(height: 12),
            FilledButton.tonal(onPressed: onRetry, child: Text(context.l10n.retry)),
          ],
        ]),
      ),
    );
  }
}

class EmptyPanel extends StatelessWidget {
  const EmptyPanel({super.key, this.text, this.onRefresh});
  final String? text;
  final VoidCallback? onRefresh;

  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            const Icon(Icons.inbox_outlined, size: 40),
            const SizedBox(height: 8),
            Text(text ?? context.l10n.empty),
            if (onRefresh != null) TextButton(onPressed: onRefresh, child: Text(context.l10n.retry)),
          ]),
        ),
      );
}

String describeError(BuildContext context, Object e) {
  final l = context.l10n;
  if (e is StepUpUnavailable) return l.stepUpUnavailable;
  if (e is DeviceCompromised) return l.deviceCompromised;
  if (e is ApiException) {
    final fields = e.problem.errors.map((f) => '${f.field}: ${f.message}').join('\n');
    return [e.toString(), if (fields.isNotEmpty) fields].join('\n');
  }
  return l.errorGeneric;
}

void showMessage(BuildContext context, String text) =>
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(text)));

/// Runs a user action, showing failures as a snack bar. Returns null on failure or cancellation.
Future<T?> runAction<T>(BuildContext context, Future<T> Function() op, {String? success}) async {
  final messenger = ScaffoldMessenger.of(context);
  String describe(Object e) => describeError(context, e);
  try {
    final r = await op();
    if (success != null) messenger.showSnackBar(SnackBar(content: Text(success)));
    return r;
  } on SignInCancelled {
    return null;
  } catch (e) {
    messenger.showSnackBar(SnackBar(content: Text(describe(e))));
    return null;
  }
}

/// Runs a sensitive operation under step-up. When a fresh strong authentication is needed, first explains why the
/// browser is about to ask for the second factor; then AuthController.withStepUp handles the rest (including the
/// server's 401 step_up_required challenge and one retry with the same Idempotency-Key).
Future<T?> runWithStepUp<T>(BuildContext context, Future<T> Function() op, {String? success}) async {
  final auth = context.services.auth;
  if (!auth.stepUpFresh) {
    final ok = await confirmDialog(context, title: context.l10n.stepUpTitle, body: context.l10n.stepUpExplain);
    if (!ok || !context.mounted) return null;
  }
  return runAction(context, () => auth.withStepUp(op), success: success);
}

Future<bool> confirmDialog(BuildContext context, {required String title, String? body, String? confirmLabel}) async {
  final l = context.l10n;
  final r = await showDialog<bool>(
    context: context,
    builder: (c) => AlertDialog(
      title: Text(title),
      content: body == null ? null : Text(body),
      actions: [
        TextButton(onPressed: () => Navigator.pop(c, false), child: Text(l.cancel)),
        FilledButton(onPressed: () => Navigator.pop(c, true), child: Text(confirmLabel ?? l.confirm)),
      ],
    ),
  );
  return r ?? false;
}

class MoneyText extends StatelessWidget {
  const MoneyText(this.amount, this.currency, {super.key, this.style, this.signed = false, this.credit = false});
  final String? amount;
  final String? currency;
  final TextStyle? style;
  final bool signed;
  final bool credit;

  @override
  Widget build(BuildContext context) {
    final locale = Localizations.localeOf(context).toLanguageTag();
    final text = Money.format(amount, currency, locale: locale);
    final color = signed && credit ? Colors.green.shade700 : null;
    return Text(signed ? '${credit ? '+' : '-'}$text' : text,
        style: (style ?? const TextStyle()).copyWith(color: color, fontFeatures: const [FontFeature.tabularFigures()]));
  }
}

class StatusChip extends StatelessWidget {
  const StatusChip(this.status, {super.key});
  final String status;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final good = {'ACTIVE', 'SETTLED', 'COMPLETED', 'PAID', 'POSTED', 'OPEN'}.contains(status);
    final bad = {'FROZEN', 'BLOCKED', 'REJECTED', 'FAILED', 'OVERDUE', 'IN_ARREARS', 'LOST', 'STOLEN'}.contains(status);
    return Chip(
      label: Text(status.replaceAll('_', ' ').toLowerCase()),
      visualDensity: VisualDensity.compact,
      side: BorderSide.none,
      backgroundColor: bad ? cs.errorContainer : (good ? cs.secondaryContainer : cs.surfaceContainerHighest),
    );
  }
}

class SectionHeader extends StatelessWidget {
  const SectionHeader(this.text, {super.key, this.trailing});
  final String text;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(16, 16, 8, 4),
        child: Row(children: [
          Expanded(child: Text(text, style: Theme.of(context).textTheme.titleMedium)),
          ?trailing,
        ]),
      );
}

String shortDate(BuildContext context, DateTime d) {
  final l = d.toLocal();
  return '${l.year}-${l.month.toString().padLeft(2, '0')}-${l.day.toString().padLeft(2, '0')}';
}

/// Validator for decimal amount fields.
String? Function(String?) amountValidator(BuildContext context) => (v) {
      if (v == null || v.trim().isEmpty) return context.l10n.required;
      if (!Money.isAmount(v) || Money.toMinor(v) <= BigInt.zero) return context.l10n.invalidAmount;
      return null;
    };

String? Function(String?) requiredValidator(BuildContext context) =>
    (v) => v == null || v.trim().isEmpty ? context.l10n.required : null;
