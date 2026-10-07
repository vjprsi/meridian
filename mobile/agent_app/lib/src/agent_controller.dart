import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;

import 'api/agent_api.dart';
import 'api/executor.dart';
import 'auth/auth.dart';
import 'config.dart';
import 'core/ids.dart';
import 'crypto/device_key.dart';
import 'outbox/outbox.dart';
import 'outbox/types.dart';
import 'security/secure_store.dart';

/// What this installation knows about its agent (device profile; not secret, kept in secure storage).
class AgentProfile {
  const AgentProfile({required this.tenantId, required this.agentId, required this.agentName, required this.currency,
      required this.outletId, required this.deviceId});
  factory AgentProfile.fromJson(Map<String, dynamic> j) => AgentProfile(
      tenantId: j['tenantId'] as String,
      agentId: j['agentId'] as String,
      agentName: j['agentName'] as String? ?? '',
      currency: j['currency'] as String? ?? '',
      outletId: j['outletId'] as String,
      deviceId: j['deviceId'] as String);
  final String tenantId;
  final String agentId;
  final String agentName;
  final String currency;
  final String outletId;
  final String deviceId;

  /// Outbox key binding: the same PIN on another agent profile yields another key.
  String get binding => '$tenantId:$agentId';

  Map<String, dynamic> toJson() => {
        'tenantId': tenantId, 'agentId': agentId, 'agentName': agentName, 'currency': currency,
        'outletId': outletId, 'deviceId': deviceId,
      };
}

/// Result of capturing one operation.
class CaptureResult {
  const CaptureResult(this.record, this.report, this.view);
  final OutboxRecord record;
  final FlushReport? report;
  final OutboxView view;

  bool get synced => view.record.status == OpStatus.synced;
  bool get queued => view.record.status == OpStatus.pending;
}

/// App state: session, device profile, connectivity, the encrypted outbox and the in-memory agent wallet PIN.
class AgentController extends ChangeNotifier {
  AgentController({
    required this.config,
    required this.authenticator,
    required this.store,
    required this.outbox,
    required this.keys,
    required http.Client httpClient,
    DateTime Function()? now,
  }) : _now = now ?? DateTime.now {
    api = AgentApi(baseUrl: config.apiBaseUrl, httpClient: httpClient, headers: _headers, proof: _proof)
      ..onReachable = _reachable;
    executor = ApiExecutor(api);
  }

  final AppConfig config;
  final AgentAuthenticator authenticator;
  final SecureStore store;
  final Outbox outbox;
  final DeviceKeyStore keys;
  final DateTime Function() _now;
  late final AgentApi api;
  late final Executor executor;

  static const _profileKey = 'meridian.agent.profile';
  static const _lastOnlineKey = 'meridian.agent.last_online';

  AgentSession? session;
  AgentProfile? profile;
  bool online = true;
  DateTime? lastOnlineAt;
  String? floatAvailable;
  bool _needsSetup = true;

  /// Agent wallet PIN for this unlock only (never persisted; cleared on lock and on invalid_pin).
  final ExecContext ctx = ExecContext();

  bool get signedIn => session != null;
  bool get needsSetup => _needsSetup;
  bool get unlocked => outbox.unlocked;

  Future<void> init() async {
    final p = await store.read(_profileKey);
    profile = p == null ? null : AgentProfile.fromJson(jsonDecode(p) as Map<String, dynamic>);
    final l = await store.read(_lastOnlineKey);
    lastOnlineAt = l == null ? null : DateTime.tryParse(l);
    notifyListeners();
  }

  Future<Map<String, String>> _headers() async {
    var s = session;
    if (s == null) return const {};
    if (s.mode == AuthMode.oidc && s.expired(_now()) && s.refreshToken != null) {
      s = session = await authenticator.refresh(s);
    }
    return s.headers();
  }

