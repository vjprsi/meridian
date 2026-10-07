import 'package:intl/intl.dart';

/// Decimal-string money helpers. Amounts from the API are decimal strings; they are never added as doubles but
/// scaled to integer minor units (4 decimals) as BigInt, like the web apps (web/*/src/lib/money.ts).
class Money {
  Money._();

  static const scale = 4;
  static final _factor = BigInt.from(10).pow(scale);
  static final _amount = RegExp(r'^\d{1,15}(\.\d{1,4})?$');

  static bool isAmount(String s) => _amount.hasMatch(s.trim());

  static BigInt toMinor(String? s) {
    if (s == null || s.trim().isEmpty) return BigInt.zero;
    var str = s.trim();
    final neg = str.startsWith('-');
    if (neg) str = str.substring(1);
    final parts = str.split('.');
    final i = BigInt.parse(parts[0].isEmpty ? '0' : parts[0]);
    final f = parts.length > 1 ? '${parts[1]}0000'.substring(0, scale) : '0000';
    final v = i * _factor + BigInt.parse(f);
    return neg ? -v : v;
  }

  static String fromMinor(BigInt v) {
    final neg = v.isNegative;
    final a = v.abs();
    final i = a ~/ _factor;
    final f = (a % _factor).toString().padLeft(scale, '0').replaceFirst(RegExp(r'0+$'), '');
    return '${neg ? '-' : ''}$i${f.isEmpty ? '' : '.$f'}';
  }

  static int compare(String a, String b) => toMinor(a).compareTo(toMinor(b));

  static String sum(Iterable<String?> values) => fromMinor(values.fold(BigInt.zero, (acc, v) => acc + toMinor(v)));

  /// Display only (never fed back into arithmetic).
  static String format(String? amount, String? currency, {String? locale}) {
    if (amount == null || amount.isEmpty) return '-';
    final n = double.tryParse(amount);
    if (n == null) return '${currency ?? ''} $amount'.trim();
    try {
      return NumberFormat.currency(locale: locale, name: currency ?? '', decimalDigits: 2).format(n).trim();
    } catch (_) {
      return '${currency ?? ''} $amount'.trim();
    }
  }
}
