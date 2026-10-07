import 'api_client.dart';
import 'models.dart';

/// Typed client of the customer channel (`/v1/me/*`, customer-channel-service). Every operation acts for the
/// signed-in customer only: the server takes the customer from the token, answers 404 for objects of anyone else and
/// rebuilds request bodies itself. Writes carry an Idempotency-Key; money movement and security changes need step-up
/// (401 step_up_required, handled by AuthController.withStepUp).
///
/// Hand-written against generated/openapi/services/customer-channel-service.openapi.yaml; [operations] lists every
/// (method, path) used and test/api_contract_test.dart checks each one exists in that contract.
class CustomerApi {
  CustomerApi(this.client);
  final ApiClient client;

  static const operations = <(String, String)>[
    ('GET', '/v1/me/profile'),
    ('GET', '/v1/me/accounts'),
    ('GET', '/v1/me/accounts/{account_id}/balance'),
    ('GET', '/v1/me/accounts/{account_id}/transactions'),
    ('GET', '/v1/me/wallets'),
    ('GET', '/v1/me/wallets/{wallet_id}/balance'),
    ('GET', '/v1/me/wallets/{wallet_id}/transactions'),
    ('GET', '/v1/me/cards'),
    ('GET', '/v1/me/cards/{card_id}'),
    ('POST', '/v1/me/cards/{card_id}/freeze'),
    ('POST', '/v1/me/cards/{card_id}/unfreeze'),
    ('POST', '/v1/me/cards/{card_id}/report-lost'),
    ('GET', '/v1/me/cards/{card_id}/controls'),
    ('PUT', '/v1/me/cards/{card_id}/channels/{channel}'),
    ('GET', '/v1/me/cards/{card_id}/limits'),
    ('PUT', '/v1/me/cards/{card_id}/limits'),
    ('GET', '/v1/me/loans'),
    ('GET', '/v1/me/loans/{loan_id}'),
    ('GET', '/v1/me/loans/{loan_id}/schedule'),
    ('GET', '/v1/me/loans/{loan_id}/repayments'),
    ('POST', '/v1/me/loans/{loan_id}/repay'),
    ('GET', '/v1/me/payees'),
    ('POST', '/v1/me/payees'),
    ('DELETE', '/v1/me/payees/{payee_id}'),
    ('POST', '/v1/me/payees/{payee_id}/trust'),
    ('GET', '/v1/me/payments'),
    ('POST', '/v1/me/payments/validate'),
    ('POST', '/v1/me/payments'),
    ('GET', '/v1/me/insights/spending'),
    ('GET', '/v1/me/insights/cards'),
    ('POST', '/v1/me/insights/cards/{insight_id}/dismiss'),
    ('GET', '/v1/me/inbox/threads'),
    ('POST', '/v1/me/inbox/threads'),
    ('GET', '/v1/me/inbox/threads/{thread_id}/messages'),
    ('POST', '/v1/me/inbox/threads/{thread_id}/messages'),
    ('POST', '/v1/me/inbox/threads/{thread_id}/read'),
    ('GET', '/v1/me/notifications'),
    ('GET', '/v1/me/sessions'),
    ('POST', '/v1/me/sessions/{session_id}/revoke'),
    ('POST', '/v1/me/sessions/revoke-all'),
    ('GET', '/v1/me/security'),
  ];

  static String _e(String id) => Uri.encodeComponent(id);

  Future<Page<T>> _page<T>(String path, T Function(Json) item, {Map<String, Object?>? query}) async =>
      Page.fromJson(await client.get(path, query: {'limit': 100, ...?query}) as Json, item);

  // Profile -------------------------------------------------------------------------------------------------------
  Future<Customer> profile() async => Customer.fromJson(await client.get('/v1/me/profile') as Json);

  // Accounts ------------------------------------------------------------------------------------------------------
  Future<List<DepositAccount>> accounts() async => (await _page('/v1/me/accounts', DepositAccount.fromJson)).data;

  Future<Balance> accountBalance(String id) async =>
      Balance.fromJson(await client.get('/v1/me/accounts/${_e(id)}/balance') as Json);

  Future<Page<LedgerEntry>> accountTransactions(String id, {String? cursor}) =>
      _page('/v1/me/accounts/${_e(id)}/transactions', LedgerEntry.fromJson, query: {'limit': 50, 'cursor': cursor});

