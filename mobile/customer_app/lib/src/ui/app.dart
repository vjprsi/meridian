import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

import '../../l10n/app_localizations.dart';
import '../app_services.dart';
import 'screens/home_shell.dart';
import 'screens/sign_in.dart';
import 'theme.dart';

class MeridianCustomerApp extends StatelessWidget {
  const MeridianCustomerApp({super.key, required this.services});
  final AppServices services;

  @override
  Widget build(BuildContext context) {
    return ServicesScope(
      services: services,
      child: ListenableBuilder(
        listenable: Listenable.merge([services.settings, services.auth]),
        builder: (context, _) => MaterialApp(
          onGenerateTitle: (c) => AppLocalizations.of(c).appTitle,
          debugShowCheckedModeBanner: false,
          theme: MeridianTheme.light(),
          darkTheme: MeridianTheme.dark(),
          themeMode: services.settings.themeMode,
          locale: services.settings.locale,
          supportedLocales: AppLocalizations.supportedLocales,
          localizationsDelegates: const [
            AppLocalizations.delegate,
            GlobalMaterialLocalizations.delegate,
            GlobalWidgetsLocalizations.delegate,
            GlobalCupertinoLocalizations.delegate,
          ],
          // A new session gets a fresh navigation stack (nothing of the previous customer stays on screen).
          home: services.auth.signedIn
              ? HomeShell(key: ValueKey(services.auth.session!.customerId))
              : const SignInScreen(),
        ),
      ),
    );
  }
}
