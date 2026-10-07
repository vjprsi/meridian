import 'package:customer_app/src/auth/auth_controller.dart';
import 'package:customer_app/src/core/money.dart';
import 'package:customer_app/src/security/secure_store.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_backend.dart';

void main() {
  group('biometric unlock of the stored refresh token', () {
    test('only the refresh token is stored, and only when biometrics are available', () async {
      final store = MemorySecureStore();
      final s = await testServices(FakeBackend(),
          authenticator: FakeAuthenticator(), biometric: FakeBiometric(), store: store);
      expect(s.auth.signedIn, isTrue);
      expect(store.values[AuthController.refreshTokenKey], 'rt-1');
      expect(store.values.values.any((v) => v.startsWith('at-')), isFalse, reason: 'access tokens stay in memory');

      final noBio = MemorySecureStore();
      await testServices(FakeBackend(),
          authenticator: FakeAuthenticator(), biometric: FakeBiometric(isAvailable: false), store: noBio);
      expect(noBio.values.containsKey(AuthController.refreshTokenKey), isFalse);
    });

    test('unlock needs the biometric prompt to pass, then refreshes the session', () async {
      final store = MemorySecureStore();
      final idp = FakeAuthenticator();
      final bio = FakeBiometric();
      await testServices(FakeBackend(), authenticator: idp, biometric: bio, store: store);

      // App restart: nothing in memory, the token is in the keystore.
      final restarted = await testServices(FakeBackend(),
          authenticator: idp, biometric: bio, store: store, signIn: false);
      expect(restarted.auth.signedIn, isFalse);
      expect(restarted.auth.canUnlock, isTrue);

      bio.pass = false;
      expect(await restarted.auth.unlock('reason'), isFalse);
      expect(idp.refreshes, 0, reason: 'the token is not used without the biometric');

      bio.pass = true;
      expect(await restarted.auth.unlock('reason'), isTrue);
      expect(idp.refreshes, 1);
      expect(restarted.auth.session!.customerId, customer);
    });

    test('turning biometric unlock off deletes the token; sign-out deletes it too', () async {
      final store = MemorySecureStore();
      final s = await testServices(FakeBackend(),
          authenticator: FakeAuthenticator(), biometric: FakeBiometric(), store: store);
      await s.auth.setBiometric(false);
      expect(store.values.containsKey(AuthController.refreshTokenKey), isFalse);
      await s.auth.setBiometric(true);
      expect(store.values[AuthController.refreshTokenKey], 'rt-1');
      await s.auth.signOut();
      expect(store.values.containsKey(AuthController.refreshTokenKey), isFalse);
    });
  });

  group('Money', () {
    test('adds decimal strings exactly', () {
      expect(Money.sum(['0.1', '0.2']), '0.3');
      expect(Money.sum(['100.0001', '-0.0001']), '100');
      expect(Money.compare('10.50', '10.5'), 0);
      expect(Money.isAmount('12.3456'), isTrue);
      expect(Money.isAmount('12.34567'), isFalse);
      expect(Money.isAmount('-1'), isFalse);
    });
  });
}
