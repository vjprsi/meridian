import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:pointycastle/export.dart';

import '../security/secure_store.dart';
import 'outbox_crypto.dart';

/// Device binding (same contract as web/agent-app src/lib/device-key.ts): an ECDSA P-256 key pair (ES256). Only the
/// public key (SubjectPublicKeyInfo, base64) is registered with the agent channel (POST /v1/agent/devices). Offline
/// onboarding batches and money operations replayed later than the sign-in's step-up window are signed with it; the
/// texts must match agent-channel-service DeviceKeys byte for byte.
class DeviceKeyInfo {
  const DeviceKeyInfo({required this.publicKey, this.algorithm = 'ES256', required this.hardwareBacked});

  /// SubjectPublicKeyInfo DER, base64.
  final String publicKey;
  final String algorithm;

  /// True when the private key lives in the platform keystore (Android Keystore / StrongBox, iOS Secure Enclave).
  final bool hardwareBacked;
}

abstract class DeviceKeyStore {
  Future<DeviceKeyInfo?> current();

  /// Generates a fresh key pair (replacing any previous one) and returns its public half.
  Future<DeviceKeyInfo> generate();

  /// ES256 signature over UTF-8 [text]: IEEE P1363 r||s (64 bytes), base64url without padding.
  Future<String> sign(String text);
  Future<void> delete();
}

/// Platform keystore through a method channel implemented in the app's native code (android MainActivity.kt:
/// Android Keystore EC P-256, StrongBox when present, non-exportable; ios AppDelegate.swift: Secure Enclave P-256).
/// The private key never enters Dart.
class PlatformDeviceKeyStore implements DeviceKeyStore {
  PlatformDeviceKeyStore([MethodChannel? channel]) : _ch = channel ?? const MethodChannel('io.meridian.agent/device_key');
  final MethodChannel _ch;
  static const alias = 'meridian-agent-device-key';

  /// True when the native side answers (the plugin is compiled into this build).
  Future<bool> available() async {
    if (kIsWeb) return false;
    try {
      return await _ch.invokeMethod<bool>('isAvailable') ?? false;
    } catch (_) {
      return false;
    }
  }

  @override
  Future<DeviceKeyInfo?> current() async {
    final spki = await _ch.invokeMethod<String>('publicKey', {'alias': alias});
    return spki == null ? null : DeviceKeyInfo(publicKey: spki, hardwareBacked: true);
  }

  @override
  Future<DeviceKeyInfo> generate() async {
    final spki = await _ch.invokeMethod<String>('generate', {'alias': alias});
    return DeviceKeyInfo(publicKey: spki!, hardwareBacked: true);
  }

  @override
  Future<String> sign(String text) async {
    // Native code returns the DER signature (both keystores produce ASN.1); converted to P1363 here.
    final der = await _ch.invokeMethod<Uint8List>('sign', {'alias': alias, 'data': Uint8List.fromList(utf8.encode(text))});
    return b64url(derToP1363(der!));
  }

  @override
  Future<void> delete() => _ch.invokeMethod('delete', {'alias': alias});
}

/// Pure-Dart fallback (pointycastle): used where no platform keystore is reachable (web smoke build, unit tests,
/// devices without the native plugin). The private scalar is kept in [SecureStore] (Keystore / Keychain encrypted on
/// devices). Signatures are deterministic (RFC 6979).
class SoftwareDeviceKeyStore implements DeviceKeyStore {
  SoftwareDeviceKeyStore(this._store);
  final SecureStore _store;
  static const _privKey = 'meridian.agent.device_key.d';
  static final _curve = ECCurve_secp256r1();

  @override
  Future<DeviceKeyInfo?> current() async {
    final d = await _store.read(_privKey);
    if (d == null) return null;
    return DeviceKeyInfo(publicKey: b64(spkiFromPrivate(BigInt.parse(d, radix: 16))), hardwareBacked: false);
  }

  @override
  Future<DeviceKeyInfo> generate() async {
    final rnd = FortunaRandom()..seed(KeyParameter(randomBytes(32)));
    final gen = ECKeyGenerator()..init(ParametersWithRandom(ECKeyGeneratorParameters(_curve), rnd));
    final pair = gen.generateKeyPair();
    final priv = pair.privateKey;
    await _store.write(_privKey, priv.d!.toRadixString(16));
    return DeviceKeyInfo(publicKey: b64(spkiFromPrivate(priv.d!)), hardwareBacked: false);
  }

