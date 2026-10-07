import 'package:customer_app/src/ui/app.dart';
import 'package:customer_app/src/ui/screens/cards.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_backend.dart';

void main() {
  late FakeBackend backend;

  setUp(() {
    backend = FakeBackend()
      ..on('GET', '/v1/me/profile', {'id': customer, 'customer_number': 'C-1', 'kyc_tier': 'TIER_2', 'first_name': 'Amina'})
      ..on('GET', '/v1/me/accounts', page([account('a1'), account('a2', number: '5566')]))
      ..on('GET', '/v1/me/accounts/a1/balance', balance('1250.50'))
      ..on('GET', '/v1/me/accounts/a2/balance', balance('80'))
      ..on('GET', '/v1/me/wallets', page([]))
      ..on('GET', '/v1/me/cards', page([card('c1')]))
      ..on('GET', '/v1/me/loans', page([]))
      ..on('GET', '/v1/me/payees', page([payee('p1')]));
  });

  testWidgets('dev sign-in shows the home summary with accounts and balances', (tester) async {
    final s = await testServices(backend, signIn: false);
    await tester.pumpWidget(MeridianCustomerApp(services: s));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('customerId')), findsOneWidget);
    await tester.tap(find.byKey(const Key('signIn')));
    await tester.pumpAndSettle();

    expect(find.text('Hello, Amina'), findsOneWidget);
    expect(find.text('Everyday 0011223344'), findsOneWidget);
    expect(find.textContaining('1,250.50'), findsOneWidget);
    expect(find.text('Cards'), findsWidgets);
  });

  testWidgets('Swahili: the home screen is translated', (tester) async {
    final s = await testServices(backend);
    await s.settings.setLocale(const Locale('sw'));
    await tester.pumpWidget(MeridianCustomerApp(services: s));
    await tester.pumpAndSettle();
    expect(find.text('Habari, Amina'), findsOneWidget);
    expect(find.text('Akaunti'), findsOneWidget);
    expect(find.text('Mwanzo'), findsOneWidget);
  });

  testWidgets('transfer: validate, confirm, step-up, one payment with the confirmation Idempotency-Key',
      (tester) async {
    backend
      ..on('POST', '/v1/me/payments/validate', {'fee_amount': '15'})
      ..on('POST', '/v1/me/payments',
          {'id': 'pay1', 'status': 'ACCEPTED', 'amount': '200', 'currency': 'KES', 'created_at': '2026-10-07T08:00:00Z'});
    final s = await testServices(backend);
    await tester.pumpWidget(MeridianCustomerApp(services: s));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Pay'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('toPayee')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Amina Njeri · 998877').last);
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('amount')), '200');
    await tester.tap(find.byKey(const Key('review')));
    await tester.pumpAndSettle();

    expect(find.text('Confirm payment'), findsOneWidget);
    expect(find.text('Fee'), findsOneWidget);
    await tester.tap(find.byKey(const Key('confirmPayment')));
    await tester.pumpAndSettle();

    // Dev mode step-up: explained first, then recorded as a confirmation (X-Auth-Time / X-Acr headers).
    expect(find.text("Confirm it's you"), findsOneWidget);
    await tester.tap(find.text('Confirm'));
    await tester.pumpAndSettle();

    expect(find.text('Payment submitted (accepted)'), findsOneWidget);
    final validate = backend.calls('POST', '/v1/me/payments/validate').single;
    final pay = backend.calls('POST', '/v1/me/payments').single;
    expect(pay.headers['Idempotency-Key'], isNot(validate.headers['Idempotency-Key']));
    expect(pay.headers['X-Acr'], '2');
    expect(pay.headers['X-Auth-Time'], isNotNull);
    expect(pay.body, contains('"payee_id":"p1"'));
    expect(pay.body, contains('"amount":"200"'));
  });

  testWidgets('card detail: freeze needs no step-up; channel switches block a channel', (tester) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    var status = 'ACTIVE';
    backend
      ..handle('GET', '/v1/me/cards/c1', (_) => Reply(200, card('c1', status: status)))
      ..on('GET', '/v1/me/cards/c1/controls', page([
        {'id': 'x1', 'control_type': 'CHANNEL_DENY', 'channel': 'ATM', 'is_active': true}
      ]))
      ..on('GET', '/v1/me/cards/c1/limits', page([
        {'period': 'DAILY', 'channel': 'ANY', 'currency': 'KES', 'max_amount': '5000', 'used_amount': '100', 'is_active': true}
      ]))
      ..handle('POST', '/v1/me/cards/c1/freeze', (_) {
        status = 'FROZEN';
        return Reply(200, card('c1', status: status));
      })
      ..on('PUT', '/v1/me/cards/c1/channels/ECOM', {'id': 'x2', 'control_type': 'CHANNEL_DENY', 'channel': 'ECOM', 'is_active': true});
    final s = await testServices(backend);
    await tester.pumpWidget(MeridianCustomerApp(services: s));
    await tester.pumpAndSettle();
    final nav = tester.state<NavigatorState>(find.byType(Navigator).first);
    nav.push(MaterialPageRoute(builder: (_) => const CardDetailScreen(cardId: 'c1')));
    await tester.pumpAndSettle();

    expect(find.text('•••• •••• •••• 4242'.replaceAll(' ', '  ')), findsOneWidget);
    expect(find.text('100 of 5000 used'), findsOneWidget);
    final atm = tester.widget<SwitchListTile>(find.widgetWithText(SwitchListTile, 'ATM'));
    expect(atm.value, isFalse, reason: 'ATM is blocked');

    await tester.tap(find.byKey(const Key('freeze')));
    await tester.pumpAndSettle();
    expect(backend.calls('POST', '/v1/me/cards/c1/freeze'), hasLength(1));
    expect(find.text("Confirm it's you"), findsNothing);
    expect(find.text('Frozen'), findsWidgets);

    await tester.tap(find.widgetWithText(SwitchListTile, 'Online'));
    await tester.pumpAndSettle();
    expect(backend.calls('PUT', '/v1/me/cards/c1/channels/ECOM').single.body, '{"blocked":true}');
  });

  testWidgets('security: sign out everywhere revokes all sessions and signs out locally', (tester) async {
    backend
      ..on('GET', '/v1/me/security', {'authentication': {'level': 1, 'amr': ['pwd']}, 'factors': [], 'step_up': {}})
      ..on('GET', '/v1/me/sessions', page([
        {'id': 's1', 'session_id': 'sid-1', 'last_seen_at': '2026-10-07T07:00:00Z', 'status': 'ACTIVE', 'is_current': true},
        {'id': 's2', 'session_id': 'sid-2', 'device_label': 'Pixel 9', 'last_seen_at': '2026-10-06T07:00:00Z', 'status': 'ACTIVE', 'is_current': false},
      ]))
      ..on('POST', '/v1/me/sessions/revoke-all', page([]));
    final s = await testServices(backend);
    await tester.pumpWidget(MeridianCustomerApp(services: s));
    await tester.pumpAndSettle();
    await tester.tap(find.text('More'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('openSecurity')));
    await tester.pumpAndSettle();

    expect(find.text('This device'), findsOneWidget);
    expect(find.text('Pixel 9'), findsOneWidget);
    await tester.tap(find.byKey(const Key('signOutEverywhere')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Confirm'));
    await tester.pumpAndSettle();

    expect(backend.calls('POST', '/v1/me/sessions/revoke-all').single.body, '{"include_current":true}');
    expect(s.auth.signedIn, isFalse);
    expect(find.byKey(const Key('signIn')), findsOneWidget);
  });
}
