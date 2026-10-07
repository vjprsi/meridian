import 'dart:convert';

import 'package:customer_app/src/app_services.dart';
import 'package:customer_app/src/auth/auth_controller.dart';
import 'package:customer_app/src/auth/authenticator.dart';
import 'package:customer_app/src/auth/session.dart';
import 'package:customer_app/src/config.dart';
import 'package:customer_app/src/security/biometric.dart';
import 'package:customer_app/src/security/integrity.dart';
import 'package:customer_app/src/security/secure_store.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

/// A canned response; [handler] may compute it from the request.
class Reply {
  const Reply(this.status, this.body, {this.headers = const {}});
  final int status;
  final Object? body;
  final Map<String, String> headers;
}

/// In-memory customer channel: routes "METHOD /path" to replies and records every request.
class FakeBackend {
  final routes = <String, Reply Function(http.Request r)>{};
  final requests = <http.Request>[];

  void on(String method, String path, Object? body, {int status = 200, Map<String, String> headers = const {}}) =>
      routes['$method $path'] = (_) => Reply(status, body, headers: headers);

  void handle(String method, String path, Reply Function(http.Request r) f) => routes['$method $path'] = f;

  List<http.Request> calls(String method, String path) =>
      requests.where((r) => r.method == method && r.url.path == path).toList();

  late final http.Client client = MockClient((req) async {
    requests.add(req);
    final f = routes['${req.method} ${req.url.path}'];
    if (f == null) {
      return http.Response(jsonEncode({'status': 404, 'code': 'not_found', 'title': 'Not found'}), 404,
          headers: {'content-type': 'application/problem+json'});
    }
    final r = f(req);
    return http.Response(r.body == null ? '' : jsonEncode(r.body), r.status,
        headers: {'content-type': 'application/json', ...r.headers});
  });
}

const tenant = '0199a000-0000-7000-8000-00000000000a';
const customer = '0199a000-0000-7000-8000-0000000c0001';

AppConfig testConfig({AuthMode mode = AuthMode.dev}) => AppConfig(
      apiBaseUrl: 'https://api.test',
      issuer: 'https://idp.test/realms/meridian',
      clientId: 'meridian-customer-mobile',
      redirectUrl: 'io.meridian.customer:/oauth2redirect',
      authMode: mode,
    );

/// Builds services wired to [backend] with a dev authenticator (or [authenticator]).
Future<AppServices> testServices(FakeBackend backend,
    {Authenticator? authenticator,
    BiometricGate biometric = const NoBiometricGate(),
    DeviceIntegrityCheck integrity = const NoopIntegrityCheck(),
    SecureStore? store,
    bool signIn = true,
    DateTime Function()? now}) async {
  final s = store ?? MemorySecureStore();
  final auth = AuthController(
    config: testConfig(),
    authenticator: authenticator ?? DevAuthenticator(now: now),
    store: s,
    biometric: biometric,
    integrity: integrity,
    now: now,
  );
  final services = AppServices.wire(config: testConfig(), auth: auth, settings: AppSettings(s), http: backend.client);
  await services.init();
  if (signIn) {
    await auth.signIn(dev: const DevIdentity(tenantId: tenant, customerId: customer, user: 'demo.customer'));
  }
  return services;
}

Map<String, Object?> page(List<Object?> data) => {'data': data, 'has_more': false, 'next_cursor': null};

Map<String, Object?> account(String id, {String number = '0011223344', String currency = 'KES'}) => {
      'id': id,
      'account_number': number,
      'account_type': 'CURRENT',
      'currency': currency,
      'status': 'ACTIVE',
      'row_version': 1,
      'nickname': 'Everyday $number',
    };

Map<String, Object?> balance(String available, {String currency = 'KES'}) =>
    {'currency': currency, 'available_balance': available, 'posted_balance': available};

Map<String, Object?> card(String id, {String status = 'ACTIVE'}) => {
      'id': id,
      'last4': '4242',
      'cardholder_name': 'DEMO CUSTOMER',
      'form_factor': 'VIRTUAL',
      'status': status,
      'expiry_month': 9,
      'expiry_year': 2029,
    };

Map<String, Object?> payee(String id) => {
      'id': id,
      'name': 'Amina Njeri',
      'account_id_type': 'ACCOUNT_NUMBER',
      'account_number': '998877',
      'country': 'KE',
      'status': 'ACTIVE',
      'trusted': true,
    };

/// Records step-up requests; returns sessions with acr 2 and a fresh auth_time.
class FakeAuthenticator implements Authenticator {
  FakeAuthenticator({this.acrAfterStepUp = '2', DateTime Function()? now}) : now = now ?? DateTime.now;
  final String acrAfterStepUp;
  final DateTime Function() now;
  final stepUps = <StepUpChallenge>[];
  int refreshes = 0;

  Session _s({String acr = '1', String? refresh = 'rt-1'}) => Session(
        mode: AuthMode.oidc,
        user: 'demo.customer',
        customerId: customer,
        tenantId: tenant,
        accessToken: 'at-$acr-${stepUps.length}',
        refreshToken: refresh,
        authTime: now(),
        acr: acr,
      );

  @override
  Future<Session> signIn({DevIdentity? dev}) async => _s();

  @override
  Future<Session> stepUp(Session current, StepUpChallenge challenge) async {
    stepUps.add(challenge);
    final s = _s(acr: acrAfterStepUp);
    if (acrAfterStepUp != '2') throw StepUpUnavailable();
    return s;
  }

  @override
  Future<Session> refresh(Session current) async {
    refreshes++;
    return _s(refresh: current.refreshToken);
  }

  @override
  Future<void> signOut(Session current) async {}
}

class FakeBiometric implements BiometricGate {
  FakeBiometric({this.isAvailable = true, this.pass = true});
  bool isAvailable;
  bool pass;
  int prompts = 0;

  @override
  Future<bool> available() async => isAvailable;

  @override
  Future<bool> authenticate(String reason) async {
    prompts++;
    return pass;
  }
}