  @override
  Future<String> sign(String text) async {
    final d = await _store.read(_privKey);
    if (d == null) throw StateError('This device has no signing key; register the device again');
    final signer = ECDSASigner(SHA256Digest(), HMac(SHA256Digest(), 64))
      ..init(true, PrivateKeyParameter<ECPrivateKey>(ECPrivateKey(BigInt.parse(d, radix: 16), _curve)));
    final sig = signer.generateSignature(Uint8List.fromList(utf8.encode(text))) as ECSignature;
    return b64url([..._fixed32(sig.r), ..._fixed32(sig.s)]);
  }

  @override
  Future<void> delete() => _store.delete(_privKey);

  static Uint8List spkiFromPrivate(BigInt d) {
    final q = (_curve.G * d)!;
    return spki(q.getEncoded(false));
  }
}

/// SubjectPublicKeyInfo for an uncompressed P-256 point (0x04 || X || Y).
Uint8List spki(Uint8List uncompressedPoint) {
  const prefix = [
    0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01, // id-ecPublicKey
    0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, // prime256v1
    0x03, 0x42, 0x00,
  ];
  return Uint8List.fromList([...prefix, ...uncompressedPoint]);
}

List<int> _fixed32(BigInt v) {
  final hex = v.toRadixString(16).padLeft(64, '0');
  return [for (var i = 0; i < 64; i += 2) int.parse(hex.substring(i, i + 2), radix: 16)];
}

/// ASN.1 DER ECDSA signature (SEQUENCE { INTEGER r, INTEGER s }) to IEEE P1363 r||s.
Uint8List derToP1363(Uint8List der) {
  var i = 2;
  if (der[1] & 0x80 != 0) i = 2 + (der[1] & 0x7f);
  BigInt readInt() {
    if (der[i] != 0x02) throw const FormatException('bad DER signature');
    final len = der[i + 1];
    final bytes = der.sublist(i + 2, i + 2 + len);
    i += 2 + len;
    return BigInt.parse(bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join(), radix: 16);
  }

  final r = readInt();
  final s = readInt();
  return Uint8List.fromList([..._fixed32(r), ..._fixed32(s)]);
}

/// Verifies a P1363 base64url signature against an SPKI public key (tests; mirrors the server check).
bool verifyEs256(String spkiB64, String text, String sigB64url) {
  final der = unb64(spkiB64);
  final point = ECCurve_secp256r1().curve.decodePoint(der.sublist(der.length - 65))!;
  final raw = base64Url.decode(base64Url.normalize(sigB64url));
  BigInt big(List<int> b) => BigInt.parse(b.map((x) => x.toRadixString(16).padLeft(2, '0')).join(), radix: 16);
  final verifier = ECDSASigner(SHA256Digest())
    ..init(false, PublicKeyParameter<ECPublicKey>(ECPublicKey(point, ECCurve_secp256r1())));
  return verifier.verifySignature(
      Uint8List.fromList(utf8.encode(text)), ECSignature(big(raw.sublist(0, 32)), big(raw.sublist(32))));
}

/// Picks the platform keystore when the native plugin answers, else the pure-Dart store.
Future<DeviceKeyStore> deviceKeyStore(SecureStore store) async {
  final platform = PlatformDeviceKeyStore();
  return await platform.available() ? platform : SoftwareDeviceKeyStore(store);
}

/// Canonical text of a device proof (agent-channel-service DeviceKeys.proofPayload).
String proofPayload({required String tenant, required String agent, required String device, required String method,
    required String path, required String idempotencyKey, required int ts}) =>
    [
      'MERIDIAN-AGENT-DEVICE-PROOF-V1',
      'tenant=$tenant',
      'agent=$agent',
      'device=$device',
      'method=$method',
      'path=$path',
      'idempotency_key=$idempotencyKey',
      'timestamp=$ts',
    ].join('\n');

/// Canonical text of an offline batch (agent-channel-service DeviceKeys.batchPayload).
String batchPayload({required String tenant, required String agent, required String device,
    required String idempotencyKey, required DateTime capturedFrom, required DateTime capturedTo,
    required int applicationCount, required String payloadUri}) =>
    [
      'MERIDIAN-AGENT-OFFLINE-BATCH-V1',
      'tenant=$tenant',
      'agent=$agent',
      'device=$device',
      'idempotency_key=$idempotencyKey',
      'captured_from=${capturedFrom.millisecondsSinceEpoch}',
      'captured_to=${capturedTo.millisecondsSinceEpoch}',
      'application_count=$applicationCount',
      'payload_sha256=${sha256Hex(utf8.encode(payloadUri))}',
    ].join('\n');