  // Wallets -------------------------------------------------------------------------------------------------------
  Future<List<Wallet>> wallets() async => (await _page('/v1/me/wallets', Wallet.fromJson)).data;

  Future<Balance> walletBalance(String id) async =>
      Balance.fromJson(await client.get('/v1/me/wallets/${_e(id)}/balance') as Json);

  Future<Page<WalletTransaction>> walletTransactions(String id, {String? cursor}) => _page(
      '/v1/me/wallets/${_e(id)}/transactions', WalletTransaction.fromJson,
      query: {'limit': 50, 'cursor': cursor});

  // Cards ---------------------------------------------------------------------------------------------------------
  Future<List<PaymentCard>> cards() async => (await _page('/v1/me/cards', PaymentCard.fromJson)).data;

  Future<PaymentCard> card(String id) async => PaymentCard.fromJson(await client.get('/v1/me/cards/${_e(id)}') as Json);

  Future<PaymentCard> freezeCard(String id) async =>
      PaymentCard.fromJson(await client.write('POST', '/v1/me/cards/${_e(id)}/freeze') as Json);

  /// Step-up.
  Future<PaymentCard> unfreezeCard(String id, {String? idempotencyKey}) async => PaymentCard.fromJson(
      await client.write('POST', '/v1/me/cards/${_e(id)}/unfreeze', idempotencyKey: idempotencyKey) as Json);

  Future<PaymentCard> reportLost(String id, {required String reason, bool replace = false}) async =>
      PaymentCard.fromJson(await client.write('POST', '/v1/me/cards/${_e(id)}/report-lost',
          body: {'reason': reason, 'replace': replace}) as Json);

  Future<List<CardControl>> cardControls(String id) async =>
      (await _page('/v1/me/cards/${_e(id)}/controls', CardControl.fromJson)).data;

  /// Blocks or allows a channel (ECOM, CONTACTLESS, ATM, POS).
  Future<CardControl> setCardChannel(String id, String channel, {required bool blocked}) async => CardControl.fromJson(
      await client.write('PUT', '/v1/me/cards/${_e(id)}/channels/${_e(channel)}', body: {'blocked': blocked}) as Json);

  Future<List<CardSpendLimit>> cardLimits(String id) async =>
      (await _page('/v1/me/cards/${_e(id)}/limits', CardSpendLimit.fromJson)).data;

  /// Step-up.
  Future<CardSpendLimit> setCardLimit(String id,
          {required String period, String channel = 'ANY', required String maxAmount, String? currency,
          String? idempotencyKey}) async =>
      CardSpendLimit.fromJson(await client.write('PUT', '/v1/me/cards/${_e(id)}/limits',
          idempotencyKey: idempotencyKey,
          body: {'period': period, 'channel': channel, 'max_amount': maxAmount, 'currency': ?currency}) as Json);

  // Loans ---------------------------------------------------------------------------------------------------------
  Future<List<Loan>> loans() async => (await _page('/v1/me/loans', Loan.fromJson)).data;

  Future<Loan> loan(String id) async => Loan.fromJson(await client.get('/v1/me/loans/${_e(id)}') as Json);

  Future<List<ScheduleInstallment>> loanSchedule(String id) async =>
      (await _page('/v1/me/loans/${_e(id)}/schedule', ScheduleInstallment.fromJson, query: {'limit': 200})).data;

  Future<List<LoanRepayment>> loanRepayments(String id) async =>
      (await _page('/v1/me/loans/${_e(id)}/repayments', LoanRepayment.fromJson)).data;

  /// Step-up; one Idempotency-Key per confirmation.
  Future<LoanRepayment> repayLoan(String id,
          {required String amount, required String sourceAccountId, required String idempotencyKey}) async =>
      LoanRepayment.fromJson(await client.write('POST', '/v1/me/loans/${_e(id)}/repay',
          idempotencyKey: idempotencyKey, body: {'amount': amount, 'source_account_id': sourceAccountId}) as Json);

  // Payees --------------------------------------------------------------------------------------------------------
  Future<List<Payee>> payees() async =>
      (await _page('/v1/me/payees', Payee.fromJson)).data.where((p) => p.status != 'DELETED').toList();

