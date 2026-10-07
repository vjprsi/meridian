import 'package:flutter/foundation.dart';

import 'outbox/outbox.dart';

enum AuthMode { oidc, dev }

/// Build-time configuration (`--dart-define` / `--dart-define-from-file=env/<env>.json`).
@immutable
class AppConfig {
  const AppConfig({
    required this.apiBaseUrl,
    required this.issuer,
    required this.clientId,
    required this.redirectUrl,
    required this.authMode,
    this.defaultTenant = '0199a000-0000-7000-8000-00000000000a',
    this.environment = 'local',
    this.policy = const OfflinePolicy(),
  });

  final String apiBaseUrl;
  final String issuer;

  /// Public OIDC client `meridian-agent-mobile` (tools/keycloak_realm.py): scope channels:agent, agent_id claim.
  final String clientId;
  final String redirectUrl;
  final AuthMode authMode;
  final String defaultTenant;
  final String environment;
  final OfflinePolicy policy;

  static const _api = String.fromEnvironment('MERIDIAN_API_BASE_URL', defaultValue: 'http://localhost:18000');
  static const _issuer =
      String.fromEnvironment('MERIDIAN_OIDC_ISSUER', defaultValue: 'http://localhost:15180/realms/meridian');
  static const _client = String.fromEnvironment('MERIDIAN_OIDC_CLIENT_ID', defaultValue: 'meridian-agent-mobile');
  static const _redirect =
      String.fromEnvironment('MERIDIAN_OIDC_REDIRECT', defaultValue: 'io.meridian.agent:/oauth2redirect');
  static const _mode = String.fromEnvironment('MERIDIAN_AUTH_MODE');
  static const _env = String.fromEnvironment('MERIDIAN_ENV', defaultValue: 'local');

  factory AppConfig.fromEnvironment() => AppConfig(
        apiBaseUrl: _api,
        issuer: _issuer,
        clientId: _client,
        redirectUrl: _redirect,
        authMode: _mode == 'dev' || (_mode.isEmpty && kIsWeb) ? AuthMode.dev : AuthMode.oidc,
        environment: _env,
      );

  bool get allowInsecureConnections => issuer.startsWith('http://');
}
