import 'package:flutter/material.dart';

import 'src/app_services.dart';
import 'src/config.dart';
import 'src/ui/app.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final services = AppServices.create(AppConfig.fromEnvironment());
  await services.init();
  runApp(MeridianCustomerApp(services: services));
}
