import 'dart:convert';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:pointycastle/export.dart';

/// Encryption at rest for the offline outbox (same scheme as web/agent-app src/lib/outbox/crypto.ts).
///
/// The AES-GCM-256 key is derived with PBKDF2-HMAC-SHA-256 from the agent's 6-digit app PIN and a per-device random
/// salt bound to tenant + agent (the same PIN on another agent profile yields another key). The key lives only in
/// memory while the app is unlocked; nothing that could rebuild it is stored. Every record is encrypted with a fresh
/// 96-bit IV and its record id as additional authenticated data, so a ciphertext cannot be moved onto another record.
const pbkdf2Iterations = 210000;

final _rng = Random.secure();

Uint8List randomBytes(int n) => Uint8List.fromList(List<int>.generate(n, (_) => _rng.nextInt(256)));

String b64(List<int> bytes) => base64Encode(bytes);
Uint8List unb64(String s) => base64Decode(s);

String b64url(List<int> bytes) => base64Url.encode(bytes).replaceAll('=', '');

/// AES-256 key material (32 bytes) held in memory while the outbox is unlocked.
class OutboxKey {
  OutboxKey(this.bytes) : assert(bytes.length == 32);
  final Uint8List bytes;
}

class Sealed {
  const Sealed({required this.iv, required this.ct});
  factory Sealed.fromJson(Map<String, dynamic> j) => Sealed(iv: j['iv'] as String, ct: j['ct'] as String);

  /// base64 IV (12 bytes).
  final String iv;

  /// base64 ciphertext with the 16-byte GCM tag appended.
  final String ct;

  Map<String, dynamic> toJson() => {'iv': iv, 'ct': ct};
}

Uint8List _pbkdf2(({String pin, Uint8List salt, String binding, int iterations}) a) {
  final saltBound = Uint8List.fromList([...a.salt, ...utf8.encode(a.binding)]);
  final d = PBKDF2KeyDerivator(HMac(SHA256Digest(), 64))..init(Pbkdf2Parameters(saltBound, a.iterations, 32));
  return d.process(Uint8List.fromList(utf8.encode(a.pin)));
}

/// Derives the outbox key from the PIN, the stored salt and the agent binding (on a background isolate on devices).
Future<OutboxKey> deriveKey(String pin, Uint8List salt, String binding, {int iterations = pbkdf2Iterations}) async {
  if (pin.isEmpty) throw ArgumentError('PIN required');
  final args = (pin: pin, salt: salt, binding: binding, iterations: iterations);
  final bytes = iterations > 20000 ? await compute(_pbkdf2, args) : _pbkdf2(args);
  return OutboxKey(bytes);
}

GCMBlockCipher _gcm(bool encrypt, OutboxKey key, Uint8List iv, String aad) =>
    GCMBlockCipher(AESEngine())
      ..init(encrypt, AEADParameters(KeyParameter(key.bytes), 128, iv, Uint8List.fromList(utf8.encode(aad))));

Sealed seal(OutboxKey key, Object? value, String aad) {
  final iv = randomBytes(12);
  final ct = _gcm(true, key, iv, aad).process(Uint8List.fromList(utf8.encode(jsonEncode(value))));
  return Sealed(iv: b64(iv), ct: b64(ct));
}

/// Decrypts; throws if the key is wrong, the data was altered, or the record id (AAD) does not match.
T open<T>(OutboxKey key, Sealed sealed, String aad) {
  final pt = _gcm(false, key, unb64(sealed.iv), aad).process(unb64(sealed.ct));
  return jsonDecode(utf8.decode(pt)) as T;
}

String sha256Hex(List<int> data) =>
    SHA256Digest().process(Uint8List.fromList(data)).map((b) => b.toRadixString(16).padLeft(2, '0')).join();
