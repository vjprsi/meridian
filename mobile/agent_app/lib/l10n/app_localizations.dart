import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/intl.dart' as intl;

import 'app_localizations_en.dart';
import 'app_localizations_sw.dart';

// ignore_for_file: type=lint

/// Callers can lookup localized strings with an instance of AppLocalizations
/// returned by `AppLocalizations.of(context)`.
///
/// Applications need to include `AppLocalizations.delegate()` in their app's
/// `localizationDelegates` list, and the locales they support in the app's
/// `supportedLocales` list. For example:
///
/// ```dart
/// import 'l10n/app_localizations.dart';
///
/// return MaterialApp(
///   localizationsDelegates: AppLocalizations.localizationsDelegates,
///   supportedLocales: AppLocalizations.supportedLocales,
///   home: MyApplicationHome(),
/// );
/// ```
///
/// ## Update pubspec.yaml
///
/// Please make sure to update your pubspec.yaml to include the following
/// packages:
///
/// ```yaml
/// dependencies:
///   # Internationalization support.
///   flutter_localizations:
///     sdk: flutter
///   intl: any # Use the pinned version from flutter_localizations
///
///   # Rest of dependencies
/// ```
///
/// ## iOS Applications
///
/// iOS applications define key application metadata, including supported
/// locales, in an Info.plist file that is built into the application bundle.
/// To configure the locales supported by your app, you’ll need to edit this
/// file.
///
/// First, open your project’s ios/Runner.xcworkspace Xcode workspace file.
/// Then, in the Project Navigator, open the Info.plist file under the Runner
/// project’s Runner folder.
///
/// Next, select the Information Property List item, select Add Item from the
/// Editor menu, then select Localizations from the pop-up menu.
///
/// Select and expand the newly-created Localizations item then, for each
/// locale your application supports, add a new item and select the locale
/// you wish to add from the pop-up menu in the Value field. This list should
/// be consistent with the languages listed in the AppLocalizations.supportedLocales
/// property.
abstract class AppLocalizations {
  AppLocalizations(String locale)
    : localeName = intl.Intl.canonicalizedLocale(locale.toString());

  final String localeName;

  static AppLocalizations of(BuildContext context) {
    return Localizations.of<AppLocalizations>(context, AppLocalizations)!;
  }

  static const LocalizationsDelegate<AppLocalizations> delegate =
      _AppLocalizationsDelegate();

  /// A list of this localizations delegate along with the default localizations
  /// delegates.
  ///
  /// Returns a list of localizations delegates containing this delegate along with
  /// GlobalMaterialLocalizations.delegate, GlobalCupertinoLocalizations.delegate,
  /// and GlobalWidgetsLocalizations.delegate.
  ///
  /// Additional delegates can be added by appending to this list in
  /// MaterialApp. This list does not have to be used at all if a custom list
  /// of delegates is preferred or required.
  static const List<LocalizationsDelegate<dynamic>> localizationsDelegates =
      <LocalizationsDelegate<dynamic>>[
        delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
      ];

  /// A list of this localizations delegate's supported locales.
  static const List<Locale> supportedLocales = <Locale>[
    Locale('en'),
    Locale('sw'),
  ];

  /// No description provided for @appTitle.
  ///
  /// In en, this message translates to:
  /// **'Meridian Agent'**
  String get appTitle;

  /// No description provided for @signIn.
  ///
  /// In en, this message translates to:
  /// **'Sign in'**
  String get signIn;

  /// No description provided for @signInWithBank.
  ///
  /// In en, this message translates to:
  /// **'Sign in with Meridian'**
  String get signInWithBank;

  /// No description provided for @devModeHint.
  ///
  /// In en, this message translates to:
  /// **'Dev mode: local services in dev security mode'**
  String get devModeHint;

  /// No description provided for @tenant.
  ///
  /// In en, this message translates to:
  /// **'Tenant'**
  String get tenant;

  /// No description provided for @agentId.
  ///
  /// In en, this message translates to:
  /// **'Agent ID'**
  String get agentId;

  /// No description provided for @username.
  ///
  /// In en, this message translates to:
  /// **'User name'**
  String get username;

  /// No description provided for @userId.
  ///
  /// In en, this message translates to:
  /// **'User ID'**
  String get userId;

  /// No description provided for @setupTitle.
  ///
  /// In en, this message translates to:
  /// **'Set up this device'**
  String get setupTitle;

  /// No description provided for @chooseOutlet.
  ///
  /// In en, this message translates to:
  /// **'Outlet'**
  String get chooseOutlet;

