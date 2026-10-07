import 'dart:convert';
import 'dart:math';

final _random = Random.secure();

/// Random (version 4) UUID, used for Idempotency-Key headers and device ids.
String uuidV4() {
  final b = List<int>.generate(16, (_) => _random.nextInt(256));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  final h = b.map((x) => x.toRadixString(16).padLeft(2, '0')).join();
  return '${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}';
}

/// Claims of a JWT (no signature check: the app only reads its own tokens; the API validates them).
Map<String, dynamic> jwtClaims(String? token) {
  if (token == null) return const {};
  final parts = token.split('.');
  if (parts.length < 2) return const {};
  try {
    final decoded = utf8.decode(base64Url.decode(base64Url.normalize(parts[1])));
    final v = jsonDecode(decoded);
    return v is Map<String, dynamic> ? v : const {};
  } catch (_) {
    return const {};
  }
}
