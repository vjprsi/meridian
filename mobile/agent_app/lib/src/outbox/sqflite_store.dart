import 'dart:convert';

import 'package:path/path.dart' as p;
import 'package:sqflite/sqflite.dart';

import 'store.dart';
import 'types.dart';

/// SQLite store on Android / iOS (app-private database; payloads are AES-GCM sealed before they get here).
class SqfliteOutboxStore implements OutboxStore {
  SqfliteOutboxStore._(this._db);
  final Database _db;

  static Future<SqfliteOutboxStore> open([String name = 'meridian_agent_outbox.db']) async {
    final db = await openDatabase(
      p.join(await getDatabasesPath(), name),
      version: 1,
      onCreate: (db, _) async {
        await db.execute('CREATE TABLE outbox (id TEXT PRIMARY KEY, seq INTEGER NOT NULL UNIQUE, json TEXT NOT NULL)');
        await db.execute('CREATE TABLE meta (k TEXT PRIMARY KEY, json TEXT NOT NULL)');
      },
    );
    return SqfliteOutboxStore._(db);
  }

  @override
  Future<List<OutboxRecord>> all() async => (await _db.query('outbox', orderBy: 'seq'))
      .map((r) => OutboxRecord.fromJson(jsonDecode(r['json'] as String) as Map<String, dynamic>))
      .toList();

  @override
  Future<OutboxRecord?> get(String id) async {
    final r = await _db.query('outbox', where: 'id = ?', whereArgs: [id]);
    return r.isEmpty ? null : OutboxRecord.fromJson(jsonDecode(r.first['json'] as String) as Map<String, dynamic>);
  }

  @override
  Future<void> put(OutboxRecord rec) => _db.insert('outbox', {'id': rec.id, 'seq': rec.seq, 'json': jsonEncode(rec.toJson())},
      conflictAlgorithm: ConflictAlgorithm.replace);

  @override
  Future<void> delete(String id) => _db.delete('outbox', where: 'id = ?', whereArgs: [id]);

  @override
  Future<int> nextSeq() => _db.transaction((tx) async {
        final row = await tx.query('meta', where: 'k = ?', whereArgs: ['seq']);
        final next = (row.isEmpty ? 0 : (jsonDecode(row.first['json'] as String) as num).toInt()) + 1;
        await tx.insert('meta', {'k': 'seq', 'json': '$next'}, conflictAlgorithm: ConflictAlgorithm.replace);
        return next;
      });

  @override
  Future<Map<String, dynamic>?> getMeta(String key) async {
    final r = await _db.query('meta', where: 'k = ?', whereArgs: [key]);
    return r.isEmpty ? null : jsonDecode(r.first['json'] as String) as Map<String, dynamic>?;
  }

  @override
  Future<void> setMeta(String key, Map<String, dynamic>? value) async {
    if (value == null) {
      await _db.delete('meta', where: 'k = ?', whereArgs: [key]);
    } else {
      await _db.insert('meta', {'k': key, 'json': jsonEncode(value)}, conflictAlgorithm: ConflictAlgorithm.replace);
    }
  }
}

Future<OutboxStore> openPlatformStore() => SqfliteOutboxStore.open();
