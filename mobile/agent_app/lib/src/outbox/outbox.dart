import 'dart:async';

import '../core/ids.dart';
import '../core/money.dart';
import '../crypto/outbox_crypto.dart';
import 'store.dart';
import 'types.dart';

/// Conservative limits for operations captured offline (the server cannot check limits, fraud or the float in real
/// time). Amounts are decimal strings in the agent's currency.
class OfflinePolicy {
  const OfflinePolicy({
    this.maxAmountPerOp = '5000',
    this.maxTotalAmount = '20000',
    this.maxCount = 10,
    this.maxOfflineHours = 12,
  });
  final String maxAmountPerOp;
  final String maxTotalAmount;
  final int maxCount;

  /// Offline capture stops this many hours after the last successful contact with the server.
  final int maxOfflineHours;
}

enum LimitCode { amountOverLimit, totalOverLimit, countOverLimit, offlineTooLong, floatInsufficient, locked }

class OfflineLimitError implements Exception {
  OfflineLimitError(this.code, this.message);
  final LimitCode code;
  final String message;

  @override
  String toString() => message;
}

class OutboxLockedError implements Exception {
  @override
  String toString() => 'The outbox is locked; unlock it with the app PIN';
}

class EnqueueOptions {
  const EnqueueOptions({required this.offline, this.lastOnlineAt, this.floatAvailable});

  /// True when captured without connectivity: the offline policy applies.
  final bool offline;
  final DateTime? lastOnlineAt;

  /// Last known available float; offline float debits may not exceed it net of other unsynced debits.
  final String? floatAvailable;
}

/// Decision for one attempt's outcome (pure function, see [reconcile]).
sealed class Decision {
  const Decision(this.result);
  final OpResult result;
}

class DecideSynced extends Decision {
  const DecideSynced(super.result, {required this.duplicate});
  final bool duplicate;
}

class DecideRejected extends Decision {
  const DecideRejected(super.result);
}

class DecideVerify extends Decision {
  const DecideVerify(super.result);
}

class DecideRetry extends Decision {
  const DecideRetry(super.result, this.blocker, {this.clearPin = false});
  final Blocker blocker;
  final bool clearPin;
}

Decision reconcile(AttemptOutcome o) {
  switch (o) {
    case AttemptOk(:final status, :final body, :final replayed):
      return DecideSynced(OpResult(httpStatus: status, duplicate: replayed, body: body), duplicate: replayed);
    case AttemptNetwork(:final message):
      return DecideRetry(OpResult(note: message), Blocker.network);
    case AttemptHttp(:final status, :final problem):
      final result = OpResult(httpStatus: status, problem: problem);
      if (status == 401) return DecideRetry(result, Blocker.auth);
      if (status == 408 || status == 425 || status == 429 || status >= 500) {
        return DecideRetry(result, Blocker.network);
      }
      if (status == 409 && problem.code == 'request_in_progress') return DecideRetry(result, Blocker.inProgress);
      if (problem.code == 'pin_required') return DecideRetry(result, Blocker.needsPin);
      if (problem.code == 'invalid_pin') return DecideRetry(result, Blocker.needsPin, clearPin: true);
      if (problem.code == 'idempotency_key_reused') return DecideVerify(result);
      return DecideRejected(result);
  }
}

const _verifier = 'meridian-agent-outbox-v1';

/// Wrong PINs after which the outbox is erased (SEC-23).
const wipeAfter = 10;

/// Encrypted, ordered, idempotent outbox (port of web/agent-app src/lib/outbox/outbox.ts). Each record gets its id and
/// Idempotency-Key once, at capture; every replay re-sends the same body with the same key, so the server applies it
/// at most once.
class Outbox {
  Outbox(this.store, {this.policy = const OfflinePolicy(), DateTime Function()? now, String Function()? uuid,
      this.iterations = pbkdf2Iterations})
      : _now = now ?? DateTime.now,
        _uuid = uuid ?? uuidV4;

  final OutboxStore store;
  OfflinePolicy policy;
  final int iterations;
  final DateTime Function() _now;
  final String Function() _uuid;
  OutboxKey? _key;
  Future<FlushReport>? _flushing;

  bool get unlocked => _key != null;

  Future<bool> isInitialized([String? binding]) async {
    final m = await store.getMeta('key');
    return m != null && (binding == null || m['binding'] == binding);
  }

