import 'package:flutter/material.dart';

import '../../auth/authenticator.dart';
import '../../config.dart';
import '../widgets.dart';

/// Start screen: biometric unlock of the saved sign-in, browser sign-in (OIDC + PKCE), or the dev-mode form.
class SignInScreen extends StatefulWidget {
  const SignInScreen({super.key});

  @override
  State<SignInScreen> createState() => _SignInScreenState();
}

class _SignInScreenState extends State<SignInScreen> {
  final _form = GlobalKey<FormState>();
  late final TextEditingController _tenant;
  final _customer = TextEditingController(text: '0199a000-0000-7000-8000-0000000c0001');
  final _user = TextEditingController(text: 'demo.customer');
  bool _busy = false;

  bool _init = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_init) {
      _init = true;
      _tenant = TextEditingController(text: context.services.config.defaultTenant);
    }
  }

  Future<void> _go(Future<void> Function() op) async {
    setState(() => _busy = true);
    context.services.sessionEnded.value = false;
    await runAction(context, op);
    if (mounted) setState(() => _busy = false);
  }

  @override
  Widget build(BuildContext context) {
    final s = context.services;
    final l = context.l10n;
    final dev = s.config.authMode == AuthMode.dev;
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 420),
              child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                Icon(Icons.account_balance, size: 56, color: Theme.of(context).colorScheme.primary),
                const SizedBox(height: 12),
                Text(l.appTitle, textAlign: TextAlign.center, style: Theme.of(context).textTheme.headlineMedium),
                const SizedBox(height: 24),
                ValueListenableBuilder(
                  valueListenable: s.sessionEnded,
                  builder: (context, ended, _) => ended
                      ? Card(child: ListTile(leading: const Icon(Icons.info_outline), title: Text(l.sessionEnded)))
                      : const SizedBox.shrink(),
                ),
                if (s.auth.integrityVerdict.compromised)
                  Card(
                    color: Theme.of(context).colorScheme.errorContainer,
                    child: ListTile(leading: const Icon(Icons.gpp_bad_outlined), title: Text(l.deviceCompromised)),
                  ),
                if (s.auth.canUnlock) ...[
                  FilledButton.icon(
                    key: const Key('unlock'),
                    icon: const Icon(Icons.fingerprint),
                    onPressed: _busy ? null : () => _go(() => s.auth.unlock(l.unlockReason)),
                    label: Text(l.unlock),
                  ),
                  const SizedBox(height: 12),
                ],
                if (dev)
                  Form(
                    key: _form,
                    child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                      Text(l.devModeHint, style: Theme.of(context).textTheme.bodySmall),
                      const SizedBox(height: 12),
                      TextFormField(controller: _tenant, decoration: InputDecoration(labelText: l.tenant),
                          validator: requiredValidator(context)),
                      const SizedBox(height: 12),
                      TextFormField(key: const Key('customerId'), controller: _customer,
                          decoration: InputDecoration(labelText: l.customerId), validator: requiredValidator(context)),
                      const SizedBox(height: 12),
                      TextFormField(controller: _user, decoration: InputDecoration(labelText: l.username)),
                      const SizedBox(height: 16),
                    ]),
                  ),
                _SignInButton(
                  secondary: s.auth.canUnlock,
                  label: s.auth.canUnlock ? l.useAnotherAccount : (dev ? l.signIn : l.signInWithBank),
                  onPressed: _busy
                      ? null
                      : () {
                          if (dev && !(_form.currentState?.validate() ?? false)) return;
                          _go(() => s.auth.signIn(
                              dev: dev
                                  ? DevIdentity(
                                      tenantId: _tenant.text.trim(),
                                      customerId: _customer.text.trim(),
                                      user: _user.text.trim())
                                  : null));
                        },
                ),
                if (_busy) const Padding(padding: EdgeInsets.all(16), child: Center(child: CircularProgressIndicator())),
              ]),
            ),
          ),
        ),
      ),
    );
  }
}

class _SignInButton extends StatelessWidget {
  const _SignInButton({required this.secondary, required this.label, required this.onPressed});
  final bool secondary;
  final String label;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) => secondary
      ? OutlinedButton(key: const Key('signIn'), onPressed: onPressed, child: Text(label))
      : FilledButton(key: const Key('signIn'), onPressed: onPressed, child: Text(label));
}
