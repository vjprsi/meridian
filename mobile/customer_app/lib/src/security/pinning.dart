import 'package:http/http.dart' as http;

import 'pinning_stub.dart' if (dart.library.io) 'pinning_io.dart' as impl;

/// Certificate pinning hook: supplies the HTTP client used for every API call.
abstract class CertificatePinning {
  http.Client client();
}

/// Platform trust store, no pinning (default; local development).
class NoPinning implements CertificatePinning {
  const NoPinning();

  @override
  http.Client client() => http.Client();
}

/// Trusts only the given CA certificates (PEM) for the API connection: a certificate from any other CA, including
/// one added to the device's user trust store, is refused. Pin the issuing CA (and its successor during rotation)
/// rather than the leaf so certificate renewals need no app release. On the web the browser decides; this falls back
/// to the default client.
class CaPinning implements CertificatePinning {
  const CaPinning(this.caCertificatesPem);
  final List<String> caCertificatesPem;

  @override
  http.Client client() => impl.pinnedClient(caCertificatesPem);
}

CertificatePinning pinningFor(List<String> pem) => pem.isEmpty ? const NoPinning() : CaPinning(pem);
