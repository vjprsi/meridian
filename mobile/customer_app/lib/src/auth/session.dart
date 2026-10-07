import 'package:flutter/foundation.dart';

import '../config.dart';
import '../core/ids.dart';

/// What the server asked for in its RFC 9470 challenge (`WWW-Authenticate: Bearer
/// error="insufficient_user_authentication", acr_values="2", max_age=300`).
@immutable
class StepUpChallenge {
  const StepUpChallenge({this.acr, this.maxAge});
  final String? acr;
  final Duration? maxAge;

  static StepUpChallenge? parse(String? header) {
    if (header == null || !header.contains('insufficient_user_authentication')) return null;
    final acr = RegExp(r'acr_values="([^"]*)"').firstMatch(header)?.group(1);
    final maxAge = RegExp(r'max_age=(\d+)').firstMatch(header)?.group(1);
    return StepUpChallenge(
      acr: acr == null || acr.isEmpty ? null : acr,
      maxAge: maxAge == null ? null : Duration(seconds: int.parse(maxAge)),
    );
  }
}

/// The signed-in customer. OIDC: built from the token response (claims customer_id, tenant_id, auth_time, acr, amr,
/// sid). Dev: entered at sign-in; requests carry dev headers.
@immutable
class Session {
  const Session({
    required this.mode,
    required this.user,
    required this.customerId,
    required this.tenantId,
    this.accessToken,
    this.refreshToken,
    this.idToken,
    this.expiresAt,
    this.authTime,
    this.acr,
    this.amr = const [],
    this.sid,
    this.stepUpAt,
  });

  final AuthMode mode;
  final String user;
  final String customerId;
  final String tenantId;
  final String? accessToken;
  final String? refreshToken;
  final String? idToken;
  final DateTime? expiresAt;

  /// Last interactive authentication (token auth_time).
  final DateTime? authTime;

  /// Level of Authentication: "1" password, "2" password plus a second factor.
  final String? acr;
  final List<String> amr;

  /// Identity provider session id (dev: random per sign-in, sent as X-Session-Id).
  final String? sid;

  /// Dev mode: time of the last explicit step-up confirmation.
  final DateTime? stepUpAt;

  /// Builds a session from OIDC tokens. Claims come from the access token, falling back to the ID token.
  factory Session.fromTokens({
    required String accessToken,
    String? refreshToken,
    String? idToken,
    DateTime? expiresAt,
  }) {
    final a = jwtClaims(accessToken);
    final i = jwtClaims(idToken);
    String? claim(String n) {
      final v = a[n] ?? i[n];
      return v?.toString();
    }

    final authTime = int.tryParse(claim('auth_time') ?? '');
    final amr = (a['amr'] ?? i['amr']);
    return Session(
      mode: AuthMode.oidc,
      user: claim('preferred_username') ?? claim('sub') ?? '',
      customerId: claim('customer_id') ?? '',
      tenantId: claim('tenant_id') ?? '',
      accessToken: accessToken,
      refreshToken: refreshToken,
      idToken: idToken,
      expiresAt: expiresAt,
      authTime: authTime == null ? null : DateTime.fromMillisecondsSinceEpoch(authTime * 1000, isUtc: true),
      acr: claim('acr'),
      amr: amr is List ? amr.map((e) => e.toString()).toList() : const [],
      sid: claim('sid'),
    );
  }

  Session copyWith({DateTime? stepUpAt}) => Session(
        mode: mode,
        user: user,
        customerId: customerId,
        tenantId: tenantId,
        accessToken: accessToken,
        refreshToken: refreshToken,
        idToken: idToken,
        expiresAt: expiresAt,
        authTime: authTime,
        acr: acr,
        amr: amr,
        sid: sid,
        stepUpAt: stepUpAt ?? this.stepUpAt,
      );

  /// True when step-up is satisfied without asking again: acr >= [requiredAcr] from an authentication no older than
  /// [maxAge] (dev: an explicit confirmation no older than [maxAge]).
  bool stepUpFresh(DateTime now, {String requiredAcr = '2', Duration maxAge = const Duration(seconds: 240)}) {
    final at = mode == AuthMode.oidc ? authTime : stepUpAt;
    final strong = mode == AuthMode.oidc ? (int.tryParse(acr ?? '') ?? 0) >= (int.tryParse(requiredAcr) ?? 2) : true;
    return strong && at != null && now.difference(at) <= maxAge;
  }

  bool expired(DateTime now) => expiresAt != null && !now.isBefore(expiresAt!.subtract(const Duration(seconds: 30)));

  /// Headers authenticating a request as this customer (bearer token, or dev-mode headers).
  Map<String, String> authHeaders({String? deviceId, String stepUpAcr = '2'}) {
    final h = <String, String>{};
    if (deviceId != null) h['X-Device-Id'] = deviceId;
    if (mode == AuthMode.oidc) {
      if (accessToken != null) h['Authorization'] = 'Bearer $accessToken';
      return h;
    }
    h['X-Tenant-Id'] = tenantId;
    h['X-User'] = user;
    h['X-Customer-Id'] = customerId;
    if (sid != null) h['X-Session-Id'] = sid!;
    if (stepUpAt != null) {
      h['X-Auth-Time'] = '${stepUpAt!.millisecondsSinceEpoch ~/ 1000}';
      h['X-Acr'] = stepUpAcr;
      h['X-Amr'] = 'pwd,otp';
    }
    return h;
  }
}
