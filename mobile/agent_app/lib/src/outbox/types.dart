import '../crypto/outbox_crypto.dart';

/// Operations that may be captured while offline and replayed later. Cash-out is deliberately absent (it needs the
/// customer's live confirmation).
enum OpKind { cashIn, billPayment, floatRequest }

/// Operations that spend the agent's float (they count against the last known float balance offline).
const floatDebits = {OpKind.cashIn, OpKind.billPayment};

/// PENDING: waiting to be sent (or re-sent). SYNCED: accepted (possibly as an idempotent replay). REJECTED: refused
/// with a problem; final. NEEDS_REVIEW: the outcome could not be established automatically.
enum OpStatus { pending, synced, rejected, needsReview }

/// Why the queue stopped at a record (it stays PENDING; later records wait to keep the order).
enum Blocker { network, auth, needsPin, inProgress }

/// RFC 9457 problem (subset kept in the outbox).
class Problem {
  const Problem({this.status, this.code, this.title, this.detail});
  factory Problem.fromJson(Map<String, dynamic> j) => Problem(
      status: (j['status'] as num?)?.toInt(),
      code: j['code'] as String?,
      title: j['title'] as String?,
      detail: j['detail'] as String?);
  final int? status;
  final String? code;
  final String? title;
  final String? detail;
  Map<String, dynamic> toJson() => {'status': status, 'code': code, 'title': title, 'detail': detail};

  @override
  String toString() => detail ?? title ?? code ?? 'HTTP $status';
}

/// What is encrypted inside a record. Never contains a customer PIN or the agent's PIN.
class OpPayload {
  const OpPayload({required this.request, this.amount, this.currency, this.display = const {}});
  factory OpPayload.fromJson(Map<String, dynamic> j) => OpPayload(
        request: Map<String, dynamic>.from(j['request'] as Map),
        amount: j['amount'] as String?,
        currency: j['currency'] as String?,
        display: Map<String, String>.from((j['display'] as Map?) ?? const {}),
      );

  /// The request body exactly as it will be sent (same body on every attempt, so idempotent replays match).
  final Map<String, dynamic> request;
  final String? amount;
  final String? currency;

  /// Human-readable fields for the pending list and the provisional receipt.
  final Map<String, String> display;

  Map<String, dynamic> toJson() => {'request': request, 'amount': amount, 'currency': currency, 'display': display};
}

/// Server outcome stored (encrypted) once an attempt was made.
class OpResult {
  const OpResult({this.httpStatus, this.duplicate = false, this.body, this.problem, this.note});
  factory OpResult.fromJson(Map<String, dynamic> j) => OpResult(
        httpStatus: (j['httpStatus'] as num?)?.toInt(),
        duplicate: j['duplicate'] == true,
        body: j['body'],
        problem: j['problem'] == null ? null : Problem.fromJson(Map<String, dynamic>.from(j['problem'] as Map)),
        note: j['note'] as String?,
      );
  final int? httpStatus;
  final bool duplicate;
  final Object? body;
  final Problem? problem;
  final String? note;

  Map<String, dynamic> toJson() =>
      {'httpStatus': httpStatus, 'duplicate': duplicate, 'body': body, 'problem': problem?.toJson(), 'note': note};
}

/// A stored record: metadata in clear (order, status, timestamps), payload and result sealed with AES-GCM.
class OutboxRecord {
  OutboxRecord({
    required this.id,
    required this.seq,
    required this.kind,
    required this.status,
    required this.idempotencyKey,
    required this.createdAt,
    required this.updatedAt,
    required this.createdOffline,
    this.attempts = 0,
    this.lastAttemptAt,
    this.blocker,
    required this.payload,
    this.result,
  });

  factory OutboxRecord.fromJson(Map<String, dynamic> j) => OutboxRecord(
        id: j['id'] as String,
        seq: (j['seq'] as num).toInt(),
        kind: OpKind.values.byName(j['kind'] as String),
        status: OpStatus.values.byName(j['status'] as String),
        idempotencyKey: j['idempotencyKey'] as String,
        createdAt: DateTime.parse(j['createdAt'] as String),
        updatedAt: DateTime.parse(j['updatedAt'] as String),
        createdOffline: j['createdOffline'] == true,
        attempts: (j['attempts'] as num?)?.toInt() ?? 0,
        lastAttemptAt: j['lastAttemptAt'] == null ? null : DateTime.parse(j['lastAttemptAt'] as String),
        blocker: j['blocker'] == null ? null : Blocker.values.byName(j['blocker'] as String),
        payload: Sealed.fromJson(Map<String, dynamic>.from(j['payload'] as Map)),
        result: j['result'] == null ? null : Sealed.fromJson(Map<String, dynamic>.from(j['result'] as Map)),
      );

  final String id;
  final int seq;
  final OpKind kind;
  OpStatus status;
  final String idempotencyKey;
  final DateTime createdAt;
  DateTime updatedAt;
  final bool createdOffline;
  int attempts;
  DateTime? lastAttemptAt;
  Blocker? blocker;
  final Sealed payload;
  Sealed? result;

  Map<String, dynamic> toJson() => {
        'id': id,
        'seq': seq,
        'kind': kind.name,
        'status': status.name,
        'idempotencyKey': idempotencyKey,
        'createdAt': createdAt.toUtc().toIso8601String(),
        'updatedAt': updatedAt.toUtc().toIso8601String(),
        'createdOffline': createdOffline,
        'attempts': attempts,
        'lastAttemptAt': lastAttemptAt?.toUtc().toIso8601String(),
        'blocker': blocker?.name,
        'payload': payload.toJson(),
        'result': result?.toJson(),
      };
}

/// A decrypted record for display.
class OutboxView {
  const OutboxView(this.record, this.payload, this.result);
  final OutboxRecord record;
  final OpPayload payload;
  final OpResult? result;
}

/// Raw result of one attempt as seen by the executor.
sealed class AttemptOutcome {
  const AttemptOutcome();
}

class AttemptOk extends AttemptOutcome {
  const AttemptOk(this.status, this.body, {this.replayed = false});
  final int status;
  final Object? body;
  final bool replayed;
}

class AttemptHttp extends AttemptOutcome {
  const AttemptHttp(this.status, this.problem);
  final int status;
  final Problem problem;
}

class AttemptNetwork extends AttemptOutcome {
  const AttemptNetwork(this.message);
  final String message;
}

class ExecContext {
  ExecContext({this.agentPin});

  /// Agent wallet PIN held in memory for this sync only (never persisted).
  String? agentPin;
}

/// Sends one record to the API. Implemented by the app (typed client); tests use fakes.
abstract class Executor {
  Future<AttemptOutcome> execute(OutboxRecord rec, OpPayload payload, ExecContext ctx);

  /// Looks for a server object created with this record's Idempotency-Key (outcome unknown, e.g.
  /// idempotency_key_reused). Returns it, or null when nothing matches; throws when the lookup itself failed.
  Future<Object?> lookup(OutboxRecord rec, OpPayload payload);
}

class FlushReport {
  int attempted = 0;
  int synced = 0;
  int duplicates = 0;
  int rejected = 0;
  int review = 0;
  int remaining = 0;
  Blocker? blocked;
}
