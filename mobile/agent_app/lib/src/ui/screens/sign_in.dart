import 'package:flutter/material.dart';

import '../../auth/auth.dart';
import '../../config.dart';
import '../widgets.dart';

class SignInScreen extends StatefulWidget {
  const SignInScreen({super.key});

  @override
  State<SignInScreen> createState() => _SignInScreenState();
}

class _SignInScreenState extends State<SignInScreen> {
  final _form = GlobalKey<FormState>();
  TextEditingController? _tenant;
  final _agent = TextEditingController(text: '0199a000-0000-7000-8000-0000000a0001');
  final _user = TextEditingController(text: 'demo.agent');
  final _userId = TextEditingController(text: '0199a000-0000-7000-8000-0000000a0f01');
  bool _busy = false;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final agent = context.agent;
    final dev = agent.config.authMode == AuthMode.dev;
    _tenant ??= TextEditingController(text: agent.config.defaultTenant);
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 420),
              child: Form(
                key: _form,
                child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                  Icon(Icons.storefront, size: 56, color: Theme.of(context).colorScheme.primary),
                  const SizedBox(height: 12),
                  Text(l.appTitle, textAlign: TextAlign.center, style: Theme.of(context).textTheme.headlineMedium),
                  const SizedBox(height: 24),
                  if (dev) ...[
                    Text(l.devModeHint, style: Theme.of(context).textTheme.bodySmall),
                    const SizedBox(height: 12),
                    TextFormField(controller: _tenant, decoration: InputDecoration(labelText: l.tenant),
                        validator: requiredValidator(context)),
                    const SizedBox(height: 12),
                    TextFormField(key: const Key('agentId'), controller: _agent,
                        decoration: InputDecoration(labelText: l.agentId), validator: requiredValidator(context)),
                    const SizedBox(height: 12),
                    TextFormField(controller: _user, decoration: InputDecoration(labelText: l.username)),
                    const SizedBox(height: 12),
                    TextFormField(controller: _userId, decoration: InputDecoration(labelText: l.userId)),
                    const SizedBox(height: 16),
                  ],
                  FilledButton(
                    key: const Key('signIn'),
                    onPressed: _busy
                        ? null
                        : () async {
                            if (dev && !(_form.currentState?.validate() ?? false)) return;
                            setState(() => _busy = true);
                            await runAction(
                                context,
                                () => agent.signIn(
                                    dev: dev
                                        ? DevAgentIdentity(
                                            tenantId: _tenant!.text.trim(),
                                            agentId: _agent.text.trim(),
                                            user: _user.text.trim(),
                                            userId: _userId.text.trim())
                                        : null));
                            if (mounted) setState(() => _busy = false);
                          },
                    child: Text(dev ? l.signIn : l.signInWithBank),
                  ),
                ]),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
