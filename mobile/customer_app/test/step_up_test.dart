import 'package:customer_app/src/api/api_client.dart';
import 'package:customer_app/src/api/models.dart';
import 'package:customer_app/src/auth/auth_controller.dart';
import 'package:customer_app/src/auth/authenticator.dart';
import 'package:customer_app/src/auth/session.dart';
import 'package:customer_app/src/config.dart';
import 'package:customer_app/src/security/integrity.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_backend.dart';

void main() {
  group('StepUpChallenge.parse', () {
    test('reads acr_values and max_age', () {
      final c = StepUpChallenge.parse('Bearer error="insufficient_user_authentication", acr_values="2", max_age=120')!;
      expect(c.acr, '2');
      expect(c.maxAge, const Duration(seconds: 120));
    });
    test('ignores other challenges', () {
      expect(StepUpChallenge.parse('Bearer error="invalid_token"'), isNull);
      expect(StepUpChallenge.parse(null), isNull);
    });
  });

  group('Session.stepUpFresh', () {
    final now = DateTime.utc(2026, 10, 7, 12);
    Session oidc(String acr, Duration ago) => Session(
        mode: AuthMode.oidc, user: 'u', customerId: customer, tenantId: tenant, acr: acr, authTime: now.subtract(ago));

    test('needs acr 2 and a recent authentication', () {
      expect(oidc('2', const Duration(seconds: 30)).stepUpFresh(now), isTrue);
      expect(oidc('1', const Duration(seconds: 30)).stepUpFresh(now), isFalse, reason: 'password only');
      expect(oidc('2', const Duration(minutes: 5)).stepUpFresh(now), isFalse, reason: 'older than 240 s');
    });
    test('dev mode needs an explicit confirmation', () {
      const s = Session(mode: AuthMode.dev, user: 'u', customerId: customer, tenantId: tenant);
      expect(s.stepUpFresh(now), isFalse);
      expect(s.copyWith(stepUpAt: now).stepUpFresh(now), isTrue);
      expect(s.copyWith(stepUpAt: now).authHeaders()['X-Acr'], '2');
    });
  });

  group('AuthController.withStepUp', () {
    late FakeBackend backend;
    late FakeAuthenticator idp;
    setUp(() {
      backend = FakeBackend();
      idp = FakeAuthenticator();
    });

    Future<AuthController> signedIn({DeviceIntegrityCheck integrity = const NoopIntegrityCheck()}) async {
      final s = await testServices(backend, authenticator: idp, integrity: integrity);
      return s.auth;
    }

    test('a password-only session steps up before the operation', () async {
      final auth = await signedIn();
      expect(auth.session!.acr, '1');
      var calls = 0;
      final r = await auth.withStepUp(() async => ++calls);
      expect(r, 1);
      expect(idp.stepUps, hasLength(1));
      expect(auth.session!.acr, '2');
    });

    test('a fresh acr-2 session runs without asking again', () async {
      final auth = await signedIn();
      await auth.stepUp();
      idp.stepUps.clear();
      await auth.withStepUp(() async => 1);
      expect(idp.stepUps, isEmpty);
    });

    test('401 step_up_required: steps up with the server challenge and retries with the same Idempotency-Key',
        () async {
      var attempts = 0;
      backend.handle('POST', '/v1/me/payments', (r) {
        attempts++;
        if (attempts == 1) {
          return const Reply(401, {'status': 401, 'code': 'step_up_required', 'title': 'Step-up'},
              headers: {'www-authenticate': 'Bearer error="insufficient_user_authentication", acr_values="2", max_age=60'});
        }
        return const Reply(200, {'id': 'p1', 'status': 'ACCEPTED', 'amount': '5', 'currency': 'KES'});
      });
      final s = await testServices(backend, authenticator: idp);
      await s.auth.stepUp(); // fresh: the first attempt goes straight to the server
      idp.stepUps.clear();

      const req = PaymentRequest.own(fromAccountId: 'a1', toAccountId: 'a2', amount: '5');
      final p = await s.auth.withStepUp(() => s.api.pay(req, idempotencyKey: 'confirm-1'));

      expect(p.status, 'ACCEPTED');
      expect(idp.stepUps.single.maxAge, const Duration(seconds: 60));
      final sent = backend.calls('POST', '/v1/me/payments');
      expect(sent, hasLength(2));
      expect(sent.map((r) => r.headers['Idempotency-Key']).toSet(), {'confirm-1'});
      expect(sent.last.headers['Authorization'], 'Bearer at-2-1', reason: 'retry uses the stepped-up token');
    });

    test('other problems are not retried', () async {
      backend.on('POST', '/v1/me/payments', {'status': 422, 'code': 'insufficient_funds', 'title': 'No funds'},
          status: 422);
      final s = await testServices(backend, authenticator: idp);
      await s.auth.stepUp();
      await expectLater(
          s.auth.withStepUp(() => s.api.pay(
              const PaymentRequest.own(fromAccountId: 'a1', toAccountId: 'a2', amount: '5'), idempotencyKey: 'k')),
          throwsA(isA<ApiException>().having((e) => e.problem.code, 'code', 'insufficient_funds')));
      expect(backend.calls('POST', '/v1/me/payments'), hasLength(1));
    });

    test('no second factor enrolled: StepUpUnavailable, nothing sent', () async {
      idp = FakeAuthenticator(acrAfterStepUp: '1');
      final auth = await signedIn();
      var ran = false;
      await expectLater(auth.withStepUp(() async => ran = true), throwsA(isA<StepUpUnavailable>()));
      expect(ran, isFalse);
    });

    test('a compromised device is refused before any step-up', () async {
      final auth = await signedIn(
          integrity: const StaticIntegrityCheck(IntegrityVerdict(compromised: true, reasons: ['rooted'])));
      await expectLater(auth.withStepUp(() async => 1), throwsA(isA<DeviceCompromised>()));
      expect(idp.stepUps, isEmpty);
    });
  });
}
