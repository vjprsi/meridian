import 'package:flutter/services.dart';
import 'package:flutter_appauth/flutter_appauth.dart';

import '../config.dart';
import '../core/ids.dart';

/// The signed-in agent user. The agent comes from the token's agent_id claim (admin-managed Keycloak attribute),
/// never from a choice in the app; dev mode takes it from the sign-in form.
class AgentSession {
  const AgentSession({
    required this.mode,
    required this.user,
    required this.userId,
    required this.tenantId,
    this.agentId,
    this.accessToken,
    this.refreshToken,
    this.idToken,
    this.expiresAt,
    this.authTime,
  });

  factory AgentSession.fromTokens(String access, {String? refresh, String? id, DateTime? expiresAt}) {
    final a = jwtClaims(access);
    final i = jwtClaims(id);
    String? c(String n) => (a[n] ?? i[n])?.toString();
    final at = int.tryParse(c('auth_time') ?? '');
    return AgentSession(
      mode: AuthMode.oidc,
      user: c('preferred_username') ?? c('sub') ?? '',
      userId: c('sub') ?? '',
      tenantId: c('tenant_id') ?? '',
      agentId: c('agent_id'),
      accessToken: access,
      refreshToken: refresh,
      idToken: id,
      expiresAt: expiresAt,
      authTime: at == null ? null : DateTime.fromMillisecondsSinceEpoch(at * 1000, isUtc: true),
    );
  }

  final AuthMode mode;
  final String user;
  final String userId;
  final String tenantId;
  final String? agentId;
  final String? accessToken;
  final String? refreshToken;
  final String? idToken;
  final DateTime? expiresAt;

  /// Last interactive sign-in: money movement needs one no older than 5 minutes, or a device proof.
  final DateTime? authTime;

  bool expired(DateTime now) => expiresAt != null && !now.isBefore(expiresAt!.subtract(const Duration(seconds: 30)));

  Map<String, String> headers() {
    if (mode == AuthMode.oidc) return {if (accessToken != null) 'Authorization': 'Bearer $accessToken'};
    return {
      'X-Tenant-Id': tenantId,
      'X-User': user,
      'X-Agent-Id': ?agentId,
      if (authTime != null) 'X-Auth-Time': '${authTime!.millisecondsSinceEpoch ~/ 1000}',
    };
  }
}

class DevAgentIdentity {
  const DevAgentIdentity({required this.tenantId, required this.agentId, required this.user, required this.userId});
  final String tenantId;
  final String agentId;
  final String user;
  final String userId;
}

class SignInCancelled implements Exception {}

abstract class AgentAuthenticator {
  Future<AgentSession> signIn({DevAgentIdentity? dev});
  Future<AgentSession> refresh(AgentSession s);
  Future<void> signOut(AgentSession s);
}

class DevAgentAuthenticator implements AgentAuthenticator {
  DevAgentAuthenticator({DateTime Function()? now}) : _now = now ?? DateTime.now;
  final DateTime Function() _now;

  @override
  Future<AgentSession> signIn({DevAgentIdentity? dev}) async {
    if (dev == null || dev.agentId.isEmpty) throw ArgumentError('agent id required');
    return AgentSession(
        mode: AuthMode.dev,
        user: dev.user,
        userId: dev.userId,
        tenantId: dev.tenantId,
        agentId: dev.agentId,
        authTime: _now());
  }

  @override
  Future<AgentSession> refresh(AgentSession s) async => s;

  @override
  Future<void> signOut(AgentSession s) async {}
}

/// Keycloak through the system browser (authorization code + PKCE, custom-scheme redirect), client
/// `meridian-agent-mobile`.
class AppAuthAgentAuthenticator implements AgentAuthenticator {
  AppAuthAgentAuthenticator(this.config, [FlutterAppAuth? appAuth]) : _appAuth = appAuth ?? const FlutterAppAuth();
  final AppConfig config;
  final FlutterAppAuth _appAuth;

  @override
  Future<AgentSession> signIn({DevAgentIdentity? dev}) async {
    try {
      final r = await _appAuth.authorizeAndExchangeCode(AuthorizationTokenRequest(
        config.clientId,
        config.redirectUrl,
        issuer: config.issuer,
        scopes: const ['openid', 'profile'],
        allowInsecureConnections: config.allowInsecureConnections,
      ));
      return AgentSession.fromTokens(r.accessToken!,
          refresh: r.refreshToken, id: r.idToken, expiresAt: r.accessTokenExpirationDateTime);
    } on FlutterAppAuthUserCancelledException {
      throw SignInCancelled();
    } on PlatformException catch (e) {
      if (e.code.contains('cancel')) throw SignInCancelled();
      rethrow;
    }
  }

  @override
  Future<AgentSession> refresh(AgentSession s) async {
    final r = await _appAuth.token(TokenRequest(config.clientId, config.redirectUrl,
        issuer: config.issuer,
        refreshToken: s.refreshToken,
        scopes: const ['openid', 'profile'],
        allowInsecureConnections: config.allowInsecureConnections));
    return AgentSession.fromTokens(r.accessToken!,
        refresh: r.refreshToken ?? s.refreshToken, id: r.idToken ?? s.idToken, expiresAt: r.accessTokenExpirationDateTime);
  }

  @override
  Future<void> signOut(AgentSession s) async {
    try {
      await _appAuth.endSession(EndSessionRequest(
          idTokenHint: s.idToken,
          postLogoutRedirectUrl: config.redirectUrl,
          issuer: config.issuer,
          allowInsecureConnections: config.allowInsecureConnections));
    } catch (_) {}
  }
}
