// ignore: unused_import
import 'package:intl/intl.dart' as intl;

import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Swahili (`sw`).
class AppLocalizationsSw extends AppLocalizations {
  AppLocalizationsSw([String locale = 'sw']) : super(locale);

  @override
  String get appTitle => 'Meridian';

  @override
  String get signIn => 'Ingia';

  @override
  String get signInWithBank => 'Ingia kwa Meridian';

  @override
  String get unlock => 'Fungua';

  @override
  String get unlockReason =>
      'Fungua Meridian ili kutumia kuingia kulikohifadhiwa';

  @override
  String get useAnotherAccount => 'Ingia kwa akaunti nyingine';

  @override
  String get signOut => 'Toka';

  @override
  String get signOutEverywhere => 'Toka kwenye vifaa vyote';

  @override
  String get signOutEverywhereConfirm =>
      'Toka kwenye kila kifaa, pamoja na hiki?';

  @override
  String get retry => 'Jaribu tena';

  @override
  String get cancel => 'Ghairi';

  @override
  String get confirm => 'Thibitisha';

  @override
  String get save => 'Hifadhi';

  @override
  String get continueLabel => 'Endelea';

  @override
  String get errorGeneric => 'Hitilafu imetokea';

  @override
  String get empty => 'Hakuna kitu bado';

  @override
  String get loadMore => 'Pakia zaidi';

  @override
  String get required => 'Inahitajika';

  @override
  String get devModeHint =>
      'Hali ya majaribio: huduma za ndani katika hali ya usalama ya majaribio';

  @override
  String get tenant => 'Mpangaji';

  @override
  String get customerId => 'Kitambulisho cha mteja';

  @override
  String get username => 'Jina la mtumiaji';

  @override
  String get navHome => 'Mwanzo';

  @override
  String get navPay => 'Lipa';

  @override
  String get navCards => 'Kadi';

  @override
  String get navMore => 'Zaidi';

  @override
  String greeting(String name) {
    return 'Habari, $name';
  }

  @override
  String get accounts => 'Akaunti';

  @override
  String get wallets => 'Pochi';

  @override
  String get cards => 'Kadi';

  @override
  String get loans => 'Mikopo';

  @override
  String get available => 'Inayopatikana';

  @override
  String get outstanding => 'Deni lililobaki';

  @override
  String get insights => 'Maarifa';

  @override
  String get inbox => 'Kikasha';

  @override
  String get payees => 'Wapokeaji';

  @override
  String get security => 'Usalama';

  @override
  String get settings => 'Mipangilio';

  @override
  String get transactions => 'Miamala';

  @override
  String get noTransactions => 'Hakuna miamala bado';

  @override
  String get moveMoney => 'Hamisha pesa';

  @override
  String get fromAccount => 'Kutoka';

  @override
  String get toAccount => 'Kwenda';

  @override
  String get kindOwn => 'Akaunti yangu';

  @override
  String get kindPayee => 'Mpokeaji';

  @override
  String get amount => 'Kiasi';

  @override
  String get reference => 'Kumbukumbu';

  @override
  String get review => 'Kagua';

  @override
  String get confirmPayment => 'Thibitisha malipo';

  @override
  String paymentSubmitted(String status) {
    return 'Malipo yamewasilishwa ($status)';
  }

  @override
  String get invalidAmount => 'Weka kiasi sahihi';

  @override
  String get fee => 'Ada';

  @override
  String get stepUpTitle => 'Thibitisha kuwa ni wewe';

  @override
  String get stepUpExplain =>
      'Kwa usalama wako, thibitisha kwa kipengele chako cha pili (passkey, ufunguo wa usalama au programu ya uthibitishaji).';

  @override
  String get stepUpUnavailable =>
      'Kipengele cha pili kinahitajika. Ongeza passkey au programu ya uthibitishaji, kisha jaribu tena.';

  @override
  String get deviceCompromised =>
      'Kifaa hiki hakikupita ukaguzi wa usalama, hivyo malipo na mabadiliko ya usalama yamezimwa.';

