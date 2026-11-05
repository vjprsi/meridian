// ignore: unused_import
import 'package:intl/intl.dart' as intl;

import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for English (`en`).
class AppLocalizationsEn extends AppLocalizations {
  AppLocalizationsEn([String locale = 'en']) : super(locale);

  @override
  String get appTitle => 'Meridian';

  @override
  String get signIn => 'Sign in';

  @override
  String get signInWithBank => 'Sign in with Meridian';

  @override
  String get unlock => 'Unlock';

  @override
  String get unlockReason => 'Unlock Meridian to use your saved sign-in';

  @override
  String get useAnotherAccount => 'Sign in with another account';

  @override
  String get signOut => 'Sign out';

  @override
  String get signOutEverywhere => 'Sign out everywhere';

  @override
  String get signOutEverywhereConfirm =>
      'Sign out on every device, including this one?';

  @override
  String get retry => 'Retry';

  @override
  String get cancel => 'Cancel';

  @override
  String get confirm => 'Confirm';

  @override
  String get save => 'Save';

  @override
  String get continueLabel => 'Continue';

  @override
  String get errorGeneric => 'Something went wrong';

  @override
  String get empty => 'Nothing here yet';

  @override
  String get loadMore => 'Load more';

  @override
  String get required => 'Required';

  @override
  String get devModeHint => 'Dev mode: local services in dev security mode';

  @override
  String get tenant => 'Tenant';

  @override
  String get customerId => 'Customer ID';

  @override
  String get username => 'User name';

  @override
  String get navHome => 'Home';

  @override
  String get navPay => 'Pay';

  @override
  String get navCards => 'Cards';

  @override
  String get navMore => 'More';

  @override
  String greeting(String name) {
    return 'Hello, $name';
  }

  @override
  String get accounts => 'Accounts';

  @override
  String get wallets => 'Wallets';

  @override
  String get cards => 'Cards';

  @override
  String get loans => 'Loans';

  @override
  String get available => 'Available';

  @override
  String get outstanding => 'Outstanding';

  @override
  String get insights => 'Insights';

  @override
  String get inbox => 'Inbox';

  @override
  String get payees => 'Payees';

  @override
  String get security => 'Security';

  @override
  String get settings => 'Settings';

  @override
  String get transactions => 'Transactions';

  @override
  String get noTransactions => 'No transactions yet';

  @override
  String get moveMoney => 'Move money';

  @override
  String get fromAccount => 'From';

  @override
  String get toAccount => 'To';

  @override
  String get kindOwn => 'My account';

  @override
  String get kindPayee => 'Payee';

  @override
  String get amount => 'Amount';

  @override
  String get reference => 'Reference';

  @override
  String get review => 'Review';

  @override
  String get confirmPayment => 'Confirm payment';

  @override
  String paymentSubmitted(String status) {
    return 'Payment submitted ($status)';
  }

  @override
  String get invalidAmount => 'Enter a valid amount';

  @override
  String get fee => 'Fee';

  @override
  String get stepUpTitle => 'Confirm it\'s you';

  @override
  String get stepUpExplain =>
      'For your security, confirm with your second factor (passkey, security key or authenticator app).';

  @override
  String get stepUpUnavailable =>
      'A second factor is needed. Add a passkey or an authenticator app, then try again.';

  @override
  String get deviceCompromised =>
      'This device failed the security check, so payments and security changes are turned off.';

  @override
  String get addPayee => 'Add payee';

  @override
  String get payeeName => 'Name';

  @override
  String get nickname => 'Nickname';

  @override
  String get accountNumber => 'Account number';

  @override
  String get accountIdType => 'Account type';

  @override
  String get country => 'Country (ISO code)';

  @override
  String get bankName => 'Bank';

  @override
  String get trust => 'Trust';

  @override
  String get trusted => 'Trusted';

  @override
  String coolingOff(String date) {
    return 'Payments possible after $date';
  }

  @override
  String get delete => 'Delete';

  @override
  String get payeeAdded => 'Payee added';

  @override
  String get freeze => 'Freeze';

  @override
  String get unfreeze => 'Unfreeze';

  @override
  String get frozen => 'Frozen';

  @override
  String get controls => 'Where the card works';

  @override
  String get chEcom => 'Online';

  @override
  String get chContactless => 'Contactless';

  @override
  String get chAtm => 'ATM';

  @override
  String get chPos => 'In shops';

  @override
  String get limits => 'Spend limits';

  @override
  String limitUsed(String used, String max) {
    return '$used of $max used';
  }

  @override
  String get noLimit => 'No limit';

  @override
  String get setLimit => 'Set limit';

  @override
  String get period => 'Period';

  @override
  String get reportLost => 'Report lost or stolen';

  @override
  String get lost => 'Lost';

  @override
  String get stolen => 'Stolen';

  @override
  String get orderReplacement => 'Order a replacement';

  @override
  String get cardBlocked => 'Card blocked';

