import 'dart:io';

import 'package:customer_app/src/api/customer_api.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yaml/yaml.dart';

/// Every (method, path) the typed client calls must exist in the customer channel contract, so a contract change
/// that removes or renames an operation fails here instead of at runtime.
void main() {
  final spec = File('../../generated/openapi/services/customer-channel-service.openapi.yaml');

  test('client operations exist in customer-channel-service.openapi.yaml', () {
    expect(spec.existsSync(), isTrue, reason: 'run from mobile/customer_app inside the monorepo');
    final doc = loadYaml(spec.readAsStringSync()) as YamlMap;
    final paths = doc['paths'] as YamlMap;
    final missing = <String>[];
    for (final (method, path) in CustomerApi.operations) {
      final ops = paths[path] as YamlMap?;
      if (ops == null || !ops.containsKey(method.toLowerCase())) missing.add('$method $path');
    }
    expect(missing, isEmpty);
  });

  test('every write the client uses declares an Idempotency-Key header', () {
    final doc = loadYaml(spec.readAsStringSync()) as YamlMap;
    final paths = doc['paths'] as YamlMap;
    for (final (method, path) in CustomerApi.operations.where((o) => o.$1 != 'GET')) {
      final op = (paths[path] as YamlMap)[method.toLowerCase()] as YamlMap;
      final params = (op['parameters'] as YamlList? ?? YamlList()).map((p) => p.toString()).join(' ');
      expect(params, contains('IdempotencyKey'), reason: '$method $path');
    }
  });
}