  @override
  String get addPayee => 'Ongeza mpokeaji';

  @override
  String get payeeName => 'Jina';

  @override
  String get nickname => 'Jina la utani';

  @override
  String get accountNumber => 'Nambari ya akaunti';

  @override
  String get accountIdType => 'Aina ya akaunti';

  @override
  String get country => 'Nchi (msimbo wa ISO)';

  @override
  String get bankName => 'Benki';

  @override
  String get trust => 'Amini';

  @override
  String get trusted => 'Anaaminika';

  @override
  String coolingOff(String date) {
    return 'Malipo yanawezekana baada ya $date';
  }

  @override
  String get delete => 'Futa';

  @override
  String get payeeAdded => 'Mpokeaji ameongezwa';

  @override
  String get freeze => 'Simamisha';

  @override
  String get unfreeze => 'Ruhusu tena';

  @override
  String get frozen => 'Imesimamishwa';

  @override
  String get controls => 'Mahali kadi inapofanya kazi';

  @override
  String get chEcom => 'Mtandaoni';

  @override
  String get chContactless => 'Bila kugusa';

  @override
  String get chAtm => 'ATM';

  @override
  String get chPos => 'Madukani';

  @override
  String get limits => 'Vikomo vya matumizi';

  @override
  String limitUsed(String used, String max) {
    return '$used kati ya $max vimetumika';
  }

  @override
  String get noLimit => 'Hakuna kikomo';

  @override
  String get setLimit => 'Weka kikomo';

  @override
  String get period => 'Kipindi';

  @override
  String get reportLost => 'Ripoti imepotea au imeibiwa';

  @override
  String get lost => 'Imepotea';

  @override
  String get stolen => 'Imeibiwa';

  @override
  String get orderReplacement => 'Agiza kadi mbadala';

  @override
  String get cardBlocked => 'Kadi imezuiwa';

  @override
  String get schedule => 'Ratiba';

  @override
  String get repayments => 'Marejesho';

  @override
  String get repay => 'Rejesha';

  @override
  String get payFrom => 'Lipa kutoka';

  @override
  String nextDue(String date) {
    return 'Malipo yajayo $date';
  }

  @override
  String get overdue => 'Yamechelewa';

  @override
  String installment(int no) {
    return 'Awamu $no';
  }

  @override
  String get repaymentPosted => 'Rejesho limerekodiwa';

  @override
  String get spending => 'Matumizi ya mwezi huu';

  @override
  String get noSpending => 'Hakuna matumizi mwezi huu';

  @override
  String get dismiss => 'Ondoa';

  @override
  String get messages => 'Ujumbe';

  @override
  String get notifications => 'Arifa';

  @override
  String get newMessage => 'Ujumbe mpya';

  @override
  String get subject => 'Mada';

  @override
  String get message => 'Ujumbe';

  @override
  String get send => 'Tuma';

  @override
  String get sessions => 'Vifaa vilivyoingia';

  @override
  String get thisDevice => 'Kifaa hiki';

  @override
  String authLevel(int level) {
    return 'Nguvu ya kuingia: kiwango $level';
  }

  @override
  String get secondFactors => 'Vipengele vya pili';

  @override
  String get noSecondFactor =>
      'Bado hakuna kipengele cha pili: ongeza kimoja kwenye benki ya mtandaoni chini ya Usalama';

  @override
  String get biometricUnlock => 'Fungua kwa bayometriki';

  @override
  String get biometricUnavailable =>
      'Kufungua kwa bayometriki hakupatikani kwenye kifaa hiki';

  @override
  String get theme => 'Mandhari';

  @override
  String get themeSystem => 'Mfumo';

  @override
  String get themeLight => 'Mwanga';

  @override
  String get themeDark => 'Giza';

  @override
  String get language => 'Lugha';

  @override
  String get offlineBanner => 'Huna mtandao. Inaonyesha kilichopakiwa mwisho.';

