import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import '../core/ids.dart';
import '../outbox/types.dart';

typedef Json = Map<String, dynamic>;

class ApiException implements Exception {
  ApiException(this.problem, {this.network = false});
  final Problem problem;
  final bool network;
  int? get status => problem.status;

  @override
  String toString() => problem.toString();
}

/// Raw response of a write (outbox executor needs status and the Idempotent-Replayed header).
class WriteResponse {
  const WriteResponse(this.status, this.body, {this.replayed = false});
  final int status;
  final Object? body;
  final bool replayed;
}

typedef HeadersProvider = Future<Map<String, String>> Function();

/// X-Agent-Device-Proof for a money POST (null when the device cannot sign: no key, app locked).
typedef ProofProvider = Future<String?> Function(String method, String path, String idempotencyKey);

/// Money movement: the channel accepts a recent sign-in or a proof signed by this device's registered key.
final moneyPaths = [
  RegExp(r'^/v1/agent/cash-in$'),
  RegExp(r'^/v1/agent/cash-out(/[^/]+/confirm)?$'),
  RegExp(r'^/v1/agent/bill-payments$'),
  RegExp(r'^/v1/agent/airtime-topups$'),
  RegExp(r'^/v1/agent/float-requests$'),
];

/// Typed client of the agent channel (`/v1/agent/*`, agent-channel-service). The server takes the agent from the
/// token, answers 404 for objects of other agents and rebuilds request bodies; the app sends only what was typed.
/// Hand-written against generated/openapi/services/agent-channel-service.openapi.yaml ([operations] is checked
/// against it by test/api_contract_test.dart).
class AgentApi {
  AgentApi({required this.baseUrl, required this.httpClient, required this.headers, this.proof,
      this.timeout = const Duration(seconds: 30)});

  final String baseUrl;
  final http.Client httpClient;
  final HeadersProvider headers;
  ProofProvider? proof;
  final Duration timeout;

  /// Every successful response (connectivity tracking).
  void Function()? onReachable;

  static const operations = <(String, String)>[
    ('GET', '/v1/agent/me'),
    ('GET', '/v1/agent/me/outlets'),
    ('GET', '/v1/agent/me/float-balance'),
    ('POST', '/v1/agent/devices'),
    ('GET', '/v1/agent/customer-wallets/lookup'),
    ('GET', '/v1/agent/transactions'),
    ('POST', '/v1/agent/cash-in'),
    ('POST', '/v1/agent/cash-out'),
    ('POST', '/v1/agent/cash-out/{transaction_id}/confirm'),
    ('GET', '/v1/agent/billers'),
    ('GET', '/v1/agent/bill-payments'),
    ('POST', '/v1/agent/bill-payments'),
    ('GET', '/v1/agent/float-requests'),
    ('POST', '/v1/agent/float-requests'),
  ];

  Uri _uri(String path, [Map<String, Object?>? q]) {
    final base = Uri.parse(baseUrl);
    final query = {
      for (final e in (q ?? const <String, Object?>{}).entries)
        if (e.value != null) e.key: '${e.value}'
    };
    return base.replace(path: '${base.path.replaceFirst(RegExp(r'/$'), '')}$path', queryParameters: query.isEmpty ? null : query);
  }

  Future<WriteResponse> _send(String method, String path, {Map<String, Object?>? query, Object? body, String? key}) async {
    final req = http.Request(method, _uri(path, query));
    req.headers.addAll(await headers());
    req.headers['Accept'] = 'application/json';
    if (method != 'GET') {
      key ??= uuidV4();
      req.headers['Idempotency-Key'] = key;
      if (moneyPaths.any((r) => r.hasMatch(path)) && proof != null) {
        final p = await proof!(method, path, key);
        if (p != null) req.headers['X-Agent-Device-Proof'] = p;
      }
    }
    if (body != null) {
      req.headers['Content-Type'] = 'application/json';
      req.body = jsonEncode(body);
    }
    http.Response res;
    try {
      res = await http.Response.fromStream(await httpClient.send(req).timeout(timeout));
    } on TimeoutException {
      throw ApiException(const Problem(title: 'Timeout', detail: 'No response'), network: true);
    } catch (e) {
      throw ApiException(Problem(title: 'Network error', detail: '$e'), network: true);
    }
    // Gateway "unreachable" answers count as network failures (the outcome of a write is unknown).
    if (res.statusCode == 502 || res.statusCode == 503 || res.statusCode == 504) {
      throw ApiException(Problem(status: res.statusCode, title: 'Unreachable'), network: true);
    }
    onReachable?.call();
    Object? decoded;
    try {
      decoded = res.body.isEmpty ? null : jsonDecode(res.body);
    } catch (_) {
      decoded = res.body;
    }
    if (res.statusCode >= 200 && res.statusCode < 300) {
      return WriteResponse(res.statusCode, decoded, replayed: res.headers['idempotent-replayed'] == 'true');
    }
    final p = decoded is Json ? Problem.fromJson(decoded) : Problem(detail: '$decoded');
    throw ApiException(Problem(status: res.statusCode, code: p.code, title: p.title, detail: p.detail));
  }

  Future<Object?> _get(String path, [Map<String, Object?>? q]) async => (await _send('GET', path, query: q)).body;

  /// Sends a write and returns the raw response (used by the outbox executor and online screens).
  Future<WriteResponse> post(String path, Object? body, {String? idempotencyKey}) =>
      _send('POST', path, body: body, key: idempotencyKey);

  List<Json> _data(Object? page) => ((page as Json?)?['data'] as List? ?? const []).whereType<Json>().toList();

