import 'types.dart';

/// Persistence of outbox records and metadata. Records come back ordered by [OutboxRecord.seq].
abstract class OutboxStore {
  Future<List<OutboxRecord>> all();
  Future<OutboxRecord?> get(String id);
  Future<void> put(OutboxRecord rec);
  Future<void> delete(String id);

  /// Next capture sequence number (strictly increasing, never reused on this device).
  Future<int> nextSeq();
  Future<Map<String, dynamic>?> getMeta(String key);
  Future<void> setMeta(String key, Map<String, dynamic>? value);
}

/// In-memory store (tests; web smoke build, where nothing survives a reload).
class MemoryOutboxStore implements OutboxStore {
  final _records = <String, Map<String, dynamic>>{};
  final _meta = <String, Map<String, dynamic>>{};
  int _seq = 0;

  @override
  Future<List<OutboxRecord>> all() async =>
      (_records.values.map(OutboxRecord.fromJson).toList()..sort((a, b) => a.seq.compareTo(b.seq)));

  @override
  Future<OutboxRecord?> get(String id) async => _records[id] == null ? null : OutboxRecord.fromJson(_records[id]!);

  @override
  Future<void> put(OutboxRecord rec) async => _records[rec.id] = rec.toJson();

  @override
  Future<void> delete(String id) async => _records.remove(id);

  @override
  Future<int> nextSeq() async => ++_seq;

  @override
  Future<Map<String, dynamic>?> getMeta(String key) async => _meta[key];

  @override
  Future<void> setMeta(String key, Map<String, dynamic>? value) async {
    if (value == null) {
      _meta.remove(key);
    } else {
      _meta[key] = value;
    }
  }

  /// Test hook: raw stored JSON (to prove nothing sensitive is in clear).
  Map<String, Map<String, dynamic>> get raw => _records;
}
