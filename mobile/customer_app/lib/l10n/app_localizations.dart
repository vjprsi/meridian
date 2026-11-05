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
  /// **'Meridian'**
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

  /// No description provided for @unlock.
  ///
  /// In en, this message translates to:
  /// **'Unlock'**
  String get unlock;

  /// No description provided for @unlockReason.
  ///
  /// In en, this message translates to:
  /// **'Unlock Meridian to use your saved sign-in'**
  String get unlockReason;

  /// No description provided for @useAnotherAccount.
  ///
  /// In en, this message translates to:
  /// **'Sign in with another account'**
  String get useAnotherAccount;

  /// No description provided for @signOut.
  ///
  /// In en, this message translates to:
  /// **'Sign out'**
  String get signOut;

  /// No description provided for @signOutEverywhere.
  ///
  /// In en, this message translates to:
  /// **'Sign out everywhere'**
  String get signOutEverywhere;

  /// No description provided for @signOutEverywhereConfirm.
  ///
  /// In en, this message translates to:
  /// **'Sign out on every device, including this one?'**
  String get signOutEverywhereConfirm;

  /// No description provided for @retry.
  ///
  /// In en, this message translates to:
  /// **'Retry'**
  String get retry;

  /// No description provided for @cancel.
  ///
  /// In en, this message translates to:
  /// **'Cancel'**
  String get cancel;

  /// No description provided for @confirm.
  ///
  /// In en, this message translates to:
  /// **'Confirm'**
  String get confirm;

  /// No description provided for @save.
  ///
  /// In en, this message translates to:
  /// **'Save'**
  String get save;

  /// No description provided for @continueLabel.
  ///
  /// In en, this message translates to:
  /// **'Continue'**
  String get continueLabel;

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

  /// No description provided for @loadMore.
  ///
  /// In en, this message translates to:
  /// **'Load more'**
  String get loadMore;

  /// No description provided for @required.
  ///
  /// In en, this message translates to:
  /// **'Required'**
  String get required;

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

  /// No description provided for @customerId.
  ///
  /// In en, this message translates to:
  /// **'Customer ID'**
  String get customerId;

  /// No description provided for @username.
  ///
  /// In en, this message translates to:
  /// **'User name'**
  String get username;

  /// No description provided for @navHome.
  ///
  /// In en, this message translates to:
  /// **'Home'**
  String get navHome;

  /// No description provided for @navPay.
  ///
  /// In en, this message translates to:
  /// **'Pay'**
  String get navPay;

  /// No description provided for @navCards.
  ///
  /// In en, this message translates to:
  /// **'Cards'**
  String get navCards;

  /// No description provided for @navMore.
  ///
  /// In en, this message translates to:
  /// **'More'**
  String get navMore;

  /// No description provided for @greeting.
  ///
  /// In en, this message translates to:
  /// **'Hello, {name}'**
  String greeting(String name);

  /// No description provided for @accounts.
  ///
  /// In en, this message translates to:
  /// **'Accounts'**
  String get accounts;

  /// No description provided for @wallets.
  ///
  /// In en, this message translates to:
  /// **'Wallets'**
  String get wallets;

  /// No description provided for @cards.
  ///
  /// In en, this message translates to:
  /// **'Cards'**
  String get cards;

  /// No description provided for @loans.
  ///
  /// In en, this message translates to:
  /// **'Loans'**
  String get loans;

  /// No description provided for @available.
  ///
  /// In en, this message translates to:
  /// **'Available'**
  String get available;

  /// No description provided for @outstanding.
  ///
  /// In en, this message translates to:
  /// **'Outstanding'**
  String get outstanding;

  /// No description provided for @insights.
  ///
  /// In en, this message translates to:
  /// **'Insights'**
  String get insights;

  /// No description provided for @inbox.
  ///
  /// In en, this message translates to:
  /// **'Inbox'**
  String get inbox;

  /// No description provided for @payees.
  ///
  /// In en, this message translates to:
  /// **'Payees'**
  String get payees;

  /// No description provided for @security.
  ///
  /// In en, this message translates to:
  /// **'Security'**
  String get security;

  /// No description provided for @settings.
  ///
  /// In en, this message translates to:
  /// **'Settings'**
  String get settings;

  /// No description provided for @transactions.
  ///
  /// In en, this message translates to:
  /// **'Transactions'**
  String get transactions;

  /// No description provided for @noTransactions.
  ///
  /// In en, this message translates to:
  /// **'No transactions yet'**
  String get noTransactions;

  /// No description provided for @moveMoney.
  ///
  /// In en, this message translates to:
  /// **'Move money'**
  String get moveMoney;

  /// No description provided for @fromAccount.
  ///
  /// In en, this message translates to:
  /// **'From'**
  String get fromAccount;

  /// No description provided for @toAccount.
  ///
  /// In en, this message translates to:
  /// **'To'**
  String get toAccount;

  /// No description provided for @kindOwn.
  ///
  /// In en, this message translates to:
  /// **'My account'**
  String get kindOwn;

  /// No description provided for @kindPayee.
  ///
  /// In en, this message translates to:
  /// **'Payee'**
  String get kindPayee;

  /// No description provided for @amount.
  ///
  /// In en, this message translates to:
  /// **'Amount'**
  String get amount;

  /// No description provided for @reference.
  ///
  /// In en, this message translates to:
  /// **'Reference'**
  String get reference;

  /// No description provided for @review.
  ///
  /// In en, this message translates to:
  /// **'Review'**
  String get review;

  /// No description provided for @confirmPayment.
  ///
  /// In en, this message translates to:
  /// **'Confirm payment'**
  String get confirmPayment;

  /// No description provided for @paymentSubmitted.
  ///
  /// In en, this message translates to:
  /// **'Payment submitted ({status})'**
  String paymentSubmitted(String status);

  /// No description provided for @invalidAmount.
  ///
  /// In en, this message translates to:
  /// **'Enter a valid amount'**
  String get invalidAmount;

  /// No description provided for @fee.
  ///
  /// In en, this message translates to:
  /// **'Fee'**
  String get fee;

  /// No description provided for @stepUpTitle.
  ///
  /// In en, this message translates to:
  /// **'Confirm it\'s you'**
  String get stepUpTitle;

  /// No description provided for @stepUpExplain.
  ///
  /// In en, this message translates to:
  /// **'For your security, confirm with your second factor (passkey, security key or authenticator app).'**
  String get stepUpExplain;

  /// No description provided for @stepUpUnavailable.
  ///
  /// In en, this message translates to:
  /// **'A second factor is needed. Add a passkey or an authenticator app, then try again.'**
  String get stepUpUnavailable;

  /// No description provided for @deviceCompromised.
  ///
  /// In en, this message translates to:
  /// **'This device failed the security check, so payments and security changes are turned off.'**
  String get deviceCompromised;

  /// No description provided for @addPayee.
  ///
  /// In en, this message translates to:
  /// **'Add payee'**
  String get addPayee;

  /// No description provided for @payeeName.
  ///
  /// In en, this message translates to:
  /// **'Name'**
  String get payeeName;

  /// No description provided for @nickname.
  ///
  /// In en, this message translates to:
  /// **'Nickname'**
  String get nickname;

  /// No description provided for @accountNumber.
  ///
  /// In en, this message translates to:
  /// **'Account number'**
  String get accountNumber;

  /// No description provided for @accountIdType.
  ///
  /// In en, this message translates to:
  /// **'Account type'**
  String get accountIdType;

  /// No description provided for @country.
  ///
  /// In en, this message translates to:
  /// **'Country (ISO code)'**
  String get country;

  /// No description provided for @bankName.
  ///
  /// In en, this message translates to:
  /// **'Bank'**
  String get bankName;

  /// No description provided for @trust.
  ///
  /// In en, this message translates to:
  /// **'Trust'**
  String get trust;

  /// No description provided for @trusted.
  ///
  /// In en, this message translates to:
  /// **'Trusted'**
  String get trusted;

  /// No description provided for @coolingOff.
  ///
  /// In en, this message translates to:
  /// **'Payments possible after {date}'**
  String coolingOff(String date);

  /// No description provided for @delete.
  ///
  /// In en, this message translates to:
  /// **'Delete'**
  String get delete;

  /// No description provided for @payeeAdded.
  ///
  /// In en, this message translates to:
  /// **'Payee added'**
  String get payeeAdded;

  /// No description provided for @freeze.
  ///
  /// In en, this message translates to:
  /// **'Freeze'**
  String get freeze;

  /// No description provided for @unfreeze.
  ///
  /// In en, this message translates to:
  /// **'Unfreeze'**
  String get unfreeze;

  /// No description provided for @frozen.
  ///
  /// In en, this message translates to:
  /// **'Frozen'**
  String get frozen;

  /// No description provided for @controls.
  ///
  /// In en, this message translates to:
  /// **'Where the card works'**
  String get controls;

  /// No description provided for @chEcom.
  ///
  /// In en, this message translates to:
  /// **'Online'**
  String get chEcom;

  /// No description provided for @chContactless.
  ///
  /// In en, this message translates to:
  /// **'Contactless'**
  String get chContactless;

  /// No description provided for @chAtm.
  ///
  /// In en, this message translates to:
  /// **'ATM'**
  String get chAtm;

  /// No description provided for @chPos.
  ///
  /// In en, this message translates to:
  /// **'In shops'**
  String get chPos;

  /// No description provided for @limits.
  ///
  /// In en, this message translates to:
  /// **'Spend limits'**
  String get limits;

  /// No description provided for @limitUsed.
  ///
  /// In en, this message translates to:
  /// **'{used} of {max} used'**
  String limitUsed(String used, String max);

  /// No description provided for @noLimit.
  ///
  /// In en, this message translates to:
  /// **'No limit'**
  String get noLimit;

  /// No description provided for @setLimit.
  ///
  /// In en, this message translates to:
  /// **'Set limit'**
  String get setLimit;

  /// No description provided for @period.
  ///
  /// In en, this message translates to:
  /// **'Period'**
  String get period;

  /// No description provided for @reportLost.
  ///
  /// In en, this message translates to:
  /// **'Report lost or stolen'**
  String get reportLost;

  /// No description provided for @lost.
  ///
  /// In en, this message translates to:
  /// **'Lost'**
  String get lost;

  /// No description provided for @stolen.
  ///
  /// In en, this message translates to:
  /// **'Stolen'**
  String get stolen;

  /// No description provided for @orderReplacement.
  ///
  /// In en, this message translates to:
  /// **'Order a replacement'**
  String get orderReplacement;

  /// No description provided for @cardBlocked.
  ///
  /// In en, this message translates to:
  /// **'Card blocked'**
  String get cardBlocked;

  /// No description provided for @schedule.
  ///
  /// In en, this message translates to:
  /// **'Schedule'**
  String get schedule;

  /// No description provided for @repayments.
  ///
  /// In en, this message translates to:
  /// **'Repayments'**
  String get repayments;

  /// No description provided for @repay.
  ///
  /// In en, this message translates to:
  /// **'Repay'**
  String get repay;

  /// No description provided for @payFrom.
  ///
  /// In en, this message translates to:
  /// **'Pay from'**
  String get payFrom;

  /// No description provided for @nextDue.
  ///
  /// In en, this message translates to:
  /// **'Next due {date}'**
  String nextDue(String date);

  /// No description provided for @overdue.
  ///
  /// In en, this message translates to:
  /// **'Overdue'**
  String get overdue;

  /// No description provided for @installment.
  ///
  /// In en, this message translates to:
  /// **'Instalment {no}'**
  String installment(int no);

  /// No description provided for @repaymentPosted.
  ///
  /// In en, this message translates to:
  /// **'Repayment posted'**
  String get repaymentPosted;

  /// No description provided for @spending.
  ///
  /// In en, this message translates to:
  /// **'Spending this month'**
  String get spending;

  /// No description provided for @noSpending.
  ///
  /// In en, this message translates to:
  /// **'No spending this month'**
  String get noSpending;

  /// No description provided for @dismiss.
  ///
  /// In en, this message translates to:
  /// **'Dismiss'**
  String get dismiss;

  /// No description provided for @messages.
  ///
  /// In en, this message translates to:
  /// **'Messages'**
  String get messages;

  /// No description provided for @notifications.
  ///
  /// In en, this message translates to:
  /// **'Notifications'**
  String get notifications;

  /// No description provided for @newMessage.
  ///
  /// In en, this message translates to:
  /// **'New message'**
  String get newMessage;

  /// No description provided for @subject.
  ///
  /// In en, this message translates to:
  /// **'Subject'**
  String get subject;

  /// No description provided for @message.
  ///
  /// In en, this message translates to:
  /// **'Message'**
  String get message;

  /// No description provided for @send.
  ///
  /// In en, this message translates to:
  /// **'Send'**
  String get send;

  /// No description provided for @sessions.
  ///
  /// In en, this message translates to:
  /// **'Signed-in devices'**
  String get sessions;

  /// No description provided for @thisDevice.
  ///
  /// In en, this message translates to:
  /// **'This device'**
  String get thisDevice;

  /// No description provided for @authLevel.
  ///
  /// In en, this message translates to:
  /// **'Sign-in strength: level {level}'**
  String authLevel(int level);

  /// No description provided for @secondFactors.
  ///
  /// In en, this message translates to:
  /// **'Second factors'**
  String get secondFactors;

  /// No description provided for @noSecondFactor.
  ///
  /// In en, this message translates to:
  /// **'No second factor yet: add one in web banking under Security'**
  String get noSecondFactor;

  /// No description provided for @biometricUnlock.
  ///
  /// In en, this message translates to:
  /// **'Unlock with biometrics'**
  String get biometricUnlock;

  /// No description provided for @biometricUnavailable.
  ///
  /// In en, this message translates to:
  /// **'Biometric unlock is not available on this device'**
  String get biometricUnavailable;

  /// No description provided for @theme.
  ///
  /// In en, this message translates to:
  /// **'Theme'**
  String get theme;

  /// No description provided for @themeSystem.
  ///
  /// In en, this message translates to:
  /// **'System'**
  String get themeSystem;

  /// No description provided for @themeLight.
  ///
  /// In en, this message translates to:
  /// **'Light'**
  String get themeLight;

  /// No description provided for @themeDark.
  ///
  /// In en, this message translates to:
  /// **'Dark'**
  String get themeDark;

  /// No description provided for @language.
  ///
  /// In en, this message translates to:
  /// **'Language'**
  String get language;

  /// No description provided for @offlineBanner.
  ///
  /// In en, this message translates to:
  /// **'You are offline. Showing what was loaded last.'**
  String get offlineBanner;

  /// No description provided for @sessionEnded.
  ///
  /// In en, this message translates to:
  /// **'You were signed out. Please sign in again.'**
  String get sessionEnded;

  /// No description provided for @wallet.
  ///
  /// In en, this message translates to:
  /// **'Wallet'**
  String get wallet;

  /// No description provided for @request.
  ///
  /// In en, this message translates to:
  /// **'Request'**
  String get request;

  /// No description provided for @topUp.
  ///
  /// In en, this message translates to:
  /// **'Top up'**
  String get topUp;

  /// No description provided for @payBill.
  ///
  /// In en, this message translates to:
  /// **'Pay bill'**
  String get payBill;

  /// No description provided for @scanQr.
  ///
  /// In en, this message translates to:
  /// **'Scan QR'**
  String get scanQr;

  /// No description provided for @payMerchant.
  ///
  /// In en, this message translates to:
  /// **'Pay merchant'**
  String get payMerchant;

  /// No description provided for @receive.
  ///
  /// In en, this message translates to:
  /// **'Receive'**
  String get receive;

  /// No description provided for @myQr.
  ///
  /// In en, this message translates to:
  /// **'My QR'**
  String get myQr;

  /// No description provided for @topUpWallet.
  ///
  /// In en, this message translates to:
  /// **'Add money'**
  String get topUpWallet;

  /// No description provided for @toBank.
  ///
  /// In en, this message translates to:
  /// **'To bank'**
  String get toBank;

  /// No description provided for @sendMoney.
  ///
  /// In en, this message translates to:
  /// **'Send money'**
  String get sendMoney;

  /// No description provided for @recipient.
  ///
  /// In en, this message translates to:
  /// **'Phone or wallet number'**
  String get recipient;

  /// No description provided for @byPhone.
  ///
  /// In en, this message translates to:
  /// **'Phone'**
  String get byPhone;

  /// No description provided for @byWallet.
  ///
  /// In en, this message translates to:
  /// **'Wallet number'**
  String get byWallet;

  /// No description provided for @lookUp.
  ///
  /// In en, this message translates to:
  /// **'Look up'**
  String get lookUp;

  /// No description provided for @payee.
  ///
  /// In en, this message translates to:
  /// **'Payee'**
  String get payee;

  /// No description provided for @note.
  ///
  /// In en, this message translates to:
  /// **'Note'**
  String get note;

  /// No description provided for @total.
  ///
  /// In en, this message translates to:
  /// **'Total'**
  String get total;

  /// No description provided for @fromWallet.
  ///
  /// In en, this message translates to:
  /// **'From wallet'**
  String get fromWallet;

  /// No description provided for @toWallet.
  ///
  /// In en, this message translates to:
  /// **'To wallet'**
  String get toWallet;

  /// No description provided for @moneySent.
  ///
  /// In en, this message translates to:
  /// **'Money sent'**
  String get moneySent;

  /// No description provided for @requestMoney.
  ///
  /// In en, this message translates to:
  /// **'Request money'**
  String get requestMoney;

  /// No description provided for @incoming.
  ///
  /// In en, this message translates to:
  /// **'Incoming'**
  String get incoming;

  /// No description provided for @outgoing.
  ///
  /// In en, this message translates to:
  /// **'Outgoing'**
  String get outgoing;

  /// No description provided for @decline.
  ///
  /// In en, this message translates to:
  /// **'Decline'**
  String get decline;

  /// No description provided for @declineReason.
  ///
  /// In en, this message translates to:
  /// **'Reason'**
  String get declineReason;

  /// No description provided for @payNow.
  ///
  /// In en, this message translates to:
  /// **'Pay'**
  String get payNow;

  /// No description provided for @requestSent.
  ///
  /// In en, this message translates to:
  /// **'Request sent'**
  String get requestSent;

  /// No description provided for @noRequests.
  ///
  /// In en, this message translates to:
  /// **'No money requests'**
  String get noRequests;

  /// No description provided for @topUpAirtime.
  ///
  /// In en, this message translates to:
  /// **'Airtime & data'**
  String get topUpAirtime;

  /// No description provided for @operator.
  ///
  /// In en, this message translates to:
  /// **'Operator'**
  String get operator;

  /// No description provided for @phoneNumber.
  ///
  /// In en, this message translates to:
  /// **'Phone number'**
  String get phoneNumber;

  /// No description provided for @bundle.
  ///
  /// In en, this message translates to:
  /// **'Bundle'**
  String get bundle;

  /// No description provided for @customAmount.
  ///
  /// In en, this message translates to:
  /// **'Other amount'**
  String get customAmount;

  /// No description provided for @rechargeToken.
  ///
  /// In en, this message translates to:
  /// **'Recharge token'**
  String get rechargeToken;

  /// No description provided for @topUpDone.
  ///
  /// In en, this message translates to:
  /// **'Top-up submitted'**
  String get topUpDone;

  /// No description provided for @payABill.
  ///
  /// In en, this message translates to:
  /// **'Pay a bill'**
  String get payABill;

  /// No description provided for @biller.
  ///
  /// In en, this message translates to:
  /// **'Biller'**
  String get biller;

  /// No description provided for @selectBiller.
  ///
  /// In en, this message translates to:
  /// **'Select a biller'**
  String get selectBiller;

  /// No description provided for @referenceNumber.
  ///
  /// In en, this message translates to:
  /// **'Reference / account number'**
  String get referenceNumber;

  /// No description provided for @check.
  ///
  /// In en, this message translates to:
  /// **'Check'**
  String get check;

  /// No description provided for @amountDue.
  ///
  /// In en, this message translates to:
  /// **'Amount due'**
  String get amountDue;

  /// No description provided for @billPaid.
  ///
  /// In en, this message translates to:
  /// **'Bill payment submitted'**
  String get billPaid;

  /// No description provided for @receipt.
  ///
  /// In en, this message translates to:
  /// **'Receipt'**
  String get receipt;

  /// No description provided for @search.
  ///
  /// In en, this message translates to:
  /// **'Search'**
  String get search;

  /// No description provided for @scanToPay.
  ///
  /// In en, this message translates to:
  /// **'Scan to pay'**
  String get scanToPay;

  /// No description provided for @qrData.
  ///
  /// In en, this message translates to:
  /// **'QR data'**
  String get qrData;

  /// No description provided for @pasteQrHint.
  ///
  /// In en, this message translates to:
  /// **'Paste or type the QR contents'**
  String get pasteQrHint;

  /// No description provided for @myQrTitle.
  ///
  /// In en, this message translates to:
  /// **'My QR code'**
  String get myQrTitle;

  /// No description provided for @createQr.
  ///
  /// In en, this message translates to:
  /// **'Create QR'**
  String get createQr;

  /// No description provided for @amountOptional.
  ///
  /// In en, this message translates to:
  /// **'Amount (optional)'**
  String get amountOptional;

  /// No description provided for @showThisQr.
  ///
  /// In en, this message translates to:
  /// **'Show this code to be paid'**
  String get showThisQr;

  /// No description provided for @merchantPayTitle.
  ///
  /// In en, this message translates to:
  /// **'Pay a merchant'**
  String get merchantPayTitle;

  /// No description provided for @tillNumber.
  ///
  /// In en, this message translates to:
  /// **'Till number'**
  String get tillNumber;

  /// No description provided for @activate.
  ///
  /// In en, this message translates to:
  /// **'Activate'**
  String get activate;

  /// No description provided for @activateCard.
  ///
  /// In en, this message translates to:
  /// **'Activate card'**
  String get activateCard;

  /// No description provided for @cardInactive.
  ///
  /// In en, this message translates to:
  /// **'Not activated yet'**
  String get cardInactive;

  /// No description provided for @cardActivated.
  ///
  /// In en, this message translates to:
  /// **'Card activated'**
  String get cardActivated;

  /// No description provided for @recent.
  ///
  /// In en, this message translates to:
  /// **'Recent activity'**
  String get recent;

  /// No description provided for @paymentSent.
  ///
  /// In en, this message translates to:
  /// **'Payment sent'**
  String get paymentSent;

  /// No description provided for @addMoneyToWallet.
  ///
  /// In en, this message translates to:
  /// **'Add money to wallet'**
  String get addMoneyToWallet;

  /// No description provided for @transferToBank.
  ///
  /// In en, this message translates to:
  /// **'Transfer to bank account'**
  String get transferToBank;
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
