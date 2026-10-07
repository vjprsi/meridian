import 'package:http/http.dart' as http;

/// Web: the browser owns TLS; pinning is not possible from the app.
http.Client pinnedClient(List<String> caCertificatesPem) => http.Client();