  /// First use on this device for this agent: creates the salt and the PIN verifier.
  Future<void> setup(String pin, String binding) async {
    if (!RegExp(r'^\d{6}$').hasMatch(pin)) throw ArgumentError('The app PIN must be 6 digits');
    final existing = await store.getMeta('key');
    if (existing != null && (await store.all()).any((r) => r.status == OpStatus.pending)) {
      throw StateError('Unsynced operations exist; they must be synced before the app PIN can be reset');
    }
    // Final records sealed under the previous key would be unreadable: drop them (they are on the server).
    for (final r in await store.all()) {
      await store.delete(r.id);
    }
    final salt = randomBytes(16);
    final key = await deriveKey(pin, salt, binding, iterations: iterations);
    await store.setMeta('key', {
      'salt': b64(salt),
      'verifier': seal(key, _verifier, 'verifier').toJson(),
      'binding': binding,
      'iterations': iterations,
    });
    await store.setMeta('lock', {'failures': 0});
    _key = key;
  }

  /// Unlocks with the app PIN. Five failures lock unlocking for 5 minutes, doubling after each further failure;
  /// [wipeAfter] failures erase the queue and its key material.
  Future<bool> unlock(String pin) async {
    final m = await store.getMeta('key');
    if (m == null) throw StateError('The outbox has not been set up on this device');
    final lock = await store.getMeta('lock') ?? {'failures': 0};
    final lockedUntil = lock['lockedUntil'] == null ? null : DateTime.parse(lock['lockedUntil'] as String);
    if (lockedUntil != null && lockedUntil.isAfter(_now())) {
      throw OfflineLimitError(LimitCode.locked, 'Too many wrong PINs; try again after $lockedUntil');
    }
    try {
      final key = await deriveKey(pin, unb64(m['salt'] as String), m['binding'] as String,
          iterations: (m['iterations'] as num).toInt());
      final v = open<String>(key, Sealed.fromJson(Map<String, dynamic>.from(m['verifier'] as Map)), 'verifier');
      if (v != _verifier) throw StateError('bad verifier');
      _key = key;
      await store.setMeta('lock', {'failures': 0});
      return true;
    } catch (_) {
      final failures = (lock['failures'] as num? ?? 0).toInt() + 1;
      if (failures >= wipeAfter) {
        for (final r in await store.all()) {
          await store.delete(r.id);
        }
        await store.setMeta('key', null);
        await store.setMeta('lock', {'failures': 0});
        throw OfflineLimitError(LimitCode.locked, 'Too many wrong PINs: the offline queue on this device was erased');
      }
      final next = <String, dynamic>{'failures': failures};
      if (failures >= 5) {
        final minutes = 5 * (1 << (failures - 5));
        next['lockedUntil'] = _now().add(Duration(minutes: minutes)).toUtc().toIso8601String();
      }
      await store.setMeta('lock', next);
      return false;
    }
  }

  void lock() => _key = null;

  OutboxKey _requireKey() => _key ?? (throw OutboxLockedError());

  Future<List<OutboxRecord>> all() => store.all();

  OutboxView decrypt(OutboxRecord r) {
    final key = _requireKey();
    final payload = OpPayload.fromJson(open<Map<String, dynamic>>(key, r.payload, r.id));
    final result =
        r.result == null ? null : OpResult.fromJson(open<Map<String, dynamic>>(key, r.result!, '${r.id}:result'));
    return OutboxView(r, payload, result);
  }

  Future<List<OutboxView>> list() async {
    _requireKey();
    return (await store.all()).map(decrypt).toList();
  }

  /// Count and totals of unsynced records.
  Future<({int count, String total, String floatDebits})> pending() async {
    final views = (await list()).where((v) => v.record.status == OpStatus.pending);
    var total = BigInt.zero;
    var debits = BigInt.zero;
    for (final v in views) {
      total += Money.toMinor(v.payload.amount);
      if (floatDebits.contains(v.record.kind)) debits += Money.toMinor(v.payload.amount);
    }
    return (count: views.length, total: Money.fromMinor(total), floatDebits: Money.fromMinor(debits));
  }

  /// Validates an operation against the offline policy; throws [OfflineLimitError] when it must be refused.
  Future<void> checkPolicy(OpKind kind, String? amount, EnqueueOptions opts) async {
    if (!opts.offline) return;
    final p = policy;
    final last = opts.lastOnlineAt;
    if (last == null || _now().difference(last) > Duration(hours: p.maxOfflineHours)) {
      throw OfflineLimitError(LimitCode.offlineTooLong,
          'Offline capture is only allowed within ${p.maxOfflineHours} h of the last successful connection');
    }
    final pend = await pending();
    if (pend.count + 1 > p.maxCount) {
      throw OfflineLimitError(LimitCode.countOverLimit, 'At most ${p.maxCount} operations may wait for sync');
    }
    if (amount != null) {
      if (Money.compare(amount, p.maxAmountPerOp) > 0) {
        throw OfflineLimitError(LimitCode.amountOverLimit, 'Offline operations are limited to ${p.maxAmountPerOp} each');
      }
      if (Money.toMinor(pend.total) + Money.toMinor(amount) > Money.toMinor(p.maxTotalAmount)) {
        throw OfflineLimitError(
            LimitCode.totalOverLimit, 'Unsynced operations may not exceed ${p.maxTotalAmount} in total');
      }
      if (floatDebits.contains(kind) &&
          opts.floatAvailable != null &&
          Money.toMinor(pend.floatDebits) + Money.toMinor(amount) > Money.toMinor(opts.floatAvailable)) {
        throw OfflineLimitError(
            LimitCode.floatInsufficient, 'Not enough float (last known balance less unsynced operations)');
      }
    }
  }