  @override
  String get schedule => 'Schedule';

  @override
  String get repayments => 'Repayments';

  @override
  String get repay => 'Repay';

  @override
  String get payFrom => 'Pay from';

  @override
  String nextDue(String date) {
    return 'Next due $date';
  }

  @override
  String get overdue => 'Overdue';

  @override
  String installment(int no) {
    return 'Instalment $no';
  }

  @override
  String get repaymentPosted => 'Repayment posted';

  @override
  String get spending => 'Spending this month';

  @override
  String get noSpending => 'No spending this month';

  @override
  String get dismiss => 'Dismiss';

  @override
  String get messages => 'Messages';

  @override
  String get notifications => 'Notifications';

  @override
  String get newMessage => 'New message';

  @override
  String get subject => 'Subject';

  @override
  String get message => 'Message';

  @override
  String get send => 'Send';

  @override
  String get sessions => 'Signed-in devices';

  @override
  String get thisDevice => 'This device';

  @override
  String authLevel(int level) {
    return 'Sign-in strength: level $level';
  }

  @override
  String get secondFactors => 'Second factors';

  @override
  String get noSecondFactor =>
      'No second factor yet: add one in web banking under Security';

  @override
  String get biometricUnlock => 'Unlock with biometrics';

  @override
  String get biometricUnavailable =>
      'Biometric unlock is not available on this device';

  @override
  String get theme => 'Theme';

  @override
  String get themeSystem => 'System';

  @override
  String get themeLight => 'Light';

  @override
  String get themeDark => 'Dark';

  @override
  String get language => 'Language';

  @override
  String get offlineBanner => 'You are offline. Showing what was loaded last.';

  @override
  String get sessionEnded => 'You were signed out. Please sign in again.';

  @override
  String get wallet => 'Wallet';

  @override
  String get request => 'Request';

  @override
  String get topUp => 'Top up';

  @override
  String get payBill => 'Pay bill';

  @override
  String get scanQr => 'Scan QR';

  @override
  String get payMerchant => 'Pay merchant';

  @override
  String get receive => 'Receive';

  @override
  String get myQr => 'My QR';

  @override
  String get topUpWallet => 'Add money';

  @override
  String get toBank => 'To bank';

  @override
  String get sendMoney => 'Send money';

  @override
  String get recipient => 'Phone or wallet number';

  @override
  String get byPhone => 'Phone';

  @override
  String get byWallet => 'Wallet number';

  @override
  String get lookUp => 'Look up';

  @override
  String get payee => 'Payee';

  @override
  String get note => 'Note';

  @override
  String get total => 'Total';

  @override
  String get fromWallet => 'From wallet';

  @override
  String get toWallet => 'To wallet';

  @override
  String get moneySent => 'Money sent';

  @override
  String get requestMoney => 'Request money';

  @override
  String get incoming => 'Incoming';

  @override
  String get outgoing => 'Outgoing';

  @override
  String get decline => 'Decline';

  @override
  String get declineReason => 'Reason';

  @override
  String get payNow => 'Pay';

  @override
  String get requestSent => 'Request sent';

  @override
  String get noRequests => 'No money requests';

  @override
  String get topUpAirtime => 'Airtime & data';

  @override
  String get operator => 'Operator';

  @override
  String get phoneNumber => 'Phone number';

  @override
  String get bundle => 'Bundle';

  @override
  String get customAmount => 'Other amount';

  @override
  String get rechargeToken => 'Recharge token';

  @override
  String get topUpDone => 'Top-up submitted';

  @override
  String get payABill => 'Pay a bill';

  @override
  String get biller => 'Biller';

  @override
  String get selectBiller => 'Select a biller';

  @override
  String get referenceNumber => 'Reference / account number';

  @override
  String get check => 'Check';

  @override
  String get amountDue => 'Amount due';

  @override
  String get billPaid => 'Bill payment submitted';

  @override
  String get receipt => 'Receipt';

  @override
  String get search => 'Search';

  @override
  String get scanToPay => 'Scan to pay';

  @override
  String get qrData => 'QR data';

  @override
  String get pasteQrHint => 'Paste or type the QR contents';

  @override
  String get myQrTitle => 'My QR code';

  @override
  String get createQr => 'Create QR';

  @override
  String get amountOptional => 'Amount (optional)';

  @override
  String get showThisQr => 'Show this code to be paid';

  @override
  String get merchantPayTitle => 'Pay a merchant';

  @override
  String get tillNumber => 'Till number';

  @override
  String get activate => 'Activate';

  @override
  String get activateCard => 'Activate card';

  @override
  String get cardInactive => 'Not activated yet';

  @override
  String get cardActivated => 'Card activated';

  @override
  String get recent => 'Recent activity';

  @override
  String get paymentSent => 'Payment sent';

  @override
  String get addMoneyToWallet => 'Add money to wallet';

  @override
  String get transferToBank => 'Transfer to bank account';
}
