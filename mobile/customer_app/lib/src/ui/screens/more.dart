import 'package:flutter/material.dart';

import '../widgets.dart';
import 'inbox.dart';
import 'insights.dart';
import 'loans.dart';
import 'payees.dart';
import 'security.dart';

class MoreScreen extends StatelessWidget {
  const MoreScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final s = context.services;
    void open(Widget w) => Navigator.push(context, MaterialPageRoute(builder: (_) => w));
    return Scaffold(
      appBar: AppBar(title: Text(l.navMore)),
      body: ListView(children: [
        ListTile(leading: const Icon(Icons.request_quote_outlined), title: Text(l.loans), onTap: () => open(const LoansScreen())),
        ListTile(leading: const Icon(Icons.people_outline), title: Text(l.payees), onTap: () => open(const PayeesScreen())),
        ListTile(leading: const Icon(Icons.insights_outlined), title: Text(l.insights), onTap: () => open(const InsightsScreen())),
        ListTile(leading: const Icon(Icons.mail_outline), title: Text(l.inbox), onTap: () => open(const InboxScreen())),
        ListTile(
            key: const Key('openSecurity'),
            leading: const Icon(Icons.shield_outlined),
            title: Text(l.security),
            onTap: () => open(const SecurityScreen())),
        SectionHeader(l.settings),
        ListenableBuilder(
          listenable: s.settings,
          builder: (context, _) => Column(children: [
            ListTile(
              title: Text(l.theme),
              subtitle: Padding(
                padding: const EdgeInsets.only(top: 8),
                child: SegmentedButton<ThemeMode>(
                  segments: [
                    ButtonSegment(value: ThemeMode.system, label: Text(l.themeSystem)),
                    ButtonSegment(value: ThemeMode.light, label: Text(l.themeLight)),
                    ButtonSegment(value: ThemeMode.dark, label: Text(l.themeDark)),
                  ],
                  selected: {s.settings.themeMode},
                  onSelectionChanged: (v) => s.settings.setTheme(v.first),
                ),
              ),
            ),
            ListTile(
              title: Text(l.language),
              trailing: DropdownButton<String>(
                key: const Key('language'),
                value: s.settings.locale?.languageCode ?? '',
                items: const [
                  DropdownMenuItem(value: '', child: Text('Auto')),
                  DropdownMenuItem(value: 'en', child: Text('English')),
                  DropdownMenuItem(value: 'sw', child: Text('Kiswahili')),
                ],
                onChanged: (v) => s.settings.setLocale(v == null || v.isEmpty ? null : Locale(v)),
              ),
            ),
          ]),
        ),
        const Divider(),
        ListTile(
          key: const Key('signOut'),
          leading: const Icon(Icons.logout),
          title: Text(l.signOut),
          onTap: () => s.auth.signOut(),
        ),
        Padding(
          padding: const EdgeInsets.all(16),
          child: Text('${s.config.environment} · ${s.config.authMode.name}', style: Theme.of(context).textTheme.bodySmall),
        ),
      ]),
    );
  }
}