  Future<String?> _proof(String method, String path, String idempotencyKey) async {
    final p = profile;
    if (p == null || !outbox.unlocked) return null; // the app signs only while unlocked with the app PIN
    try {
      final ts = _now().millisecondsSinceEpoch ~/ 1000;
      final sig = await keys.sign(proofPayload(
          tenant: p.tenantId, agent: p.agentId, device: p.deviceId, method: method, path: path,
          idempotencyKey: idempotencyKey, ts: ts));
      return 'v1.${p.deviceId}.$ts.$sig';
    } catch (_) {
      return null;
    }
  }

  void _reachable() {
    lastOnlineAt = _now();
    store.write(_lastOnlineKey, lastOnlineAt!.toUtc().toIso8601String());
    if (!online) {
      online = true;
      notifyListeners();
    }
  }

  void _unreachable() {
    if (online) {
      online = false;
      notifyListeners();
    }
  }

  Future<void> signIn({DevAgentIdentity? dev}) async {
    session = await authenticator.signIn(dev: dev);
    final p = profile;
    _needsSetup = p == null ||
        p.agentId != session!.agentId ||
        p.tenantId != session!.tenantId ||
        !await outbox.isInitialized(p.binding);
    notifyListeners();
  }

  Future<void> signOut() async {
    final s = session;
    lock();
    session = null;
    notifyListeners();
    if (s != null) await authenticator.signOut(s);
  }

  /// Setup on this device: the agent from /v1/agent/me (never chosen in the app), the chosen outlet, a fresh device
  /// key registered with the channel, and the 6-digit app PIN protecting the outbox.
  Future<void> completeSetup({required String outletId, required String pin}) async {
    final agent = await api.me();
    final key = await keys.generate();
    final deviceId = await api.registerDevice(
        outletId: outletId, serial: 'mobile-${uuidV4()}', publicKey: key.publicKey, model: defaultTargetPlatform.name);
    final p = AgentProfile(
        tenantId: session!.tenantId, agentId: agent.id, agentName: agent.legalName, currency: agent.currency,
        outletId: outletId, deviceId: deviceId);
    await outbox.setup(pin, p.binding);
    profile = p;
    await store.write(_profileKey, jsonEncode(p.toJson()));
    _needsSetup = false;
    notifyListeners();
    refreshFloat().ignore();
  }

  Future<bool> unlock(String pin) async {
    final ok = await outbox.unlock(pin);
    notifyListeners();
    if (ok) refreshFloat().ignore();
    return ok;
  }

  void lock() {
    outbox.lock();
    ctx.agentPin = null;
    notifyListeners();
  }

  Future<void> refreshFloat() async {
    try {
      floatAvailable = (await api.floatBalance()).available;
      notifyListeners();
    } on ApiException catch (e) {
      if (e.network) _unreachable();
    }
  }

  /// Re-checks connectivity with a light read.
  Future<bool> probe() async {
    try {
      await api.billers();
      return true;
    } on ApiException catch (e) {
      if (e.network) _unreachable();
      return !e.network;
    }
  }

  /// Captures an operation into the outbox. Online: sent at once (only this record, keeping order behind any earlier
  /// pending ones). Offline: queued under the offline policy and replayed by [sync].
  Future<CaptureResult> capture(OpKind kind, OpPayload payload) async {
    final rec = await outbox.enqueue(
        kind, payload, EnqueueOptions(offline: !online, lastOnlineAt: lastOnlineAt, floatAvailable: floatAvailable));
    FlushReport? report;
    if (online) {
      report = await outbox.flush(executor, ctx);
      if (report.blocked == Blocker.network) _unreachable();
    }
    final stored = (await outbox.store.get(rec.id))!;
    notifyListeners();
    if (report != null && report.synced > 0) refreshFloat().ignore();
    return CaptureResult(rec, report, outbox.decrypt(stored));
  }

  /// Replays everything pending, in capture order.
  Future<FlushReport> sync() async {
    final r = await outbox.flush(executor, ctx);
    if (r.blocked == Blocker.network) _unreachable();
    notifyListeners();
    if (r.synced > 0) refreshFloat().ignore();
    return r;
  }

  void setAgentPin(String? pin) => ctx.agentPin = pin;
}
