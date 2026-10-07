import 'package:flutter/foundation.dart';

import '../api/api_client.dart';
import '../config.dart';
import '../core/ids.dart';
import '../security/biometric.dart';
import '../security/integrity.dart';
import '../security/secure_store.dart';
import 'authenticator.dart';
import 'session.dart';

/// Money movement or a security change was refused on this device (integrity check failed).
class DeviceCompromised implements Exception {
  DeviceCompromised(this.reasons);
  final List<String> reasons;

  @override
  String toString() => 'This device failed the security check (${reasons.join(', ')})';
}

/// Owns the session: sign-in, biometric unlock of the stored refresh token, token refresh, step-up and sign-out.
///
/// Storage: only the refresh token is persisted, in the platform keystore ([SecureStore]); it is read only after the
/// biometric gate passed. The access token lives in memory.
class AuthController extends ChangeNotifier {
  AuthController({
    required this.config,
    required this.authenticator,
    required this.store,
    this.biometric = const NoBiometricGate(),
    this.integrity = const NoopIntegrityCheck(),
    DateTime Function()? now,
  }) : _now = now ?? DateTime.now;

  final AppConfig config;
  final Authenticator authenticator;
  final SecureStore store;
  final BiometricGate biometric;
  final DeviceIntegrityCheck integrity;
  final DateTime Function() _now;

  static const refreshTokenKey = 'meridian.customer.refresh_token';
  static const biometricKey = 'meridian.customer.biometric';
  static const deviceKey = 'meridian.customer.device';

  Session? _session;
  bool _canUnlock = false;
  bool _biometricAvailable = false;
  String? _deviceId;
  IntegrityVerdict _verdict = IntegrityVerdict.trusted;

  Session? get session => _session;
  bool get signedIn => _session != null;

  /// A refresh token is stored behind biometrics: the start screen offers "Unlock".
  bool get canUnlock => _canUnlock;
  bool get biometricAvailable => _biometricAvailable;
  IntegrityVerdict get integrityVerdict => _verdict;

  Future<void> init() async {
    _deviceId = await store.read(deviceKey);
    if (_deviceId == null) {
      _deviceId = uuidV4();
      await store.write(deviceKey, _deviceId!);
    }
    _biometricAvailable = await biometric.available();
    _canUnlock = _biometricAvailable &&
        await store.read(biometricKey) == 'on' &&
        (await store.read(refreshTokenKey)) != null;
    _verdict = await integrity.check();
    notifyListeners();
  }

  /// A random id of this installation, sent as X-Device-Id (new-device sign-in alerts).
  String? get deviceId => _deviceId;

  Future<void> signIn({DevIdentity? dev}) async {
    _session = await authenticator.signIn(dev: dev);
    await _persist();
    notifyListeners();
  }

  /// Biometric unlock: the stored refresh token is used only after the user authenticated on the device.
  Future<bool> unlock(String reason) async {
    if (!await biometric.authenticate(reason)) return false;
    final token = await store.read(refreshTokenKey);
    if (token == null) return false;
    try {
      _session = await authenticator.refresh(Session(
          mode: AuthMode.oidc, user: '', customerId: '', tenantId: '', refreshToken: token));
      await _persist();
      notifyListeners();
      return true;
    } catch (_) {
      await forgetDevice();
      return false;
    }
  }

  /// Turns biometric unlock on or off (off removes the stored refresh token).
  Future<void> setBiometric(bool on) async {
    await store.write(biometricKey, on ? 'on' : 'off');
    if (on) {
      await _persist();
    } else {
      await store.delete(refreshTokenKey);
    }
    _canUnlock = on && _session?.refreshToken != null;
    notifyListeners();
  }

  Future<bool> biometricEnabled() async => await store.read(biometricKey) == 'on';

  Future<void> _persist() async {
    final s = _session;
    if (s?.refreshToken == null || !_biometricAvailable) return;
    if (await store.read(biometricKey) == 'off') return;
    await store.write(biometricKey, 'on');
    await store.write(refreshTokenKey, s!.refreshToken!);
  }

  Future<void> forgetDevice() async {
    await store.delete(refreshTokenKey);
    _canUnlock = false;
    notifyListeners();
  }

  /// Request headers; refreshes an expired access token first.
  Future<Map<String, String>> headers() async {
    var s = _session;
    if (s == null) return {'X-Device-Id': ?_deviceId};
    if (s.mode == AuthMode.oidc && s.expired(_now()) && s.refreshToken != null) {
      s = _session = await authenticator.refresh(s);
      await _persist();
    }
    return s.authHeaders(deviceId: _deviceId, stepUpAcr: config.stepUpAcr);
  }

  bool get stepUpFresh =>
      _session?.stepUpFresh(_now(), requiredAcr: config.stepUpAcr, maxAge: config.stepUpClientMaxAge) ?? false;

  Future<void> stepUp([StepUpChallenge challenge = const StepUpChallenge()]) async {
    final s = _session;
    if (s == null) throw StateError('Not signed in');
    _session = await authenticator.stepUp(s, challenge);
    await _persist();
    notifyListeners();
  }

  /// Runs a sensitive operation (money movement, security change) under step-up:
  /// 1. refuses on a device that failed the integrity check (when configured);
  /// 2. steps up first when the last strong authentication is too old;
  /// 3. if the server still answers 401 step_up_required, steps up with exactly what its challenge asked for and
  ///    retries once. [op] must reuse the same Idempotency-Key on the retry.
  Future<T> withStepUp<T>(Future<T> Function() op) async {
    if (config.blockOnCompromisedDevice && _verdict.compromised) throw DeviceCompromised(_verdict.reasons);
    if (!stepUpFresh) await stepUp();
    try {
      return await op();
    } on ApiException catch (e) {
      if (!e.isStepUpRequired) rethrow;
      await stepUp(e.challenge ?? const StepUpChallenge());
      return op();
    }
  }

  /// Local sign-out (and IdP sign-out unless [local]).
  Future<void> signOut({bool local = false}) async {
    final s = _session;
    _session = null;
    await store.delete(refreshTokenKey);
    _canUnlock = false;
    notifyListeners();
    if (s != null && !local) await authenticator.signOut(s);
  }
}