  /// Captures an operation: allocates order, id and Idempotency-Key, encrypts the payload and persists it.
  Future<OutboxRecord> enqueue(OpKind kind, OpPayload payload, EnqueueOptions opts) async {
    final key = _requireKey();
    await checkPolicy(kind, payload.amount, opts);
    if (payload.request.containsKey('pin')) throw ArgumentError('PINs must never be stored in the outbox');
    final id = _uuid();
    final seq = await store.nextSeq();
    final ts = _now();
    final rec = OutboxRecord(
      id: id,
      seq: seq,
      kind: kind,
      status: OpStatus.pending,
      idempotencyKey: 'agentapp-$id',
      createdAt: ts,
      updatedAt: ts,
      createdOffline: opts.offline,
      payload: seal(key, payload.toJson(), id),
    );
    await store.put(rec);
    return rec;
  }

  /// Replays PENDING records strictly in capture order. A record that cannot be decided now (no network, expired
  /// session, PIN needed, still processing) stops the run so later records never overtake it. Runs never overlap.
  Future<FlushReport> flush(Executor executor, ExecContext ctx, {String? only}) {
    final previous = _flushing ?? Future<FlushReport>.value(FlushReport());
    final next = previous.catchError((Object _) => FlushReport()).then((_) => _run(executor, ctx, only));
    _flushing = next;
    next.whenComplete(() {
      if (identical(_flushing, next)) _flushing = null;
    }).ignore();
    return next;
  }

  Future<FlushReport> _run(Executor executor, ExecContext ctx, String? only) async {
    final key = _requireKey();
    final report = FlushReport();
    final queue = (await store.all()).where((r) => r.status == OpStatus.pending).toList();
    var stopped = false;
    for (var i = 0; i < queue.length; i++) {
      final rec = queue[i];
      if (stopped) {
        report.remaining++;
        continue;
      }
      final payload = OpPayload.fromJson(open<Map<String, dynamic>>(key, rec.payload, rec.id));
      rec.attempts += 1;
      rec.lastAttemptAt = _now();
      report.attempted++;
      AttemptOutcome outcome;
      try {
        outcome = await executor.execute(rec, payload, ctx);
      } catch (e) {
        outcome = AttemptNetwork(e.toString());
      }
      var d = reconcile(outcome);
      if (d is DecideVerify) {
        Object? found;
        var failed = false;
        try {
          found = await executor.lookup(rec, payload);
        } catch (_) {
          failed = true;
        }
        if (failed) {
          d = DecideRetry(OpResult(httpStatus: d.result.httpStatus, problem: d.result.problem, note: 'lookup failed'),
              Blocker.network);
        } else if (found != null) {
          d = DecideSynced(OpResult(duplicate: true, body: found, note: 'matched by Idempotency-Key'), duplicate: true);
        }
      }
      switch (d) {
        case DecideSynced(:final duplicate):
          rec.status = OpStatus.synced;
          rec.blocker = null;
          report.synced++;
          if (duplicate) report.duplicates++;
        case DecideRejected():
          rec.status = OpStatus.rejected;
          rec.blocker = null;
          report.rejected++;
        case DecideVerify():
          rec.status = OpStatus.needsReview;
          rec.blocker = null;
          report.review++;
        case DecideRetry(:final blocker, :final clearPin):
          rec.blocker = blocker;
          if (clearPin) ctx.agentPin = null;
          report.blocked = blocker;
          report.remaining++;
          stopped = true;
      }
      rec.updatedAt = _now();
      rec.result = seal(key, d.result.toJson(), '${rec.id}:result');
      await store.put(rec);
      if (only != null && rec.id == only && !stopped) {
        report.remaining += queue.length - i - 1;
        break;
      }
    }
    return report;
  }

  /// Removes a final record. PENDING records cannot be removed.
  Future<void> remove(String id) async {
    final r = await store.get(id);
    if (r == null) return;
    if (r.status == OpStatus.pending) throw StateError('Unsynced operations cannot be deleted');
    await store.delete(id);
  }

  /// Drops synced records older than [maxAge] (default 7 days).
  Future<int> purgeSynced([Duration maxAge = const Duration(days: 7)]) async {
    final cutoff = _now().subtract(maxAge);
    var n = 0;
    for (final r in await store.all()) {
      if (r.status == OpStatus.synced && r.updatedAt.isBefore(cutoff)) {
        await store.delete(r.id);
        n++;
      }
    }
    return n;
  }
}
