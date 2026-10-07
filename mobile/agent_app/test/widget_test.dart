import 'package:agent_app/src/ui/app.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fakes.dart';

void main() {
  testWidgets('sign in, set up the device (outlet, key, app PIN), reach the home screen', (tester) async {
    final ch = FakeAgentChannel();
    final c = await testController(ch);
    await tester.pumpWidget(MeridianAgentApp(controller: c));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('signIn')));
    await tester.pumpAndSettle();
    expect(find.text('Set up this device'), findsOneWidget);
    expect(find.text('Main street (O-1)'), findsOneWidget);

    await tester.enterText(find.byKey(const Key('pin')), '123456');
    await tester.enterText(find.byKey(const Key('pin2')), '654321');
    await tester.tap(find.byKey(const Key('finishSetup')));
    await tester.pumpAndSettle();
    expect(find.text('The PINs do not match'), findsOneWidget);

    await tester.enterText(find.byKey(const Key('pin2')), '123456');
    await tester.tap(find.byKey(const Key('finishSetup')));
    await tester.pumpAndSettle();

    expect(find.text('Duka Bora'), findsOneWidget);
    expect(find.textContaining('50,000'), findsOneWidget);
    expect(find.text('Online'), findsOneWidget);
    expect(ch.calls('POST', '/v1/agent/devices'), hasLength(1));

    // Lock and unlock with the app PIN.
    await tester.tap(find.byTooltip('Lock'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('unlockPin')), '000000');
    await tester.tap(find.byKey(const Key('unlock')));
    await tester.pumpAndSettle();
    expect(find.text('Wrong PIN'), findsOneWidget);
    await tester.enterText(find.byKey(const Key('unlockPin')), '123456');
    await tester.tap(find.byKey(const Key('unlock')));
    await tester.pumpAndSettle();
    expect(find.text('Duka Bora'), findsOneWidget);
  });

  testWidgets('offline cash-in is saved on the device and shown on the sync screen; sync sends it', (tester) async {
    final ch = FakeAgentChannel();
    final c = await testController(ch);
    await c.signIn(dev: devIdentity);
    await c.completeSetup(outletId: 'outlet-1', pin: '123456');
    await c.refreshFloat();
    ch.down = true;
    await c.probe();

    await tester.pumpWidget(MeridianAgentApp(controller: c));
    await tester.pumpAndSettle();
    expect(find.text('Offline'), findsOneWidget);

    await tester.tap(find.byKey(const Key('action-Cash-in')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('msisdn')), '254700000001');
    await tester.enterText(find.byKey(const Key('amount')), '250');
    await tester.tap(find.byKey(const Key('submit')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('confirm')));
    await tester.pumpAndSettle();
    expect(find.text('Saved on this device; it will be sent when online'), findsOneWidget);
    expect(find.text('1 waiting to sync'), findsOneWidget);

    await tester.tap(find.text('1 waiting to sync'));
    await tester.pumpAndSettle();
    expect(find.textContaining('Waiting'), findsOneWidget);

    ch.down = false;
    await tester.tap(find.byKey(const Key('syncNow')));
    await tester.pumpAndSettle();
    expect(find.text('1 sent, 0 still waiting'), findsOneWidget);
    expect(ch.calls('POST', '/v1/agent/cash-in'), hasLength(1));
    expect(find.textContaining('Sent'), findsWidgets);
  });

  testWidgets('Swahili', (tester) async {
    final c = await testController(FakeAgentChannel());
    await tester.pumpWidget(MeridianAgentApp(controller: c, locale: const Locale('sw')));
    await tester.pumpAndSettle();
    expect(find.text('Meridian Wakala'), findsOneWidget);
    expect(find.text('Kitambulisho cha wakala'), findsOneWidget);
  });
}
