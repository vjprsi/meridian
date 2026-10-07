import 'dart:convert';
import 'dart:io';

import 'package:agent_app/src/api/agent_api.dart';
import 'package:agent_app/src/crypto/device_key.dart';
import 'package:agent_app/src/outbox/types.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yaml/yaml.dart';

import 'support/fakes.dart';

void main() {
  test('client operations exist in agent-channel-service.openapi.yaml', () {
    final spec = File('../../generated/openapi/services/agent-channel-service.openapi.yaml');
    final paths = (loadYaml(spec.readAsStringSync()) as YamlMap)['paths'] as YamlMap;
    final missing = [
      for (final (m, p) in AgentApi.operations)
        if (!((paths[p] as YamlMap?)?.containsKey(m.toLowerCase()) ?? false)) '$m $p'
    ];
    expect(missing, isEmpty);
  });

  test('setup registers the device public key; money POSTs carry a verifiable device proof', () async {
    final ch = FakeAgentChannel();
    final c = await testController(ch);
    await c.signIn(dev: devIdentity);
    expect(c.needsSetup, isTrue);
    await c.completeSetup(outletId: 'outlet-1', pin: '123456');
    expect(c.needsSetup, isFalse);
    expect(c.profile!.deviceId, 'device-1');

    final reg = jsonDecode(ch.calls('POST', '/v1/agent/devices').single.body) as Map<String, dynamic>;
    expect(reg['key_algorithm'], 'ES256');
    expect(reg['agent_outlet_id'], 'outlet-1');
    final publicKey = reg['public_key'] as String;
    expect(ch.calls('POST', '/v1/agent/devices').single.headers.containsKey('X-Agent-Device-Proof'), isFalse);

    final r = await c.capture(OpKind.cashIn,
        const OpPayload(request: {'payee_msisdn': '254700000001', 'amount': '100'}, amount: '100', currency: 'KES'));
    expect(r.synced, isTrue);
    final sent = ch.calls('POST', '/v1/agent/cash-in').single;
    expect(sent.headers['Idempotency-Key'], r.record.idempotencyKey);
    expect(sent.headers['X-Agent-Id'], agentId);
    final proof = sent.headers['X-Agent-Device-Proof']!.split('.');
    expect(proof[0], 'v1');
    expect(proof[1], 'device-1');
    final text = proofPayload(
        tenant: tenant, agent: agentId, device: 'device-1', method: 'POST', path: '/v1/agent/cash-in',
        idempotencyKey: r.record.idempotencyKey, ts: int.parse(proof[2]));
    expect(verifyEs256(publicKey, text, proof[3]), isTrue);

    // Locked: the app does not sign.
    c.lock();
    ch.on('POST', '/v1/agent/float-requests', {'id': 'f1', 'status': 'REQUESTED'});
    await c.api.post('/v1/agent/float-requests', {'request_type': 'TOP_UP', 'amount': '1'});
    expect(ch.requests.last.headers.containsKey('X-Agent-Device-Proof'), isFalse);
  });

  test('offline capture is queued, then replayed in order with the agent PIN from memory only', () async {
    final ch = FakeAgentChannel();
    var pinAsked = 0;
    ch.routes['POST /v1/agent/cash-in'] = (r) {
      final body = jsonDecode(r.body) as Map;
      if (body['pin'] == null) {
        pinAsked++;
        return (422, {'status': 422, 'code': 'pin_required', 'title': 'PIN required'}, const <String, String>{});
      }
      return (200, {'id': 'txn', 'reference': 'R', 'status': 'COMPLETED', 'amount': body['amount'], 'currency': 'KES'},
          const <String, String>{});
    };
    final c = await testController(ch);
    await c.signIn(dev: devIdentity);
    await c.completeSetup(outletId: 'outlet-1', pin: '123456');
    await c.refreshFloat();

    ch.down = true;
    await c.probe();
    expect(c.online, isFalse);
    final a = await c.capture(OpKind.cashIn, const OpPayload(request: {'payee_msisdn': '1', 'amount': '10'}, amount: '10'));
    final b = await c.capture(OpKind.cashIn, const OpPayload(request: {'payee_msisdn': '2', 'amount': '20'}, amount: '20'));
    expect(a.queued && b.queued, isTrue);
    expect(ch.calls('POST', '/v1/agent/cash-in'), isEmpty);

    ch.down = false;
    var r = await c.sync();
    expect(r.blocked, Blocker.needsPin);
    expect(pinAsked, 1);
    c.setAgentPin('4321');
    r = await c.sync();
    expect(r.synced, 2);
    final bodies = ch.calls('POST', '/v1/agent/cash-in').map((x) => jsonDecode(x.body) as Map).toList();
    expect(bodies.map((x) => x['amount']), ['10', '10', '20'], reason: 'first refused for the PIN, then in order');
    expect(bodies.last['pin'], '4321');
    expect(c.online, isTrue);
    // The PIN never reached the store.
    for (final rec in await c.outbox.all()) {
      expect(jsonEncode(c.outbox.decrypt(rec).payload.toJson()), isNot(contains('4321')));
    }
  });
}
