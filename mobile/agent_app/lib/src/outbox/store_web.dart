import 'store.dart';

/// Web smoke build: no SQLite; the queue lives in memory only (documented gap).
Future<OutboxStore> openPlatformStore() async => MemoryOutboxStore();
