// ignore: unused_import
import 'package:intl/intl.dart' as intl;

import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Swahili (`sw`).
class AppLocalizationsSw extends AppLocalizations {
  AppLocalizationsSw([String locale = 'sw']) : super(locale);

  @override
  String get appTitle => 'Meridian Wakala';

  @override
  String get signIn => 'Ingia';

  @override
  String get signInWithBank => 'Ingia kwa Meridian';

  @override
  String get devModeHint =>
      'Hali ya majaribio: huduma za ndani katika hali ya usalama ya majaribio';

  @override
  String get tenant => 'Mpangaji';

  @override
  String get agentId => 'Kitambulisho cha wakala';

  @override
  String get username => 'Jina la mtumiaji';

  @override
  String get userId => 'Kitambulisho cha mtumiaji';

  @override
  String get setupTitle => 'Sanidi kifaa hiki';

  @override
  String get chooseOutlet => 'Kituo';

  @override
  String get appPin => 'PIN ya programu';

  @override
  String get appPinHint =>
      'Tarakimu 6; inalinda kazi iliyohifadhiwa kwenye kifaa hiki';

  @override
  String get confirmPin => 'Rudia PIN ya programu';

  @override
  String get pinMismatch => 'PIN hazilingani';

  @override
  String get pinInvalid => 'Weka tarakimu 6';

  @override
  String get finishSetup => 'Sajili kifaa';

  @override
  String get unlockTitle => 'Fungua';

  @override
  String get unlock => 'Fungua';

  @override
  String get wrongPin => 'PIN si sahihi';

  @override
  String get lock => 'Funga';

  @override
  String get signOut => 'Toka';

  @override
  String get online => 'Mtandaoni';

  @override
  String get offline => 'Nje ya mtandao';

  @override
  String pendingCount(int count) {
    return '$count zinasubiri kutumwa';
  }

  @override
  String get float => 'Floti';

  @override
  String get cashIn => 'Kuweka pesa';

  @override
  String get cashOut => 'Kutoa pesa';

  @override
  String get billPay => 'Kulipa bili';

  @override
  String get floatRequest => 'Ombi la floti';

  @override
  String get history => 'Historia';

  @override
  String get sync => 'Sawazisha';

  @override
  String get customerPhone => 'Nambari ya simu ya mteja';

  @override
  String get amount => 'Kiasi';

  @override
  String get lookup => 'Tafuta mteja';

  @override
  String customerFound(String name) {
    return 'Mteja: $name';
  }

  @override
  String get customerNotFound => 'Hakuna pochi kwa nambari hii';

  @override
  String get confirm => 'Thibitisha';

  @override
  String get cancel => 'Ghairi';

  @override
  String get completed => 'Imekamilika';

  @override
  String get queued => 'Imehifadhiwa kwenye kifaa; itatumwa ukiwa mtandaoni';

  @override
  String rejected(String reason) {
    return 'Imekataliwa: $reason';
  }

  @override
  String get agentPin => 'PIN ya pochi ya wakala';

  @override
  String get agentPinHint =>
      'Inahifadhiwa kwenye kumbukumbu kwa kipindi hiki tu';

  @override
  String get customerPin => 'PIN ya mteja';

  @override
  String get otp => 'Msimbo wa mara moja';

  @override
  String get cashOutStarted =>
      'Mwombe mteja athibitishe kwa PIN au msimbo wake';

  @override
  String get biller => 'Mtoa bili';

  @override
  String get accountReference => 'Kumbukumbu ya akaunti';

  @override
  String get customerName => 'Jina la mteja';

  @override
  String get requestType => 'Aina ya ombi';

  @override
  String get bankReference => 'Kumbukumbu ya benki';

  @override
  String get syncNow => 'Sawazisha sasa';

  @override
  String syncResult(int synced, int remaining) {
    return '$synced zimetumwa, $remaining bado zinasubiri';
  }

  @override
  String get nothingPending => 'Hakuna kinachosubiri';

  @override
  String get remove => 'Ondoa';

  @override
  String get statusPending => 'Inasubiri';

  @override
  String get statusSynced => 'Imetumwa';

  @override
  String get statusRejected => 'Imekataliwa';

  @override
  String get statusNeedsReview => 'Inahitaji ukaguzi';

  @override
  String get required => 'Inahitajika';

  @override
  String get invalidAmount => 'Weka kiasi sahihi';

  @override
  String get retry => 'Jaribu tena';

  @override
  String get errorGeneric => 'Hitilafu imetokea';

  @override
  String get empty => 'Hakuna kitu bado';

  @override
  String get onDevice => 'Kwenye kifaa hiki';

  @override
  String get onServer => 'Miamala iliyokamilika';

  @override
  String get offlineNotAllowed => 'Kutoa pesa kunahitaji mtandao';

  @override
  String blocked(String reason) {
    return 'Imesimama: $reason';
  }
}
