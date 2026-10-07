import 'dart:convert';
import 'dart:typed_data';

import 'package:agent_app/src/crypto/device_key.dart';
import 'package:agent_app/src/crypto/outbox_crypto.dart';
import 'package:agent_app/src/security/secure_store.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('software ES256 key: SPKI public key, P1363 base64url signatures that verify', () async {
    final store = MemorySecureStore();
    final keys = SoftwareDeviceKeyStore(store);
    expect(await keys.current(), isNull);
    final info = await keys.generate();
    final der = unb64(info.publicKey);
    expect(der.length, 91);
    expect(der.sublist(0, 2), [0x30, 0x59]);
    expect(info.hardwareBacked, isFalse);
    expect((await keys.current())!.publicKey, info.publicKey);

    const text = 'MERIDIAN-AGENT-DEVICE-PROOF-V1\ntenant=t';
    final sig = await keys.sign(text);
    expect(sig, isNot(contains('=')));
    expect(base64Url.decode(base64Url.normalize(sig)), hasLength(64));
    expect(verifyEs256(info.publicKey, text, sig), isTrue);
    expect(verifyEs256(info.publicKey, '$text!', sig), isFalse);

    await keys.delete();
    expect(() => keys.sign(text), throwsA(isA<StateError>()));
  });

  test('canonical proof and batch texts match agent-channel-service DeviceKeys', () {
    expect(
        proofPayload(
            tenant: 't', agent: 'a', device: 'd', method: 'POST', path: '/v1/agent/cash-in', idempotencyKey: 'k', ts: 1700000000),
        'MERIDIAN-AGENT-DEVICE-PROOF-V1\ntenant=t\nagent=a\ndevice=d\nmethod=POST\npath=/v1/agent/cash-in\n'
        'idempotency_key=k\ntimestamp=1700000000');
    final b = batchPayload(
        tenant: 't', agent: 'a', device: 'd', idempotencyKey: 'k',
        capturedFrom: DateTime.fromMillisecondsSinceEpoch(1000, isUtc: true),
        capturedTo: DateTime.fromMillisecondsSinceEpoch(2000, isUtc: true),
        applicationCount: 1, payloadUri: 'abc');
    expect(b.split('\n').last, 'payload_sha256=${sha256Hex(utf8.encode('abc'))}');
    expect(sha256Hex(utf8.encode('abc')), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
    expect(b, contains('captured_from=1000\ncaptured_to=2000'));
  });

  test('DER signatures from platform keystores convert to P1363', () {
    // SEQUENCE { INTEGER 0x00 ff.. (33 bytes, leading zero), INTEGER 0x01 }
    final r = [0x00, ...List.filled(32, 0xff)];
    final der = Uint8List.fromList([0x30, 2 + r.length + 3, 0x02, r.length, ...r, 0x02, 0x01, 0x01]);
    final p = derToP1363(der);
    expect(p, hasLength(64));
    expect(p.sublist(0, 32), List.filled(32, 0xff));
    expect(p.sublist(32), [...List.filled(31, 0), 1]);
  });
}
