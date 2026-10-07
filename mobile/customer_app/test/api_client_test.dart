import 'package:customer_app/src/api/api_client.dart';
import 'package:customer_app/src/api/models.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_backend.dart';

void main() {
  late FakeBackend backend;

  setUp(() => backend = FakeBackend());

  test('maps an account page and balances; dev headers identify the customer', () async {
    backend.on('GET', '/v1/me/accounts', page([account('a1'), account('a2', number: '5566')]));
    backend.on('GET', '/v1/me/accounts/a1/balance', balance('1250.5'));
    final s = await testServices(backend);

    final accounts = await s.api.accounts();
    expect(accounts.map((a) => a.id), ['a1', 'a2']);
    expect(accounts.first.accountType, 'CURRENT');
    expect(accounts.first.label, 'Everyday 0011223344');
    final b = await s.api.accountBalance('a1');
    expect(b.available, '1250.5');
    expect(b.currency, 'KES');

    final r = backend.requests.first;
    expect(r.url.queryParameters['limit'], '100');
    expect(r.headers['X-Customer-Id'], customer);
    expect(r.headers['X-Tenant-Id'], tenant);
    expect(r.headers['X-Device-Id'], isNotEmpty);
    expect(r.headers.containsKey('Idempotency-Key'), isFalse, reason: 'reads carry no Idempotency-Key');
  });

  test('ledger entries keep decimal strings and the cursor', () async {
    backend.on('GET', '/v1/me/accounts/a1/transactions', {
      'data': [
        {
          'id': 'e1', 'direction': 'CREDIT', 'amount': '100.0001', 'currency': 'KES',
          'effective_at': '2026-10-01T10:00:00Z', 'counterparty_name': 'Salary', 'balance_after': '900'
        }
      ],
      'has_more': true,
      'next_cursor': 'c2'
    });
    final s = await testServices(backend);
    final p = await s.api.accountTransactions('a1');
    expect(p.hasMore, isTrue);
    expect(p.nextCursor, 'c2');
    expect(p.data.single.amount, '100.0001');
    expect(p.data.single.credit, isTrue);
    expect(p.data.single.title, 'Salary');
  });

  test('every write carries an Idempotency-Key; a passed key is reused verbatim', () async {
    backend.on('POST', '/v1/me/cards/c1/freeze', card('c1', status: 'FROZEN'));
    backend.on('POST', '/v1/me/payments', {
      'id': 'p1', 'status': 'ACCEPTED', 'amount': '10', 'currency': 'KES', 'created_at': '2026-10-07T08:00:00Z'
    });
    final s = await testServices(backend);
    final frozen = await s.api.freezeCard('c1');
    expect(frozen.frozen, isTrue);
    expect(backend.requests.last.headers['Idempotency-Key'], hasLength(36));

    final req = const PaymentRequest.payee(fromAccountId: 'a1', payeeId: 'p9', amount: '10', reference: 'rent');
    await s.api.pay(req, idempotencyKey: 'key-123');
    final sent = backend.requests.last;
    expect(sent.headers['Idempotency-Key'], 'key-123');
    expect(sent.body, contains('"kind":"PAYEE"'));
    expect(sent.body, contains('"payee_id":"p9"'));
    expect(sent.body, contains('"channel":"MOBILE"'));
    expect(sent.body, isNot(contains('to_account_id')));
  });

  test('problems map to ApiException with the RFC 9470 step-up challenge', () async {
    backend.on('POST', '/v1/me/payments',
        {'status': 401, 'code': 'step_up_required', 'title': 'Step-up required', 'detail': 'Confirm it is you'},
        status: 401,
        headers: {'www-authenticate': 'Bearer error="insufficient_user_authentication", acr_values="2", max_age=300'});
    final s = await testServices(backend);
    try {
      await s.api.pay(const PaymentRequest.own(fromAccountId: 'a1', toAccountId: 'a2', amount: '1'), idempotencyKey: 'k');
      fail('expected ApiException');
    } on ApiException catch (e) {
      expect(e.isStepUpRequired, isTrue);
      expect(e.status, 401);
      expect(e.challenge?.acr, '2');
      expect(e.challenge?.maxAge, const Duration(seconds: 300));
      expect(e.toString(), 'Confirm it is you');
    }
  });

  test('validation problems keep field errors; non-JSON errors keep the text', () async {
    backend.on('POST', '/v1/me/payees', {
      'status': 422, 'code': 'validation_failed', 'title': 'Invalid',
      'errors': [{'field': 'account_number', 'code': 'invalid', 'message': 'not a valid account'}]
    }, status: 422);
    final s = await testServices(backend);
    try {
      await s.api.addPayee(name: 'x', accountIdType: 'IBAN', accountNumber: '1', country: 'KE');
      fail('expected ApiException');
    } on ApiException catch (e) {
      expect(e.status, 422);
      expect(e.problem.errors.single.field, 'account_number');
    }
  });

  test('session_revoked signs the app out locally', () async {
    backend.on('GET', '/v1/me/accounts', {'status': 401, 'code': 'session_revoked', 'title': 'Signed out'}, status: 401);
    final s = await testServices(backend);
    expect(s.auth.signedIn, isTrue);
    await expectLater(s.api.accounts(), throwsA(isA<ApiException>()));
    expect(s.auth.signedIn, isFalse);
    expect(s.sessionEnded.value, isTrue);
  });

  test('spending and security overview map the untyped objects', () async {
    backend.on('GET', '/v1/me/insights/spending', {
      'customer_id': customer,
      'categories': [
        {'category_name': 'Groceries', 'category_code': 'GROC', 'currency': 'KES', 'spend': '420.5', 'income': '0', 'is_income': false}
      ]
    });
    backend.on('GET', '/v1/me/security', {
      'authentication': {'level': 2, 'amr': ['pwd', 'otp']},
      'factors': [{'id': 'f1', 'type': 'otp', 'label': 'Phone'}],
      'has_second_factor': true,
      'step_up': {'acr': '2', 'max_age_seconds': 300, 'operations': []}
    });
    final s = await testServices(backend);
    final spend = await s.api.spending(from: DateTime.utc(2026, 10), to: DateTime.utc(2026, 10, 7));
    expect(spend.single.name, 'Groceries');
    expect(backend.requests.last.url.queryParameters['from'], '2026-10-01T00:00:00.000Z');
    final sec = await s.api.security();
    expect(sec.level, 2);
    expect(sec.factors.single, 'otp (Phone)');
  });
}
