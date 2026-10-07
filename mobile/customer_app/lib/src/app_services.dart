import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;

import 'api/api_client.dart';
import 'api/customer_api.dart';
import 'auth/appauth_authenticator.dart';
import 'auth/auth_controller.dart';
import 'auth/authenticator.dart';
import 'config.dart';
import 'security/biometric.dart';
import 'security/integrity.dart';
import 'security/pinning.dart';
import 'security/secure_store.dart';

/// Theme and language, kept on the device.
class AppSettings extends ChangeNotifier {
  AppSettings(this._store);
  final SecureStore _store;

  ThemeMode themeMode = ThemeMode.system;
  Locale? locale;

  Future<void> load() async {
    themeMode = ThemeMode.values.asNameMap()[await _store.read('meridian.customer.theme')] ?? ThemeMode.system;
    final l = await _store.read('meridian.customer.locale');
    locale = l == null || l.isEmpty ? null : Locale(l);
    notifyListeners();
  }

  Future<void> setTheme(ThemeMode m) async {
    themeMode = m;
    notifyListeners();
    await _store.write('meridian.customer.theme', m.name);
  }

  Future<void> setLocale(Locale? l) async {
    locale = l;
    notifyListeners();
    await _store.write('meridian.customer.locale', l?.languageCode ?? '');
  }
}

/// Everything a screen needs, created once at start-up (tests build it with fakes).
class AppServices {
  AppServices({required this.config, required this.auth, required this.api, required this.settings});

  /// Production wiring from the build configuration.
  factory AppServices.create(AppConfig config) {
    final store = kIsWeb ? MemorySecureStore() : PlatformSecureStore();
    final Authenticator authenticator =
        config.authMode == AuthMode.oidc ? AppAuthAuthenticator(config) : DevAuthenticator();
    final auth = AuthController(
      config: config,
      authenticator: authenticator,
      store: store,
      biometric: kIsWeb ? const NoBiometricGate() : LocalAuthBiometricGate(),
      integrity: const NoopIntegrityCheck(),
    );
    return AppServices.wire(
        config: config, auth: auth, settings: AppSettings(store), http: pinningFor(config.pinnedCaCertificatesPem).client());
  }

  factory AppServices.wire(
      {required AppConfig config, required AuthController auth, required AppSettings settings, required http.Client http}) {
    final client = ApiClient(baseUrl: config.apiBaseUrl, httpClient: http, headers: auth.headers);
    final services = AppServices(config: config, auth: auth, api: CustomerApi(client), settings: settings);
    // A session ended elsewhere ("sign out everywhere", revoked by the bank) or an access token that can no longer be
    // refreshed: sign out locally.
    client.onError = (e) {
      if (e.isSessionRevoked || (e.status == 401 && !e.isStepUpRequired)) {
        services.sessionEnded.value = true;
        auth.signOut(local: true);
      }
    };
    return services;
  }

  final AppConfig config;
  final AuthController auth;
  final CustomerApi api;
  final AppSettings settings;

  /// Set when the server ended the session; the sign-in screen shows a notice.
  final ValueNotifier<bool> sessionEnded = ValueNotifier(false);

  Future<void> init() async {
    await settings.load();
    await auth.init();
  }

  static AppServices of(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<ServicesScope>()!.services;
}

class ServicesScope extends InheritedWidget {
  const ServicesScope({super.key, required this.services, required super.child});
  final AppServices services;

  @override
  bool updateShouldNotify(ServicesScope oldWidget) => services != oldWidget.services;
}
