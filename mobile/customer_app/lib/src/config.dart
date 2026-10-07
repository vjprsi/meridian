import 'package:flutter/foundation.dart';

/// How the app authenticates.
///
/// * [oidc]: Keycloak realm `meridian`, authorization code + PKCE through the system browser (flutter_appauth).
/// * [dev]: local services in dev security mode; requests carry `X-Tenant-Id` / `X-User` / `X-Customer-Id` headers
///   (honoured only by services started with `meridian.security.mode=dev`). Used by the web smoke build and tests.
enum AuthMode { oidc, dev }

/// Build-time configuration (`--dart-define=KEY=value`, one set per flavor / environment; see README).
@immutable
class AppConfig {
  const AppConfig({
    required this.apiBaseUrl,
    required this.issuer,
    required this.clientId,
    required this.redirectUrl,
    required this.authMode,
    this.defaultTenant = '0199a000-0000-7000-8000-00000000000a',
    this.stepUpAcr = '2',
    this.stepUpServerMaxAge = const Duration(seconds: 300),
    this.stepUpClientMaxAge = const Duration(seconds: 240),
    this.environment = 'local',
    this.pinnedCaCertificatesPem = const [],
    this.blockOnCompromisedDevice = true,
  });

  /// Edge gateway base URL (`/v1/me/*` is appended).
  final String apiBaseUrl;

  /// OIDC issuer (Keycloak realm URL); discovery document at `<issuer>/.well-known/openid-configuration`.
  final String issuer;

  /// Public OIDC client: `meridian-customer-mobile` (custom-scheme redirect, tools/keycloak_realm.py).
  final String clientId;

  /// Private-use URI scheme redirect (RFC 8252), registered in AndroidManifest / Info.plist.
  final String redirectUrl;
  final AuthMode authMode;
  final String defaultTenant;

  /// Level of Authentication the channel asks for money movement and security changes (acr_values, RFC 9470).
  final String stepUpAcr;

  /// The server's step-up window (meridian.channels.step-up-max-age), sent as OIDC `max_age` on step-up.
  final Duration stepUpServerMaxAge;

  /// The app asks again a little before the server window ends, so an open confirmation does not run into it.
  final Duration stepUpClientMaxAge;
  final String environment;

  /// PEM CA certificates the API connection is pinned to (empty: platform trust store). See security/pinning.dart.
  final List<String> pinnedCaCertificatesPem;

  /// Refuse money movement and security changes when the device integrity check reports a compromised device.
  final bool blockOnCompromisedDevice;

  static const _api = String.fromEnvironment('MERIDIAN_API_BASE_URL', defaultValue: 'http://localhost:18000');
  static const _issuer =
      String.fromEnvironment('MERIDIAN_OIDC_ISSUER', defaultValue: 'http://localhost:15180/realms/meridian');
  static const _client = String.fromEnvironment('MERIDIAN_OIDC_CLIENT_ID', defaultValue: 'meridian-customer-mobile');
  static const _redirect =
      String.fromEnvironment('MERIDIAN_OIDC_REDIRECT', defaultValue: 'io.meridian.customer:/oauth2redirect');
  static const _mode = String.fromEnvironment('MERIDIAN_AUTH_MODE');
  static const _env = String.fromEnvironment('MERIDIAN_ENV', defaultValue: 'local');

  /// Reads the `--dart-define` values. Web builds default to dev mode (AppAuth has no web implementation).
  factory AppConfig.fromEnvironment() => AppConfig(
        apiBaseUrl: _api,
        issuer: _issuer,
        clientId: _client,
        redirectUrl: _redirect,
        authMode: _mode == 'dev' || (_mode.isEmpty && kIsWeb) ? AuthMode.dev : AuthMode.oidc,
        environment: _env,
      );

  /// Plain-HTTP issuers are accepted only for local development (AppAuth refuses them otherwise).
  bool get allowInsecureConnections => issuer.startsWith('http://');
}
