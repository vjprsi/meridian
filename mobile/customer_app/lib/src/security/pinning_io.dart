import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;
import 'package:http/io_client.dart';

/// Android / iOS: an HttpClient whose security context trusts only the pinned CAs (no platform roots).
http.Client pinnedClient(List<String> caCertificatesPem) {
  final ctx = SecurityContext(withTrustedRoots: false);
  for (final pem in caCertificatesPem) {
    ctx.setTrustedCertificatesBytes(utf8.encode(pem));
  }
  final client = HttpClient(context: ctx)..badCertificateCallback = (cert, host, port) => false;
  return IOClient(client);
}
