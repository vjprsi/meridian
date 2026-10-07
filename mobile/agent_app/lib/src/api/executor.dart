import '../outbox/types.dart';
import 'agent_api.dart';

/// Executes outbox records against the agent channel. Money operations carry an X-Agent-Device-Proof (added by
/// [AgentApi]) so a replay later than the sign-in's step-up window still passes. The agent wallet PIN, when the
/// server asks for it, is added from memory and never stored.
class ApiExecutor implements Executor {
  ApiExecutor(this.api);
  final AgentApi api;

  static String pathOf(OpKind k) => switch (k) {
        OpKind.cashIn => '/v1/agent/cash-in',
        OpKind.billPayment => '/v1/agent/bill-payments',
        OpKind.floatRequest => '/v1/agent/float-requests',
      };

  static String lookupPathOf(OpKind k) => switch (k) {
        OpKind.cashIn => '/v1/agent/transactions',
        OpKind.billPayment => '/v1/agent/bill-payments',
        OpKind.floatRequest => '/v1/agent/float-requests',
      };

  @override
  Future<AttemptOutcome> execute(OutboxRecord rec, OpPayload payload, ExecContext ctx) async {
    final body = {...payload.request};
    if (rec.kind != OpKind.floatRequest && ctx.agentPin != null) body['pin'] = ctx.agentPin;
    try {
      final r = await api.post(pathOf(rec.kind), body, idempotencyKey: rec.idempotencyKey);
      return AttemptOk(r.status, r.body, replayed: r.replayed);
    } on ApiException catch (e) {
      if (e.network) return AttemptNetwork(e.toString());
      return AttemptHttp(e.status ?? 0, e.problem);
    }
  }

  @override
  Future<Object?> lookup(OutboxRecord rec, OpPayload payload) =>
      api.firstByKey(lookupPathOf(rec.kind), rec.idempotencyKey);
}