  /// Step-up.
  Future<Payee> addPayee(
          {required String name,
          String? nickname,
          required String accountIdType,
          required String accountNumber,
          required String country,
          String? currency,
          String? bankName,
          String? idempotencyKey}) async =>
      Payee.fromJson(await client.write('POST', '/v1/me/payees', idempotencyKey: idempotencyKey, body: {
        'name': name,
        if (nickname != null && nickname.isNotEmpty) 'nickname': nickname,
        'account_id_type': accountIdType,
        'account_number': accountNumber,
        'country': country,
        'currency': ?currency,
        if (bankName != null && bankName.isNotEmpty) 'bank_name': bankName,
      }) as Json);

  Future<void> deletePayee(String id) => client.write('DELETE', '/v1/me/payees/${_e(id)}');

  /// Step-up.
  Future<Payee> trustPayee(String id, {String? idempotencyKey}) async => Payee.fromJson(
      await client.write('POST', '/v1/me/payees/${_e(id)}/trust', idempotencyKey: idempotencyKey) as Json);

  // Payments ------------------------------------------------------------------------------------------------------
  Future<List<Payment>> payments({String? accountId}) async =>
      (await _page('/v1/me/payments', Payment.fromJson, query: {'limit': 50, 'account_id': accountId})).data;

  /// Checks a payment before confirmation (account, payee, route, cut-off, fees, Confirmation of Payee).
  Future<Json> validatePayment(PaymentRequest r) async =>
      (await client.write('POST', '/v1/me/payments/validate', body: r.toJson()) as Json?) ?? const {};

  /// Step-up; [idempotencyKey] is held for the whole confirmation so a resubmit cannot pay twice.
  Future<Payment> pay(PaymentRequest r, {required String idempotencyKey}) async => Payment.fromJson(
      await client.write('POST', '/v1/me/payments', body: r.toJson(), idempotencyKey: idempotencyKey) as Json);

  // Insights ------------------------------------------------------------------------------------------------------
  Future<List<SpendingCategory>> spending({required DateTime from, required DateTime to, String? currency}) async {
    final j = await client.get('/v1/me/insights/spending', query: {
      'from': from.toUtc().toIso8601String(),
      'to': to.toUtc().toIso8601String(),
      'currency': currency,
    }) as Json;
    return (j['categories'] as List? ?? const []).whereType<Json>().map(SpendingCategory.fromJson).toList();
  }

  Future<List<FinancialInsight>> insightCards() async =>
      (await _page('/v1/me/insights/cards', FinancialInsight.fromJson)).data;

  Future<void> dismissInsight(String id) => client.write('POST', '/v1/me/insights/cards/${_e(id)}/dismiss');

  // Inbox ---------------------------------------------------------------------------------------------------------
  Future<List<MessageThread>> threads() async =>
      (await _page('/v1/me/inbox/threads', MessageThread.fromJson)).data;

  Future<MessageThread> startThread({required String subject, required String body}) async =>
      MessageThread.fromJson(
          await client.write('POST', '/v1/me/inbox/threads', body: {'subject': subject, 'initial_message': body}) as Json);

  Future<List<SecureMessage>> messages(String threadId) async =>
      (await _page('/v1/me/inbox/threads/${_e(threadId)}/messages', SecureMessage.fromJson)).data;

  Future<SecureMessage> sendMessage(String threadId, String body, {String? clientMessageId}) async =>
      SecureMessage.fromJson(await client.write('POST', '/v1/me/inbox/threads/${_e(threadId)}/messages',
          body: {'body': body, 'client_message_id': ?clientMessageId}) as Json);

  Future<void> markThreadRead(String threadId) => client.write('POST', '/v1/me/inbox/threads/${_e(threadId)}/read');

  Future<List<AppNotification>> notifications() async =>
      (await _page('/v1/me/notifications', AppNotification.fromJson, query: {'limit': 50})).data;

  // Security ------------------------------------------------------------------------------------------------------
  Future<List<CustomerSession>> sessions() async => (await _page('/v1/me/sessions', CustomerSession.fromJson)).data;

  Future<CustomerSession> revokeSession(String sessionId) async => CustomerSession.fromJson(await client
      .write('POST', '/v1/me/sessions/${_e(sessionId)}/revoke', body: {'reason': 'Signed out from the mobile app'}) as Json);

  /// Signs out every session including this one (the app then signs out locally).
  Future<void> revokeAllSessions({bool includeCurrent = true}) =>
      client.write('POST', '/v1/me/sessions/revoke-all', body: {'include_current': includeCurrent});

  Future<SecurityOverview> security() async => SecurityOverview.fromJson(await client.get('/v1/me/security') as Json);
}
