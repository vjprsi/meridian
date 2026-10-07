import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import '../auth/session.dart';
import '../core/ids.dart';

/// RFC 9457 problem returned by every Meridian service.
class Problem {
  const Problem({this.type, this.title, this.status, this.code, this.detail, this.requestId, this.errors = const []});

  factory Problem.fromJson(Map<String, dynamic> j) => Problem(
        type: j['type'] as String?,
        title: j['title'] as String?,
        status: (j['status'] as num?)?.toInt(),
        code: j['code'] as String?,
        detail: j['detail'] as String?,
        requestId: j['request_id'] as String?,
        errors: (j['errors'] as List?)
                ?.whereType<Map<String, dynamic>>()
                .map((e) => FieldError(e['field']?.toString() ?? '', e['code']?.toString() ?? '',
                    e['message']?.toString() ?? ''))
                .toList() ??
            const [],
      );

  final String? type;
  final String? title;
  final int? status;
  final String? code;
  final String? detail;
  final String? requestId;
  final List<FieldError> errors;
}

class FieldError {
  const FieldError(this.field, this.code, this.message);
  final String field;
  final String code;
  final String message;
}

class ApiException implements Exception {
  ApiException(this.problem, {this.challenge, this.network = false});

  final Problem problem;

  /// 401 step_up_required: what the server asks for (WWW-Authenticate acr_values / max_age).
  final StepUpChallenge? challenge;

  /// The request did not reach the server or no response came back (outcome unknown for writes).
  final bool network;

  int? get status => problem.status;
  bool get isStepUpRequired => problem.code == 'step_up_required';
  bool get isSessionRevoked => problem.code == 'session_revoked';
  bool get isNotFound => problem.status == 404;

  @override
  String toString() => problem.detail ?? problem.title ?? 'Request failed (${problem.status ?? 'network'})';
}

typedef HeadersProvider = Future<Map<String, String>> Function();

/// Thin JSON-over-HTTP client: auth headers, Idempotency-Key on every write, problem mapping, timeouts.
class ApiClient {
  ApiClient({required this.baseUrl, required this.httpClient, required this.headers, this.timeout = const Duration(seconds: 30)});

  final String baseUrl;
  final http.Client httpClient;
  final HeadersProvider headers;
  final Duration timeout;

  /// Called with every failure (e.g. session_revoked signs the app out).
  void Function(ApiException e)? onError;

  Uri uri(String path, [Map<String, Object?>? query]) {
    final q = <String, String>{};
    query?.forEach((k, v) {
      if (v != null) q[k] = v.toString();
    });
    final base = Uri.parse(baseUrl);
    return base.replace(path: '${base.path.replaceFirst(RegExp(r'/$'), '')}$path', queryParameters: q.isEmpty ? null : q);
  }

  Future<dynamic> get(String path, {Map<String, Object?>? query}) => _send('GET', path, query: query);

  /// POST / PUT / DELETE. [idempotencyKey]: pass the key held for a whole confirmation so a resubmit (or the retry
  /// after step-up) is recognised by the server; otherwise a fresh key per call.
  Future<dynamic> write(String method, String path, {Object? body, String? idempotencyKey, Map<String, String>? extra}) =>
      _send(method, path, body: body, idempotencyKey: idempotencyKey ?? uuidV4(), extra: extra);

  Future<dynamic> _send(String method, String path,
      {Map<String, Object?>? query, Object? body, String? idempotencyKey, Map<String, String>? extra}) async {
    final req = http.Request(method, uri(path, query));
    req.headers.addAll(await headers());
    req.headers['Accept'] = 'application/json';
    if (idempotencyKey != null) req.headers['Idempotency-Key'] = idempotencyKey;
    if (extra != null) req.headers.addAll(extra);
    if (body != null) {
      req.headers['Content-Type'] = 'application/json';
      req.body = jsonEncode(body);
    }
    http.Response res;
    try {
      res = await http.Response.fromStream(await httpClient.send(req).timeout(timeout));
    } on TimeoutException {
      throw _fail(ApiException(const Problem(title: 'Timeout', detail: 'No response from the bank'), network: true));
    } on ApiException {
      rethrow;
    } catch (e) {
      throw _fail(ApiException(Problem(title: 'Network error', detail: e.toString()), network: true));
    }
    final text = res.body;
    if (res.statusCode >= 200 && res.statusCode < 300) {
      return text.isEmpty ? null : jsonDecode(text);
    }
    Problem p;
    try {
      final j = jsonDecode(text);
      p = j is Map<String, dynamic> ? Problem.fromJson(j) : Problem(detail: text);
    } catch (_) {
      p = Problem(title: res.reasonPhrase, detail: text.length > 300 ? text.substring(0, 300) : text);
    }
    p = Problem(
        type: p.type, title: p.title, status: res.statusCode, code: p.code, detail: p.detail,
        requestId: p.requestId, errors: p.errors);
    throw _fail(ApiException(p,
        challenge: res.statusCode == 401 ? StepUpChallenge.parse(res.headers['www-authenticate']) : null));
  }

  ApiException _fail(ApiException e) {
    onError?.call(e);
    return e;
  }
}
