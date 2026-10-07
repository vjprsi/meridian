import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

import '../../l10n/app_localizations.dart';
import '../agent_controller.dart';
import 'screens/home.dart';
import 'screens/setup.dart';
import 'screens/sign_in.dart';
import 'theme.dart';
import 'widgets.dart';

class MeridianAgentApp extends StatelessWidget {
  const MeridianAgentApp({super.key, required this.controller, this.locale});
  final AgentController controller;
  final Locale? locale;

  @override
  Widget build(BuildContext context) {
    return AgentScope(
      controller: controller,
      child: ListenableBuilder(
        listenable: controller,
        builder: (context, _) => MaterialApp(
          onGenerateTitle: (c) => AppLocalizations.of(c).appTitle,
          debugShowCheckedModeBanner: false,
          theme: MeridianTheme.light(),
          darkTheme: MeridianTheme.dark(),
          locale: locale,
          supportedLocales: AppLocalizations.supportedLocales,
          localizationsDelegates: const [
            AppLocalizations.delegate,
            GlobalMaterialLocalizations.delegate,
            GlobalWidgetsLocalizations.delegate,
            GlobalCupertinoLocalizations.delegate,
          ],
          home: !controller.signedIn
              ? const SignInScreen()
              : controller.needsSetup
                  ? const SetupScreen()
                  : !controller.unlocked
                      ? const UnlockScreen()
                      : const HomeScreen(),
        ),
      ),
    );
  }
}
