import 'package:local_auth/local_auth.dart';

/// Gate in front of the stored refresh token: the app uses the token only after the user passed it.
abstract class BiometricGate {
  /// Whether this device can authenticate the user locally (biometrics or device credential).
  Future<bool> available();

  /// Prompts the user; true when they authenticated.
  Future<bool> authenticate(String reason);
}

/// local_auth (Face ID / Touch ID, Android BiometricPrompt with device-credential fallback).
class LocalAuthBiometricGate implements BiometricGate {
  LocalAuthBiometricGate([LocalAuthentication? auth]) : _auth = auth ?? LocalAuthentication();
  final LocalAuthentication _auth;

  @override
  Future<bool> available() async {
    try {
      return await _auth.isDeviceSupported();
    } catch (_) {
      return false;
    }
  }

  @override
  Future<bool> authenticate(String reason) async {
    try {
      return await _auth.authenticate(localizedReason: reason, persistAcrossBackgrounding: true);
    } catch (_) {
      return false;
    }
  }
}

/// No local authentication (web, tests): biometric unlock is not offered.
class NoBiometricGate implements BiometricGate {
  const NoBiometricGate();

  @override
  Future<bool> available() async => false;

  @override
  Future<bool> authenticate(String reason) async => false;
}