  /// No description provided for @appPin.
  ///
  /// In en, this message translates to:
  /// **'App PIN'**
  String get appPin;

  /// No description provided for @appPinHint.
  ///
  /// In en, this message translates to:
  /// **'6 digits; protects the work saved on this device'**
  String get appPinHint;

  /// No description provided for @confirmPin.
  ///
  /// In en, this message translates to:
  /// **'Repeat the app PIN'**
  String get confirmPin;

  /// No description provided for @pinMismatch.
  ///
  /// In en, this message translates to:
  /// **'The PINs do not match'**
  String get pinMismatch;

  /// No description provided for @pinInvalid.
  ///
  /// In en, this message translates to:
  /// **'Enter 6 digits'**
  String get pinInvalid;

  /// No description provided for @finishSetup.
  ///
  /// In en, this message translates to:
  /// **'Register device'**
  String get finishSetup;

  /// No description provided for @unlockTitle.
  ///
  /// In en, this message translates to:
  /// **'Unlock'**
  String get unlockTitle;

  /// No description provided for @unlock.
  ///
  /// In en, this message translates to:
  /// **'Unlock'**
  String get unlock;

  /// No description provided for @wrongPin.
  ///
  /// In en, this message translates to:
  /// **'Wrong PIN'**
  String get wrongPin;

  /// No description provided for @lock.
  ///
  /// In en, this message translates to:
  /// **'Lock'**
  String get lock;

  /// No description provided for @signOut.
  ///
  /// In en, this message translates to:
  /// **'Sign out'**
  String get signOut;

  /// No description provided for @online.
  ///
  /// In en, this message translates to:
  /// **'Online'**
  String get online;

  /// No description provided for @offline.
  ///
  /// In en, this message translates to:
  /// **'Offline'**
  String get offline;

  /// No description provided for @pendingCount.
  ///
  /// In en, this message translates to:
  /// **'{count} waiting to sync'**
  String pendingCount(int count);

  /// No description provided for @float.
  ///
  /// In en, this message translates to:
  /// **'Float'**
  String get float;

  /// No description provided for @cashIn.
  ///
  /// In en, this message translates to:
  /// **'Cash-in'**
  String get cashIn;

  /// No description provided for @cashOut.
  ///
  /// In en, this message translates to:
  /// **'Cash-out'**
  String get cashOut;

  /// No description provided for @billPay.
  ///
  /// In en, this message translates to:
  /// **'Bill payment'**
  String get billPay;

  /// No description provided for @floatRequest.
  ///
  /// In en, this message translates to:
  /// **'Float request'**
  String get floatRequest;

  /// No description provided for @history.
  ///
  /// In en, this message translates to:
  /// **'History'**
  String get history;

  /// No description provided for @sync.
  ///
  /// In en, this message translates to:
  /// **'Sync'**
  String get sync;

  /// No description provided for @customerPhone.
  ///
  /// In en, this message translates to:
  /// **'Customer phone number'**
  String get customerPhone;

  /// No description provided for @amount.
  ///
  /// In en, this message translates to:
  /// **'Amount'**
  String get amount;

  /// No description provided for @lookup.
  ///
  /// In en, this message translates to:
  /// **'Find customer'**
  String get lookup;

  /// No description provided for @customerFound.
  ///
  /// In en, this message translates to:
  /// **'Customer: {name}'**
  String customerFound(String name);

  /// No description provided for @customerNotFound.
  ///
  /// In en, this message translates to:
  /// **'No wallet found for this number'**
  String get customerNotFound;

  /// No description provided for @confirm.
  ///
  /// In en, this message translates to:
  /// **'Confirm'**
  String get confirm;

  /// No description provided for @cancel.
  ///
  /// In en, this message translates to:
  /// **'Cancel'**
  String get cancel;

  /// No description provided for @completed.
  ///
  /// In en, this message translates to:
  /// **'Completed'**
  String get completed;

  /// No description provided for @queued.
  ///
  /// In en, this message translates to:
  /// **'Saved on this device; it will be sent when online'**
  String get queued;

  /// No description provided for @rejected.
  ///
  /// In en, this message translates to:
  /// **'Refused: {reason}'**
  String rejected(String reason);

  /// No description provided for @agentPin.
  ///
  /// In en, this message translates to:
  /// **'Agent wallet PIN'**
  String get agentPin;

  /// No description provided for @agentPinHint.
  ///
  /// In en, this message translates to:
  /// **'Kept in memory for this session only'**
  String get agentPinHint;

