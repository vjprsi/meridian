import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;

import 'src/agent_controller.dart';
import 'src/auth/auth.dart';
import 'src/config.dart';
import 'src/crypto/device_key.dart';
import 'src/outbox/outbox.dart';
import 'src/outbox/platform_store.dart';
import 'src/security/secure_store.dart';
import 'src/ui/app.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final config = AppConfig.fromEnvironment();
  final SecureStore store = kIsWeb ? MemorySecureStore() : PlatformSecureStore();
  final controller = AgentController(
    config: config,
    authenticator: config.authMode == AuthMode.oidc ? AppAuthAgentAuthenticator(config) : DevAgentAuthenticator(),
    store: store,
    outbox: Outbox(await openPlatformStore(), policy: config.policy),
    keys: await deviceKeyStore(store),
    httpClient: http.Client(),
  );
  await controller.init();
  runApp(MeridianAgentApp(controller: controller));
}
