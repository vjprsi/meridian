import 'package:flutter/material.dart';

import '../../api/models.dart';
import '../widgets.dart';

/// Sign-in strength and second factors, biometric unlock, signed-in devices (sign out one or everywhere).
class SecurityScreen extends StatefulWidget {
  const SecurityScreen({super.key});

  @override
  State<SecurityScreen> createState() => _SecurityScreenState();
}

class _SecurityScreenState extends State<SecurityScreen> {
  bool? _biometric;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_biometric == null) {
      context.services.auth.biometricEnabled().then((v) {
        if (mounted) setState(() => _biometric = v);
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final s = context.services;
    final api = s.api;
    return Scaffold(
      appBar: AppBar(title: Text(l.security)),
      body: Loader<(SecurityOverview?, List<CustomerSession>)>(
        load: () async {
          final overview = await api.security().then<SecurityOverview?>((v) => v).catchError((Object _) => null);
          return (overview, await api.sessions());
        },
        builder: (context, d, reload) {
          final (overview, sessions) = d;
          return RefreshIndicator(
            onRefresh: reload,
            child: ListView(children: [
              if (s.auth.integrityVerdict.compromised)
                Card(
                  color: Theme.of(context).colorScheme.errorContainer,
                  child: ListTile(leading: const Icon(Icons.gpp_bad_outlined), title: Text(l.deviceCompromised)),
                ),
              if (overview != null) ...[
                ListTile(leading: const Icon(Icons.verified_user_outlined), title: Text(l.authLevel(overview.level)),
                    subtitle: overview.amr.isEmpty ? null : Text(overview.amr.join(', '))),
                ListTile(
                  leading: const Icon(Icons.key_outlined),
                  title: Text(l.secondFactors),
                  subtitle: Text(overview.factors.isEmpty ? l.noSecondFactor : overview.factors.join('\n')),
                ),
              ],
              SwitchListTile(
                key: const Key('biometric'),
                secondary: const Icon(Icons.fingerprint),
                title: Text(l.biometricUnlock),
                subtitle: s.auth.biometricAvailable ? null : Text(l.biometricUnavailable),
                value: _biometric ?? false,
                onChanged: !s.auth.biometricAvailable
                    ? null
                    : (v) async {
                        await s.auth.setBiometric(v);
                        setState(() => _biometric = v);
                      },
              ),
              SectionHeader(l.sessions),
              for (final x in sessions.where((x) => x.active))
                ListTile(
                  leading: Icon(x.isCurrent ? Icons.smartphone : Icons.devices_other),
                  title: Text(x.isCurrent ? l.thisDevice : (x.deviceLabel ?? x.userAgent ?? x.sessionId)),
                  subtitle: Text([shortDate(context, x.lastSeenAt), ?x.ipAddress].join(' · ')),
                  trailing: x.isCurrent
                      ? null
                      : TextButton(
                          onPressed: () async {
                            await runAction(context, () => api.revokeSession(x.sessionId));
                            reload();
                          },
                          child: Text(l.signOut)),
                ),
              const SizedBox(height: 16),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: OutlinedButton.icon(
                  key: const Key('signOutEverywhere'),
                  icon: const Icon(Icons.logout),
                  label: Text(l.signOutEverywhere),
                  style: OutlinedButton.styleFrom(foregroundColor: Theme.of(context).colorScheme.error),
                  onPressed: () async {
                    if (!await confirmDialog(context, title: l.signOutEverywhere, body: l.signOutEverywhereConfirm)) return;
                    if (!context.mounted) return;
                    final nav = Navigator.of(context);
                    final done = await runAction(context, () async {
                      await api.revokeAllSessions();
                      return true;
                    });
                    if (done == true) {
                      nav.popUntil((r) => r.isFirst);
                      await s.auth.signOut(local: true);
                    }
                  },
                ),
              ),
              const SizedBox(height: 32),
            ]),
          );
        },
      ),
    );
  }
}
