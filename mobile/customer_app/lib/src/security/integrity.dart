/// Result of a device integrity check.
class IntegrityVerdict {
  const IntegrityVerdict({required this.compromised, this.reasons = const []});

  static const trusted = IntegrityVerdict(compromised: false);

  /// Rooted / jailbroken, debugger or hooking framework attached, emulator, tampered app signature, ...
  final bool compromised;
  final List<String> reasons;
}

/// Jailbreak / root detection hook. The default reports a trusted device; a production build plugs in a real check
/// (e.g. Play Integrity / App Attest verdicts verified server-side, or a RASP SDK) without changing the app.
abstract class DeviceIntegrityCheck {
  Future<IntegrityVerdict> check();
}

class NoopIntegrityCheck implements DeviceIntegrityCheck {
  const NoopIntegrityCheck();

  @override
  Future<IntegrityVerdict> check() async => IntegrityVerdict.trusted;
}

/// Fixed verdict (tests).
class StaticIntegrityCheck implements DeviceIntegrityCheck {
  const StaticIntegrityCheck(this.verdict);
  final IntegrityVerdict verdict;

  @override
  Future<IntegrityVerdict> check() async => verdict;
}
