// ignore: unused_import
import 'package:intl/intl.dart' as intl;

import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for English (`en`).
class AppLocalizationsEn extends AppLocalizations {
  AppLocalizationsEn([String locale = 'en']) : super(locale);

  @override
  String get appTitle => 'Meridian Agent';

  @override
  String get signIn => 'Sign in';

  @override
  String get signInWithBank => 'Sign in with Meridian';

  @override
  String get devModeHint => 'Dev mode: local services in dev security mode';

  @override
  String get tenant => 'Tenant';

  @override
  String get agentId => 'Agent ID';

  @override
  String get username => 'User name';

  @override
  String get userId => 'User ID';

  @override
  String get setupTitle => 'Set up this device';

  @override
  String get chooseOutlet => 'Outlet';

  @override
  String get appPin => 'App PIN';

  @override
  String get appPinHint => '6 digits; protects the work saved on this device';

  @override
  String get confirmPin => 'Repeat the app PIN';

  @override
  String get pinMismatch => 'The PINs do not match';

  @override
  String get pinInvalid => 'Enter 6 digits';

  @override
  String get finishSetup => 'Register device';

  @override
  String get unlockTitle => 'Unlock';

  @override
  String get unlock => 'Unlock';

  @override
  String get wrongPin => 'Wrong PIN';

  @override
  String get lock => 'Lock';

  @override
  String get signOut => 'Sign out';

  @override
  String get online => 'Online';

  @override
  String get offline => 'Offline';

  @override
  String pendingCount(int count) {
    return '$count waiting to sync';
  }

  @override
  String get float => 'Float';

  @override
  String get cashIn => 'Cash-in';

  @override
  String get cashOut => 'Cash-out';

  @override
  String get billPay => 'Bill payment';

  @override
  String get floatRequest => 'Float request';

  @override
  String get history => 'History';

  @override
  String get sync => 'Sync';

  @override
  String get customerPhone => 'Customer phone number';

  @override
  String get amount => 'Amount';

  @override
  String get lookup => 'Find customer';

  @override
  String customerFound(String name) {
    return 'Customer: $name';
  }

  @override
  String get customerNotFound => 'No wallet found for this number';

  @override
  String get confirm => 'Confirm';

  @override
  String get cancel => 'Cancel';

  @override
  String get completed => 'Completed';

  @override
  String get queued => 'Saved on this device; it will be sent when online';

  @override
  String rejected(String reason) {
    return 'Refused: $reason';
  }

  @override
  String get agentPin => 'Agent wallet PIN';

  @override
  String get agentPinHint => 'Kept in memory for this session only';

  @override
  String get customerPin => 'Customer PIN';

  @override
  String get otp => 'One-time code';

  @override
  String get cashOutStarted =>
      'Ask the customer to confirm with their PIN or code';

  @override
  String get biller => 'Biller';

  @override
  String get accountReference => 'Account reference';

  @override
  String get customerName => 'Customer name';

  @override
  String get requestType => 'Request type';

  @override
  String get bankReference => 'Bank reference';

  @override
  String get syncNow => 'Sync now';

  @override
  String syncResult(int synced, int remaining) {
    return '$synced sent, $remaining still waiting';
  }

  @override
  String get nothingPending => 'Nothing waiting to sync';

  @override
  String get remove => 'Remove';

  @override
  String get statusPending => 'Waiting';

  @override
  String get statusSynced => 'Sent';

  @override
  String get statusRejected => 'Refused';

  @override
  String get statusNeedsReview => 'Needs review';

  @override
  String get required => 'Required';

  @override
  String get invalidAmount => 'Enter a valid amount';

  @override
  String get retry => 'Retry';

  @override
  String get errorGeneric => 'Something went wrong';

  @override
  String get empty => 'Nothing here yet';

  @override
  String get onDevice => 'On this device';

  @override
  String get onServer => 'Completed transactions';

  @override
  String get offlineNotAllowed => 'Cash-out needs a connection';

  @override
  String blocked(String reason) {
    return 'Stopped: $reason';
  }
}