  /// No description provided for @customerPin.
  ///
  /// In en, this message translates to:
  /// **'Customer PIN'**
  String get customerPin;

  /// No description provided for @otp.
  ///
  /// In en, this message translates to:
  /// **'One-time code'**
  String get otp;

  /// No description provided for @cashOutStarted.
  ///
  /// In en, this message translates to:
  /// **'Ask the customer to confirm with their PIN or code'**
  String get cashOutStarted;

  /// No description provided for @biller.
  ///
  /// In en, this message translates to:
  /// **'Biller'**
  String get biller;

  /// No description provided for @accountReference.
  ///
  /// In en, this message translates to:
  /// **'Account reference'**
  String get accountReference;

  /// No description provided for @customerName.
  ///
  /// In en, this message translates to:
  /// **'Customer name'**
  String get customerName;

  /// No description provided for @requestType.
  ///
  /// In en, this message translates to:
  /// **'Request type'**
  String get requestType;

  /// No description provided for @bankReference.
  ///
  /// In en, this message translates to:
  /// **'Bank reference'**
  String get bankReference;

  /// No description provided for @syncNow.
  ///
  /// In en, this message translates to:
  /// **'Sync now'**
  String get syncNow;

  /// No description provided for @syncResult.
  ///
  /// In en, this message translates to:
  /// **'{synced} sent, {remaining} still waiting'**
  String syncResult(int synced, int remaining);

  /// No description provided for @nothingPending.
  ///
  /// In en, this message translates to:
  /// **'Nothing waiting to sync'**
  String get nothingPending;

  /// No description provided for @remove.
  ///
  /// In en, this message translates to:
  /// **'Remove'**
  String get remove;

  /// No description provided for @statusPending.
  ///
  /// In en, this message translates to:
  /// **'Waiting'**
  String get statusPending;

  /// No description provided for @statusSynced.
  ///
  /// In en, this message translates to:
  /// **'Sent'**
  String get statusSynced;

  /// No description provided for @statusRejected.
  ///
  /// In en, this message translates to:
  /// **'Refused'**
  String get statusRejected;

  /// No description provided for @statusNeedsReview.
  ///
  /// In en, this message translates to:
  /// **'Needs review'**
  String get statusNeedsReview;

  /// No description provided for @required.
  ///
  /// In en, this message translates to:
  /// **'Required'**
  String get required;

  /// No description provided for @invalidAmount.
  ///
  /// In en, this message translates to:
  /// **'Enter a valid amount'**
  String get invalidAmount;

  /// No description provided for @retry.
  ///
  /// In en, this message translates to:
  /// **'Retry'**
  String get retry;

  /// No description provided for @errorGeneric.
  ///
  /// In en, this message translates to:
  /// **'Something went wrong'**
  String get errorGeneric;

  /// No description provided for @empty.
  ///
  /// In en, this message translates to:
  /// **'Nothing here yet'**
  String get empty;

  /// No description provided for @onDevice.
  ///
  /// In en, this message translates to:
  /// **'On this device'**
  String get onDevice;

  /// No description provided for @onServer.
  ///
  /// In en, this message translates to:
  /// **'Completed transactions'**
  String get onServer;

  /// No description provided for @offlineNotAllowed.
  ///
  /// In en, this message translates to:
  /// **'Cash-out needs a connection'**
  String get offlineNotAllowed;

  /// No description provided for @blocked.
  ///
  /// In en, this message translates to:
  /// **'Stopped: {reason}'**
  String blocked(String reason);
}

class _AppLocalizationsDelegate
    extends LocalizationsDelegate<AppLocalizations> {
  const _AppLocalizationsDelegate();

  @override
  Future<AppLocalizations> load(Locale locale) {
    return SynchronousFuture<AppLocalizations>(lookupAppLocalizations(locale));
  }

  @override
  bool isSupported(Locale locale) =>
      <String>['en', 'sw'].contains(locale.languageCode);

  @override
  bool shouldReload(_AppLocalizationsDelegate old) => false;
}

AppLocalizations lookupAppLocalizations(Locale locale) {
  // Lookup logic when only language code is specified.
  switch (locale.languageCode) {
    case 'en':
      return AppLocalizationsEn();
    case 'sw':
      return AppLocalizationsSw();
  }

  throw FlutterError(
    'AppLocalizations.delegate failed to load unsupported locale "$locale". This is likely '
    'an issue with the localizations generation tool. Please file an issue '
    'on GitHub with a reproducible sample app and the gen-l10n configuration '
    'that was used.',
  );
}