  @override
  String get sessionEnded => 'Umetolewa. Tafadhali ingia tena.';

  @override
  String get wallet => 'Pochi';

  @override
  String get request => 'Omba';

  @override
  String get topUp => 'Ongeza muda';

  @override
  String get payBill => 'Lipa bili';

  @override
  String get scanQr => 'Changanua QR';

  @override
  String get payMerchant => 'Lipa mfanyabiashara';

  @override
  String get receive => 'Pokea';

  @override
  String get myQr => 'QR yangu';

  @override
  String get topUpWallet => 'Weka pesa';

  @override
  String get toBank => 'Kwenda benki';

  @override
  String get sendMoney => 'Tuma pesa';

  @override
  String get recipient => 'Namba ya simu au pochi';

  @override
  String get byPhone => 'Simu';

  @override
  String get byWallet => 'Namba ya pochi';

  @override
  String get lookUp => 'Tafuta';

  @override
  String get payee => 'Mpokeaji';

  @override
  String get note => 'Dokezo';

  @override
  String get total => 'Jumla';

  @override
  String get fromWallet => 'Kutoka pochi';

  @override
  String get toWallet => 'Kwenda pochi';

  @override
  String get moneySent => 'Pesa zimetumwa';

  @override
  String get requestMoney => 'Omba pesa';

  @override
  String get incoming => 'Zinazoingia';

  @override
  String get outgoing => 'Zinazotoka';

  @override
  String get decline => 'Kataa';

  @override
  String get declineReason => 'Sababu';

  @override
  String get payNow => 'Lipa';

  @override
  String get requestSent => 'Ombi limetumwa';

  @override
  String get noRequests => 'Hakuna maombi ya pesa';

  @override
  String get topUpAirtime => 'Muda wa maongezi na data';

  @override
  String get operator => 'Mtandao';

  @override
  String get phoneNumber => 'Namba ya simu';

  @override
  String get bundle => 'Kifurushi';

  @override
  String get customAmount => 'Kiasi kingine';

  @override
  String get rechargeToken => 'Tokeni ya kuongeza';

  @override
  String get topUpDone => 'Kuongeza kumewasilishwa';

  @override
  String get payABill => 'Lipa bili';

  @override
  String get biller => 'Mtoa huduma';

  @override
  String get selectBiller => 'Chagua mtoa huduma';

  @override
  String get referenceNumber => 'Namba ya kumbukumbu / akaunti';

  @override
  String get check => 'Angalia';

  @override
  String get amountDue => 'Kiasi kinachodaiwa';

  @override
  String get billPaid => 'Malipo ya bili yamewasilishwa';

  @override
  String get receipt => 'Risiti';

  @override
  String get search => 'Tafuta';

  @override
  String get scanToPay => 'Changanua ulipe';

  @override
  String get qrData => 'Data ya QR';

  @override
  String get pasteQrHint => 'Bandika au andika yaliyomo kwenye QR';

  @override
  String get myQrTitle => 'Msimbo wangu wa QR';

  @override
  String get createQr => 'Tengeneza QR';

  @override
  String get amountOptional => 'Kiasi (si lazima)';

  @override
  String get showThisQr => 'Onyesha msimbo huu ili ulipwe';

  @override
  String get merchantPayTitle => 'Lipa mfanyabiashara';

  @override
  String get tillNumber => 'Namba ya till';

  @override
  String get activate => 'Amilisha';

  @override
  String get activateCard => 'Amilisha kadi';

  @override
  String get cardInactive => 'Haijaamilishwa bado';

  @override
  String get cardActivated => 'Kadi imeamilishwa';

  @override
  String get recent => 'Shughuli za hivi karibuni';

  @override
  String get paymentSent => 'Malipo yametumwa';

  @override
  String get addMoneyToWallet => 'Weka pesa kwenye pochi';

  @override
  String get transferToBank => 'Hamisha kwenda akaunti ya benki';
}
