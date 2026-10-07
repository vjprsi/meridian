import 'dart:convert';

import 'package:agent_app/src/agent_controller.dart';
import 'package:agent_app/src/auth/auth.dart';
import 'package:agent_app/src/config.dart';
import 'package:agent_app/src/crypto/device_key.dart';
import 'package:agent_app/src/outbox/outbox.dart';
import 'package:agent_app/src/outbox/store.dart';
import 'package:agent_app/src/security/secure_store.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

const tenant = '0199a000-0000-7000-8000-00000000000a';
const agentId = '0199a000-0000-7000-8000-0000000a0001';

/// In-memory agent channel. Set [down] to simulate no connectivity (the client sees a socket error).
class FakeAgentChannel {
  bool down = false;
  final requests = <http.Request>[];
  final routes = <String, (int, Object?, Map<String, String>) Function(http.Request)>{};

  FakeAgentChannel() {
    on('GET', '/v1/agent/me', {'id': agentId, 'agent_code': 'AG-1', 'legal_name': 'Duka Bora', 'currency': 'KES', 'status': 'ACTIVE'});
    on('GET', '/v1/agent/me/outlets', {
      'data': [{'id': 'outlet-1', 'name': 'Main street', 'outlet_code': 'O-1', 'status': 'ACTIVE'}], 'has_more': false
    });
    on('GET', '/v1/agent/me/float-balance', {'currency': 'KES', 'available_balance': '50000'});
    on('POST', '/v1/agent/devices', {'id': 'key-1', 'agent_device_id': 'device-1', 'status': 'ACTIVE'});
    on('GET', '/v1/agent/billers', {'data': [{'id': 'b1', 'name': 'Power Co', 'category': 'UTILITY'}], 'has_more': false});
    on('GET', '/v1/agent/transactions', {'data': [], 'has_more': false});
    routes['POST /v1/agent/cash-in'] = (r) => (200, {
          'id': 'txn-${requests.length}', 'reference': 'R${requests.length}', 'txn_type': 'CASH_IN', 'status': 'COMPLETED',
          'amount': (jsonDecode(r.body) as Map)['amount'], 'currency': 'KES'
        }, const {});
  }

  void on(String method, String path, Object? body, {int status = 200}) =>
      routes['$method $path'] = (_) => (status, body, const {});

  List<http.Request> calls(String method, String path) =>
      requests.where((r) => r.method == method && r.url.path == path).toList();

  late final http.Client client = MockClient((req) async {
    if (down) throw http.ClientException('Network is unreachable');
    requests.add(req);
    final f = routes['${req.method} ${req.url.path}'];
    if (f == null) return http.Response('{"status":404,"code":"not_found"}', 404);
    final (status, body, headers) = f(req);
    return http.Response(jsonEncode(body), status, headers: {'content-type': 'application/json', ...headers});
  });
}

AppConfig testConfig() => const AppConfig(
      apiBaseUrl: 'https://api.test',
      issuer: 'https://idp.test/realms/meridian',
      clientId: 'meridian-agent-mobile',
      redirectUrl: 'io.meridian.agent:/oauth2redirect',
      authMode: AuthMode.dev,
    );

Future<AgentController> testController(FakeAgentChannel channel,
    {SecureStore? store, OutboxStore? outboxStore, DateTime Function()? now}) async {
  final s = store ?? MemorySecureStore();
  final c = AgentController(
    config: testConfig(),
    authenticator: DevAgentAuthenticator(now: now),
    store: s,
    outbox: Outbox(outboxStore ?? MemoryOutboxStore(), iterations: 1000, now: now),
    keys: SoftwareDeviceKeyStore(s),
    httpClient: channel.client,
    now: now,
  );
  await c.init();
  return c;
}

const devIdentity = DevAgentIdentity(tenantId: tenant, agentId: agentId, user: 'demo.agent', userId: 'user-1');
