import 'dart:convert';

import 'package:agent_app/src/crypto/outbox_crypto.dart';
import 'package:agent_app/src/outbox/outbox.dart';
import 'package:agent_app/src/outbox/store.dart';
import 'package:agent_app/src/outbox/types.dart';
import 'package:flutter_test/flutter_test.dart';

/// Scripted executor: answers per record from a queue of outcomes, records the order and keys it saw.
class FakeExecutor implements Executor {
  FakeExecutor(this.outcomes, {this.lookupResult, this.lookupFails = false});
  final List<AttemptOutcome> outcomes;
  final Object? lookupResult;
  final bool lookupFails;
  final seen = <(String key, Map<String, dynamic> body, String? pin)>[];

  @override
  Future<AttemptOutcome> execute(OutboxRecord rec, OpPayload payload, ExecContext ctx) async {
    seen.add((rec.idempotencyKey, payload.request, ctx.agentPin));
    return outcomes.isEmpty ? const AttemptOk(200, {}) : outcomes.removeAt(0);
  }

  @override
  Future<Object?> lookup(OutboxRecord rec, OpPayload payload) async {
    if (lookupFails) throw StateError('lookup failed');
    return lookupResult;
  }
}

void main() {
  late MemoryOutboxStore store;
  late Outbox outbox;
  var clock = DateTime.utc(2026, 10, 7, 9);
  const binding = '0199a000-0000-7000-8000-00000000000a:agent-1';

  OpPayload cashIn(String amount, [String msisdn = '254700000001']) => OpPayload(
      request: {'payee_msisdn': msisdn, 'amount': amount}, amount: amount, currency: 'KES', display: {'customer': msisdn});

  EnqueueOptions offline({String? float = '100000'}) =>
      EnqueueOptions(offline: true, lastOnlineAt: clock.subtract(const Duration(hours: 1)), floatAvailable: float);

  setUp(() async {
    clock = DateTime.utc(2026, 10, 7, 9);
    store = MemoryOutboxStore();
    outbox = Outbox(store, now: () => clock, iterations: 1000);
    await outbox.setup('123456', binding);
  });

  group('encryption at rest', () {
    test('payloads are AES-GCM sealed: nothing typed is stored in clear', () async {
      await outbox.enqueue(OpKind.cashIn, cashIn('250.75', '254711222333'), offline());
      final raw = jsonEncode(store.raw);
      expect(raw, isNot(contains('254711222333')));
      expect(raw, isNot(contains('250.75')));
      final view = (await outbox.list()).single;
      expect(view.payload.request['payee_msisdn'], '254711222333');
    });

    test('a ciphertext moved onto another record fails (record id is the AAD)', () async {
      final a = await outbox.enqueue(OpKind.cashIn, cashIn('1'), offline());
      final b = await outbox.enqueue(OpKind.cashIn, cashIn('2'), offline());
      final key = await deriveKey('123456', unb64((await store.getMeta('key'))!['salt'] as String), binding, iterations: 1000);
      expect(() => open<Map<String, dynamic>>(key, a.payload, b.id), throwsA(anything));
      expect(open<Map<String, dynamic>>(key, a.payload, a.id)['amount'], '1');
    });

    test('a different binding (another agent) derives another key', () async {
      final salt = randomBytes(16);
      final k1 = await deriveKey('123456', salt, 'tenant:agent-1', iterations: 1000);
      final k2 = await deriveKey('123456', salt, 'tenant:agent-2', iterations: 1000);
      final sealed = seal(k1, {'x': 1}, 'r1');
      expect(() => open<Map<String, dynamic>>(k2, sealed, 'r1'), throwsA(anything));
    });

    test('locked outbox refuses to enqueue or read', () async {
      outbox.lock();
      expect(() => outbox.enqueue(OpKind.cashIn, cashIn('1'), offline()), throwsA(isA<OutboxLockedError>()));
      expect(() => outbox.list(), throwsA(isA<OutboxLockedError>()));
    });
  });

  group('PIN', () {
    test('right PIN unlocks, wrong PIN does not; 5 failures lock for 5 minutes', () async {
      outbox.lock();
      expect(await outbox.unlock('000000'), isFalse);
      expect(await outbox.unlock('123456'), isTrue);
      outbox.lock();
      for (var i = 0; i < 5; i++) {
        expect(await outbox.unlock('999999'), isFalse);
      }
      await expectLater(outbox.unlock('123456'), throwsA(isA<OfflineLimitError>()));
      clock = clock.add(const Duration(minutes: 6));
      expect(await outbox.unlock('123456'), isTrue);
    });

    test('10 wrong PINs erase the queue and the key material', () async {
      await outbox.enqueue(OpKind.cashIn, cashIn('1'), offline());
      outbox.lock();
      for (var i = 0; i < wipeAfter - 1; i++) {
        clock = clock.add(const Duration(days: 1)); // past any lock-out
        expect(await outbox.unlock('999999'), isFalse);
      }
      clock = clock.add(const Duration(days: 1));
      await expectLater(outbox.unlock('999999'), throwsA(isA<OfflineLimitError>()));
      expect(await store.all(), isEmpty);
      expect(await outbox.isInitialized(), isFalse);
    });

    test('the PIN cannot be reset while work is unsynced; PINs never go into the outbox', () async {
      await outbox.enqueue(OpKind.cashIn, cashIn('1'), offline());
      expect(() => outbox.setup('654321', binding), throwsA(isA<StateError>()));
      expect(
          () => outbox.enqueue(OpKind.cashIn,
              const OpPayload(request: {'payee_msisdn': '1', 'amount': '1', 'pin': '1234'}, amount: '1'), offline()),
          throwsA(isA<ArgumentError>()));
    });
  });

  group('ordering and idempotency', () {
    test('each record gets one Idempotency-Key at capture, reused on every replay', () async {
      final rec = await outbox.enqueue(OpKind.cashIn, cashIn('10'), offline());
      expect(rec.idempotencyKey, 'agentapp-${rec.id}');
      final ex = FakeExecutor([const AttemptNetwork('offline'), const AttemptNetwork('offline'), const AttemptOk(200, {'id': 't1'})]);
      await outbox.flush(ex, ExecContext());
      await outbox.flush(ex, ExecContext());
      final r = await outbox.flush(ex, ExecContext());
      expect(r.synced, 1);
      expect(ex.seen.map((s) => s.$1).toSet(), {rec.idempotencyKey});
      expect(ex.seen.map((s) => jsonEncode(s.$2)).toSet(), hasLength(1), reason: 'same body every time');
      expect((await store.get(rec.id))!.attempts, 3);
    });

    test('replays strictly in capture order and stops at the first undecided record', () async {
      final a = await outbox.enqueue(OpKind.cashIn, cashIn('1'), offline());
      final b = await outbox.enqueue(OpKind.billPayment, cashIn('2'), offline());
      final c = await outbox.enqueue(OpKind.floatRequest, cashIn('3'), offline());
      final ex = FakeExecutor([const AttemptOk(200, {}), const AttemptNetwork('lost')]);
      final r = await outbox.flush(ex, ExecContext());
      expect(ex.seen.map((s) => s.$1), [a.idempotencyKey, b.idempotencyKey], reason: 'c never overtakes b');
      expect(r.synced, 1);
      expect(r.remaining, 2);
      expect(r.blocked, Blocker.network);
      expect((await store.get(b.id))!.blocker, Blocker.network);

      final ex2 = FakeExecutor([]);
      await outbox.flush(ex2, ExecContext());
      expect(ex2.seen.map((s) => s.$1), [b.idempotencyKey, c.idempotencyKey]);
      expect((await store.all()).every((r) => r.status == OpStatus.synced), isTrue);
    });

    test('a refused record is final and does not block the rest', () async {
      final a = await outbox.enqueue(OpKind.cashIn, cashIn('1'), offline());
      await outbox.enqueue(OpKind.cashIn, cashIn('2'), offline());
      final r = await outbox.flush(
          FakeExecutor([const AttemptHttp(422, Problem(status: 422, code: 'wallet_not_found')), const AttemptOk(200, {})]),
          ExecContext());
      expect(r.rejected, 1);
      expect(r.synced, 1);
      final v = outbox.decrypt((await store.get(a.id))!);
      expect(v.record.status, OpStatus.rejected);
      expect(v.result!.problem!.code, 'wallet_not_found');
    });

    test('an idempotent replay counts as synced duplicate', () async {
      await outbox.enqueue(OpKind.cashIn, cashIn('1'), offline());
      final r = await outbox.flush(FakeExecutor([const AttemptOk(200, {}, replayed: true)]), ExecContext());
      expect(r.duplicates, 1);
    });

    test('idempotency_key_reused: matched by lookup -> synced; nothing found -> needs review; lookup down -> retry',
        () async {
      const reused = AttemptHttp(422, Problem(status: 422, code: 'idempotency_key_reused'));
      final a = await outbox.enqueue(OpKind.cashIn, cashIn('1'), offline());
      await outbox.flush(FakeExecutor([reused], lookupResult: {'id': 'txn-1'}), ExecContext());
      expect((await store.get(a.id))!.status, OpStatus.synced);

      final b = await outbox.enqueue(OpKind.cashIn, cashIn('2'), offline());
      await outbox.flush(FakeExecutor([reused], lookupFails: true), ExecContext());
      expect((await store.get(b.id))!.status, OpStatus.pending);
      await outbox.flush(FakeExecutor([reused]), ExecContext());
      expect((await store.get(b.id))!.status, OpStatus.needsReview);
    });

    test('pin_required waits for the agent PIN; invalid_pin clears it from memory', () async {
      await outbox.enqueue(OpKind.cashIn, cashIn('1'), offline());
      final ctx = ExecContext(agentPin: '9999');
      final r = await outbox.flush(
          FakeExecutor([const AttemptHttp(422, Problem(status: 422, code: 'invalid_pin'))]), ctx);
      expect(r.blocked, Blocker.needsPin);
      expect(ctx.agentPin, isNull);
    });

    test('concurrent flushes never overlap (no double send)', () async {
      await outbox.enqueue(OpKind.cashIn, cashIn('1'), offline());
      final ex = FakeExecutor([]);
      await Future.wait([outbox.flush(ex, ExecContext()), outbox.flush(ex, ExecContext())]);
      expect(ex.seen, hasLength(1));
    });

    test('final records can be removed; pending ones cannot; purge drops old synced', () async {
      final a = await outbox.enqueue(OpKind.cashIn, cashIn('1'), offline());
      expect(() => outbox.remove(a.id), throwsA(isA<StateError>()));
      await outbox.flush(FakeExecutor([]), ExecContext());
      clock = clock.add(const Duration(days: 8));
      expect(await outbox.purgeSynced(), 1);
    });
  });

  group('offline limits', () {
    test('per operation, total, count, float and offline age', () async {
      outbox.policy = const OfflinePolicy(maxAmountPerOp: '100', maxTotalAmount: '150', maxCount: 2, maxOfflineHours: 12);
      Future<LimitCode?> code(Future<void> Function() f) async {
        try {
          await f();
          return null;
        } on OfflineLimitError catch (e) {
          return e.code;
        }
      }

      expect(await code(() => outbox.enqueue(OpKind.cashIn, cashIn('101'), offline())), LimitCode.amountOverLimit);
      await outbox.enqueue(OpKind.cashIn, cashIn('100'), offline());
      expect(await code(() => outbox.enqueue(OpKind.cashIn, cashIn('60'), offline())), LimitCode.totalOverLimit);
      expect(await code(() => outbox.enqueue(OpKind.cashIn, cashIn('10'), offline(float: '105'))),
          LimitCode.floatInsufficient);
      // A float request does not spend float.
      expect(await code(() => outbox.enqueue(OpKind.floatRequest, cashIn('10'), offline(float: '105'))), isNull);
      expect(await code(() => outbox.enqueue(OpKind.cashIn, cashIn('1'), offline())), LimitCode.countOverLimit);
      expect(
          await code(() => outbox.enqueue(OpKind.cashIn, cashIn('1'),
              EnqueueOptions(offline: true, lastOnlineAt: clock.subtract(const Duration(hours: 13))))),
          LimitCode.offlineTooLong);
      // Online capture is not limited by the offline policy.
      expect(await code(() => outbox.enqueue(OpKind.cashIn, cashIn('500'), const EnqueueOptions(offline: false))), isNull);
    });
  });

  test('reconcile maps outcomes', () {
    expect(reconcile(const AttemptHttp(401, Problem())), isA<DecideRetry>().having((d) => d.blocker, 'b', Blocker.auth));
    expect(reconcile(const AttemptHttp(503, Problem())), isA<DecideRetry>().having((d) => d.blocker, 'b', Blocker.network));
    expect(reconcile(const AttemptHttp(409, Problem(code: 'request_in_progress'))),
        isA<DecideRetry>().having((d) => d.blocker, 'b', Blocker.inProgress));
    expect(reconcile(const AttemptHttp(422, Problem(code: 'limit_exceeded'))), isA<DecideRejected>());
    expect(reconcile(const AttemptHttp(422, Problem(code: 'idempotency_key_reused'))), isA<DecideVerify>());
  });
}
