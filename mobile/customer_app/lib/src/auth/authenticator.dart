import '../config.dart';
import '../core/ids.dart';
import 'session.dart';

/// Talks to the identity provider. Implementations: [AppAuthAuthenticator] (Keycloak through the system browser)
/// and [DevAuthenticator] (local dev security mode).
abstract class Authenticator {
  /// Interactive sign-in (authorization code + PKCE). [dev] carries the dev-mode identity.
  Future<Session> signIn({DevIdentity? dev});

  /// Step-up: a new authorization request with `acr_values` and `max_age` (RFC 9470). Keycloak asks for the second
  /// factor (and the password too when the last sign-in is older than max_age).
  Future<Session> stepUp(Session current, StepUpChallenge challenge);

  /// Exchanges a refresh token for fresh tokens (biometric unlock, token expiry).
  Future<Session> refresh(Session current);

  /// Ends the identity provider session (RP-initiated logout).
  Future<void> signOut(Session current);
}

/// Dev-mode identity typed on the sign-in screen.
class DevIdentity {
  const DevIdentity({required this.tenantId, required this.customerId, required this.user});
  final String tenantId;
  final String customerId;
  final String user;
}

/// The identity provider did not reach the required level (no second factor enrolled).
class StepUpUnavailable implements Exception {
  @override
  String toString() => 'A second factor is needed: add a passkey or an authenticator app, then try again.';
}

/// The user cancelled the browser sign-in.
class SignInCancelled implements Exception {}

/// Dev security mode: no identity provider. Step-up records an explicit confirmation (the UI asks for it).
class DevAuthenticator implements Authenticator {
  DevAuthenticator({DateTime Function()? now}) : _now = now ?? DateTime.now;
  final DateTime Function() _now;

  @override
  Future<Session> signIn({DevIdentity? dev}) async {
    if (dev == null || dev.customerId.isEmpty) throw ArgumentError('customer id required');
    return Session(
      mode: AuthMode.dev,
      user: dev.user.isEmpty ? 'demo.customer' : dev.user,
      customerId: dev.customerId,
      tenantId: dev.tenantId,
      sid: uuidV4(),
    );
  }

  @override
  Future<Session> stepUp(Session current, StepUpChallenge challenge) async => current.copyWith(stepUpAt: _now());

  @override
  Future<Session> refresh(Session current) async => current;

  @override
  Future<void> signOut(Session current) async {}
}