  Future<Agent> me() async => Agent.fromJson(await _get('/v1/agent/me') as Json);
  Future<List<Outlet>> outlets() async => _data(await _get('/v1/agent/me/outlets', {'limit': 100})).map(Outlet.fromJson).toList();

  Future<({String available, String currency})> floatBalance() async {
    final j = await _get('/v1/agent/me/float-balance') as Json;
    return (available: '${j['available_balance'] ?? '0'}', currency: '${j['currency'] ?? ''}');
  }

  /// Registers this device and its public key (needs a recent sign-in). Returns the agent device id.
  Future<String> registerDevice({required String outletId, required String serial, required String publicKey,
      String deviceType = 'SMARTPHONE', String? model, String? osVersion, String appVersion = '0.1.0'}) async {
    final r = await post('/v1/agent/devices', {
      'serial_number': serial,
      'device_type': deviceType,
      'agent_outlet_id': outletId,
      'model': ?model,
      'os_version': ?osVersion,
      'app_version': appVersion,
      'key_algorithm': 'ES256',
      'public_key': publicKey,
    });
    return '${(r.body as Json)['agent_device_id']}';
  }

  Future<CustomerLookup> lookup({String? msisdn, String? walletNumber}) async => CustomerLookup.fromJson(
      await _get('/v1/agent/customer-wallets/lookup', {'msisdn': msisdn, 'wallet_number': walletNumber}) as Json);

  Future<List<AgentTxn>> transactions({String? idempotencyKey}) async =>
      _data(await _get('/v1/agent/transactions', {'limit': 50, 'idempotency_key': idempotencyKey}))
          .map(AgentTxn.fromJson)
          .toList();

  /// Online only: starts a cash-out for the customer's wallet; the customer confirms with PIN / OTP.
  Future<AgentTxn> cashOut({required String payerWalletId, required String amount, String? outletId, String? key}) async =>
      AgentTxn.fromJson((await post('/v1/agent/cash-out',
              {'payer_wallet_id': payerWalletId, 'amount': amount, 'agent_outlet_id': ?outletId},
              idempotencyKey: key))
          .body as Json);

  Future<AgentTxn> confirmCashOut(String txnId, {String? pin, String? otp, String? key}) async => AgentTxn.fromJson(
      (await post('/v1/agent/cash-out/${Uri.encodeComponent(txnId)}/confirm', {'pin': ?pin, 'otp': ?otp},
              idempotencyKey: key))
          .body as Json);

  Future<List<Biller>> billers() async => _data(await _get('/v1/agent/billers', {'limit': 100})).map(Biller.fromJson).toList();

  Future<Object?> firstByKey(String path, String key) async {
    final d = _data(await _get(path, {'idempotency_key': key}));
    return d.isEmpty ? null : d.first;
  }
}

class Agent {
  const Agent({required this.id, required this.agentCode, required this.legalName, required this.currency, required this.status});
  factory Agent.fromJson(Json j) => Agent(
      id: '${j['id']}', agentCode: '${j['agent_code'] ?? ''}', legalName: '${j['trading_name'] ?? j['legal_name'] ?? ''}',
      currency: '${j['currency'] ?? ''}', status: '${j['status'] ?? ''}');
  final String id;
  final String agentCode;
  final String legalName;
  final String currency;
  final String status;
}

class Outlet {
  const Outlet({required this.id, required this.name, required this.code, required this.status});
  factory Outlet.fromJson(Json j) =>
      Outlet(id: '${j['id']}', name: '${j['name'] ?? ''}', code: '${j['outlet_code'] ?? ''}', status: '${j['status'] ?? ''}');
  final String id;
  final String name;
  final String code;
  final String status;
}

class CustomerLookup {
  const CustomerLookup({required this.found, this.walletId, this.name, this.msisdn, this.currency, this.walletStatus});
  factory CustomerLookup.fromJson(Json j) => CustomerLookup(
      found: j['found'] == true,
      walletId: j['wallet_id'] as String?,
      name: j['display_name_masked'] as String?,
      msisdn: j['msisdn_masked'] as String?,
      currency: j['currency'] as String?,
      walletStatus: j['wallet_status'] as String?);
  final bool found;
  final String? walletId;
  final String? name;
  final String? msisdn;
  final String? currency;
  final String? walletStatus;
}

class AgentTxn {
  const AgentTxn({required this.id, required this.reference, required this.txnType, required this.status,
      required this.amount, required this.currency, this.createdAt, this.payeeMsisdn});
  factory AgentTxn.fromJson(Json j) => AgentTxn(
      id: '${j['id']}',
      reference: '${j['reference'] ?? ''}',
      txnType: '${j['txn_type'] ?? ''}',
      status: '${j['status'] ?? ''}',
      amount: '${j['amount'] ?? '0'}',
      currency: '${j['currency'] ?? ''}',
      createdAt: DateTime.tryParse('${j['created_at']}'),
      payeeMsisdn: j['payee_msisdn'] as String?);
  final String id;
  final String reference;
  final String txnType;
  final String status;
  final String amount;
  final String currency;
  final DateTime? createdAt;
  final String? payeeMsisdn;
}

class Biller {
  const Biller({required this.id, required this.name, required this.category, this.referenceLabel});
  factory Biller.fromJson(Json j) => Biller(
      id: '${j['id']}', name: '${j['name'] ?? ''}', category: '${j['category'] ?? ''}',
      referenceLabel: j['account_reference_label'] as String?);
  final String id;
  final String name;
  final String category;
  final String? referenceLabel;
}
