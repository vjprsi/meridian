// Models of the customer channel contract (generated/openapi/services/customer-channel-service.openapi.yaml,
// components.schemas). Only the members the app shows are mapped; unknown members are ignored and missing optional
// members are null, so additive contract changes do not break the app. Decimal amounts stay strings.

typedef Json = Map<String, dynamic>;

String _s(Json j, String k, [String fallback = '']) => j[k]?.toString() ?? fallback;
String? _o(Json j, String k) => j[k]?.toString();
DateTime? _t(Json j, String k) => j[k] == null ? null : DateTime.tryParse(j[k].toString());
bool _b(Json j, String k) => j[k] == true;
int _i(Json j, String k) => (j[k] as num?)?.toInt() ?? 0;

/// `{data, next_cursor, has_more}` page of the channel's list operations.
class Page<T> {
  const Page(this.data, {this.nextCursor, this.hasMore = false});

  factory Page.fromJson(Json j, T Function(Json) item) => Page(
        (j['data'] as List? ?? const []).whereType<Json>().map(item).toList(),
        nextCursor: _o(j, 'next_cursor'),
        hasMore: _b(j, 'has_more'),
      );

  final List<T> data;
  final String? nextCursor;
  final bool hasMore;
}

class Customer {
  const Customer({required this.id, required this.customerNumber, this.displayName, this.firstName, this.lastName,
      this.preferredLanguage, required this.kycTier});
  factory Customer.fromJson(Json j) => Customer(
      id: _s(j, 'id'), customerNumber: _s(j, 'customer_number'), displayName: _o(j, 'display_name'),
      firstName: _o(j, 'first_name'), lastName: _o(j, 'last_name'), preferredLanguage: _o(j, 'preferred_language'),
      kycTier: _s(j, 'kyc_tier'));
  final String id;
  final String customerNumber;
  final String? displayName;
  final String? firstName;
  final String? lastName;
  final String? preferredLanguage;
  final String kycTier;

  String get name => displayName ?? [firstName, lastName].whereType<String>().join(' ');
}

class DepositAccount {
  const DepositAccount({required this.id, required this.accountNumber, required this.accountType, required this.currency,
      required this.status, this.nickname, this.iban, required this.rowVersion});
  factory DepositAccount.fromJson(Json j) => DepositAccount(
      id: _s(j, 'id'), accountNumber: _s(j, 'account_number'), accountType: _s(j, 'account_type'),
      currency: _s(j, 'currency'), status: _s(j, 'status'), nickname: _o(j, 'nickname'), iban: _o(j, 'iban'),
      rowVersion: _i(j, 'row_version'));
  final String id;
  final String accountNumber;
  final String accountType;
  final String currency;
  final String status;
  final String? nickname;
  final String? iban;
  final int rowVersion;

  String get label => nickname ?? '$accountType ${accountNumber.length > 4 ? accountNumber.substring(accountNumber.length - 4) : accountNumber}';
  bool get active => status == 'ACTIVE';
}

/// AccountBalance (accounts and wallets).
class Balance {
  const Balance({required this.currency, required this.available, required this.posted});
  factory Balance.fromJson(Json j) =>
      Balance(currency: _s(j, 'currency'), available: _s(j, 'available_balance', '0'), posted: _s(j, 'posted_balance', '0'));
  final String currency;
  final String available;
  final String posted;
}

class LedgerEntry {
  const LedgerEntry({required this.id, required this.direction, required this.amount, required this.currency,
      required this.effectiveAt, this.description, this.counterpartyName, this.reference, required this.balanceAfter});
  factory LedgerEntry.fromJson(Json j) => LedgerEntry(
      id: _s(j, 'id'), direction: _s(j, 'direction'), amount: _s(j, 'amount', '0'), currency: _s(j, 'currency'),
      effectiveAt: _t(j, 'effective_at') ?? DateTime.fromMillisecondsSinceEpoch(0), description: _o(j, 'description'),
      counterpartyName: _o(j, 'counterparty_name'), reference: _o(j, 'reference'),
      balanceAfter: _s(j, 'balance_after', '0'));
  final String id;
  final String direction;
  final String amount;
  final String currency;
  final DateTime effectiveAt;
  final String? description;
  final String? counterpartyName;
  final String? reference;
  final String balanceAfter;

  bool get credit => direction == 'CREDIT';
  String get title => counterpartyName ?? description ?? reference ?? direction;
}

class Wallet {
  const Wallet({required this.id, required this.walletNumber, required this.displayName, required this.currency,
      required this.status, required this.isDefault, this.msisdn});
  factory Wallet.fromJson(Json j) => Wallet(
      id: _s(j, 'id'), walletNumber: _s(j, 'wallet_number'), displayName: _s(j, 'display_name'),
      currency: _s(j, 'currency'), status: _s(j, 'status'), isDefault: _b(j, 'is_default'), msisdn: _o(j, 'msisdn'));
  final String id;
  final String walletNumber;
  final String displayName;
  final String currency;
  final String status;
  final bool isDefault;
  final String? msisdn;
}

class WalletTransaction {
  const WalletTransaction({required this.id, required this.reference, required this.txnType, required this.status,
      required this.amount, required this.currency, required this.createdAt, this.payeeName, this.description,
      this.payerWalletId});
  factory WalletTransaction.fromJson(Json j) => WalletTransaction(
      id: _s(j, 'id'), reference: _s(j, 'reference'), txnType: _s(j, 'txn_type'), status: _s(j, 'status'),
      amount: _s(j, 'amount', '0'), currency: _s(j, 'currency'),
      createdAt: _t(j, 'created_at') ?? DateTime.fromMillisecondsSinceEpoch(0), payeeName: _o(j, 'payee_name'),
      description: _o(j, 'description'), payerWalletId: _o(j, 'payer_wallet_id'));
  final String id;
  final String reference;
  final String txnType;
  final String status;
  final String amount;
  final String currency;
  final DateTime createdAt;
  final String? payeeName;
  final String? description;
  final String? payerWalletId;
}

class PaymentCard {
  const PaymentCard({required this.id, required this.last4, required this.cardholderName, required this.formFactor,
      required this.status, required this.expiryMonth, required this.expiryYear, this.nickname});
  factory PaymentCard.fromJson(Json j) => PaymentCard(
      id: _s(j, 'id'), last4: _s(j, 'last4'), cardholderName: _s(j, 'cardholder_name'),
      formFactor: _s(j, 'form_factor'), status: _s(j, 'status'), expiryMonth: _i(j, 'expiry_month'),
      expiryYear: _i(j, 'expiry_year'), nickname: _o(j, 'nickname'));
  final String id;
  final String last4;
  final String cardholderName;
  final String formFactor;
  final String status;
  final int expiryMonth;
  final int expiryYear;
  final String? nickname;

  bool get frozen => status == 'FROZEN';
  bool get usable => status == 'ACTIVE' || status == 'FROZEN';
  String get expiry => '${expiryMonth.toString().padLeft(2, '0')}/${expiryYear % 100}';
}

class CardControl {
  const CardControl({required this.id, required this.controlType, required this.channel, required this.isActive});
  factory CardControl.fromJson(Json j) => CardControl(
      id: _s(j, 'id'), controlType: _s(j, 'control_type'), channel: _s(j, 'channel'), isActive: _b(j, 'is_active'));
  final String id;
  final String controlType;
  final String channel;
  final bool isActive;
}

class CardSpendLimit {
  const CardSpendLimit({required this.period, required this.channel, this.maxAmount, required this.currency,
      required this.usedAmount, required this.isActive});
  factory CardSpendLimit.fromJson(Json j) => CardSpendLimit(
      period: _s(j, 'period'), channel: _s(j, 'channel'), maxAmount: _o(j, 'max_amount'), currency: _s(j, 'currency'),
      usedAmount: _s(j, 'used_amount', '0'), isActive: _b(j, 'is_active'));
  final String period;
  final String channel;
  final String? maxAmount;
  final String currency;
  final String usedAmount;
  final bool isActive;
}

class Loan {
  const Loan({required this.id, required this.loanNumber, required this.loanType, required this.currency,
      required this.principalAmount, required this.totalOutstanding, required this.overdueAmount,
      required this.interestRate, required this.status, this.nextDueDate, required this.daysPastDue});
  factory Loan.fromJson(Json j) => Loan(
      id: _s(j, 'id'), loanNumber: _s(j, 'loan_number'), loanType: _s(j, 'loan_type'), currency: _s(j, 'currency'),
      principalAmount: _s(j, 'principal_amount', '0'), totalOutstanding: _s(j, 'total_outstanding', '0'),
      overdueAmount: _s(j, 'overdue_amount', '0'), interestRate: _s(j, 'interest_rate', '0'), status: _s(j, 'status'),
      nextDueDate: _o(j, 'next_due_date'), daysPastDue: _i(j, 'days_past_due'));
  final String id;
  final String loanNumber;
  final String loanType;
  final String currency;
  final String principalAmount;
  final String totalOutstanding;
  final String overdueAmount;
  final String interestRate;
  final String status;
  final String? nextDueDate;
  final int daysPastDue;

  bool get repayable => status == 'ACTIVE' || status == 'IN_ARREARS' || status == 'RESTRUCTURED';
}

class ScheduleInstallment {
  const ScheduleInstallment({required this.installmentNo, required this.dueDate, required this.totalDue,
      required this.principalDue, required this.interestDue, required this.status});
  factory ScheduleInstallment.fromJson(Json j) => ScheduleInstallment(
      installmentNo: _i(j, 'installment_no'), dueDate: _s(j, 'due_date'), totalDue: _s(j, 'total_due', '0'),
      principalDue: _s(j, 'principal_due', '0'), interestDue: _s(j, 'interest_due', '0'), status: _s(j, 'status'));
  final int installmentNo;
  final String dueDate;
  final String totalDue;
  final String principalDue;
  final String interestDue;
  final String status;
}

class LoanRepayment {
  const LoanRepayment({required this.id, required this.amount, required this.currency, required this.valueDate,
      required this.status});
  factory LoanRepayment.fromJson(Json j) => LoanRepayment(
      id: _s(j, 'id'), amount: _s(j, 'amount', '0'), currency: _s(j, 'currency'), valueDate: _s(j, 'value_date'),
      status: _s(j, 'status'));
  final String id;
  final String amount;
  final String currency;
  final String valueDate;
  final String status;
}

class Payee {
  const Payee({required this.id, required this.name, this.nickname, required this.accountIdType,
      required this.accountNumber, required this.country, this.currency, this.bankName, required this.status,
      required this.trusted, this.coolingOffUntil, this.verificationResult});
  factory Payee.fromJson(Json j) => Payee(
      id: _s(j, 'id'), name: _s(j, 'name'), nickname: _o(j, 'nickname'), accountIdType: _s(j, 'account_id_type'),
      accountNumber: _s(j, 'account_number'), country: _s(j, 'country'), currency: _o(j, 'currency'),
      bankName: _o(j, 'bank_name'), status: _s(j, 'status'), trusted: _b(j, 'trusted'),
      coolingOffUntil: _t(j, 'cooling_off_until'), verificationResult: _o(j, 'verification_result'));
  final String id;
  final String name;
  final String? nickname;
  final String accountIdType;
  final String accountNumber;
  final String country;
  final String? currency;
  final String? bankName;
  final String status;
  final bool trusted;
  final DateTime? coolingOffUntil;
  final String? verificationResult;

  String get label => nickname ?? name;
}

/// MyPaymentsCreateRequest / MyPaymentsValidateRequest: the server fills in debtor, creditor details and type.
class PaymentRequest {
  const PaymentRequest.own({required this.fromAccountId, required String this.toAccountId, required this.amount, this.reference})
      : kind = 'OWN_TRANSFER',
        payeeId = null;
  const PaymentRequest.payee({required this.fromAccountId, required String this.payeeId, required this.amount, this.reference})
      : kind = 'PAYEE',
        toAccountId = null;

  final String kind;
  final String fromAccountId;
  final String? toAccountId;
  final String? payeeId;
  final String amount;
  final String? reference;

  Json toJson() => {
        'kind': kind,
        'from_account_id': fromAccountId,
        if (toAccountId != null) 'to_account_id': toAccountId,
        if (payeeId != null) 'payee_id': payeeId,
        'amount': amount,
        if (reference != null && reference!.isNotEmpty) 'reference': reference,
        'channel': 'MOBILE',
      };
}

class Payment {
  const Payment({required this.id, required this.status, required this.amount, required this.currency,
      this.creditorName, this.reference, this.feeAmount, required this.createdAt, this.rejectionReason});
  factory Payment.fromJson(Json j) => Payment(
      id: _s(j, 'id'), status: _s(j, 'status'), amount: _s(j, 'amount', '0'), currency: _s(j, 'currency'),
      creditorName: _o(j, 'creditor_name'), reference: _o(j, 'reference'), feeAmount: _o(j, 'fee_amount'),
      createdAt: _t(j, 'created_at') ?? DateTime.fromMillisecondsSinceEpoch(0),
      rejectionReason: _o(j, 'rejection_reason'));
  final String id;
  final String status;
  final String amount;
  final String currency;
  final String? creditorName;
  final String? reference;
  final String? feeAmount;
  final DateTime createdAt;
  final String? rejectionReason;
}

/// GET /v1/me/insights/spending (`object` in the contract; customer-channel-service SpendingByCategory).
class SpendingCategory {
  const SpendingCategory({required this.name, required this.code, required this.currency, required this.spend,
      required this.income, required this.isIncome});
  factory SpendingCategory.fromJson(Json j) => SpendingCategory(
      name: _s(j, 'category_name'), code: _o(j, 'category_code'), currency: _s(j, 'currency'),
      spend: _s(j, 'spend', '0'), income: _s(j, 'income', '0'), isIncome: _b(j, 'is_income'));
  final String name;
  final String? code;
  final String currency;
  final String spend;
  final String income;
  final bool isIncome;
}

class FinancialInsight {
  const FinancialInsight({required this.id, required this.insightType, required this.severity, required this.title,
      required this.body, this.actionLabel});
  factory FinancialInsight.fromJson(Json j) => FinancialInsight(
      id: _s(j, 'id'), insightType: _s(j, 'insight_type'), severity: _s(j, 'severity'), title: _s(j, 'title'),
      body: _s(j, 'body'), actionLabel: _o(j, 'action_label'));
  final String id;
  final String insightType;
  final String severity;
  final String title;
  final String body;
  final String? actionLabel;
}

class MessageThread {
  const MessageThread({required this.id, required this.subject, required this.status, required this.unread,
      this.lastMessageAt, required this.messageCount});
  factory MessageThread.fromJson(Json j) => MessageThread(
      id: _s(j, 'id'), subject: _s(j, 'subject'), status: _s(j, 'status'), unread: _i(j, 'unread_customer'),
      lastMessageAt: _t(j, 'last_message_at'), messageCount: _i(j, 'message_count'));
  final String id;
  final String subject;
  final String status;
  final int unread;
  final DateTime? lastMessageAt;
  final int messageCount;
}

class SecureMessage {
  const SecureMessage({required this.id, required this.senderType, required this.body, required this.createdAt});
  factory SecureMessage.fromJson(Json j) => SecureMessage(
      id: _s(j, 'id'), senderType: _s(j, 'sender_type'), body: _s(j, 'body'),
      createdAt: _t(j, 'created_at') ?? DateTime.fromMillisecondsSinceEpoch(0));
  final String id;
  final String senderType;
  final String body;
  final DateTime createdAt;

  bool get mine => senderType == 'CUSTOMER';
}

class AppNotification {
  const AppNotification({required this.id, this.subject, required this.body, required this.category,
      required this.createdAt, this.readAt});
  factory AppNotification.fromJson(Json j) => AppNotification(
      id: _s(j, 'id'), subject: _o(j, 'subject'), body: _s(j, 'body'), category: _s(j, 'category'),
      createdAt: _t(j, 'created_at') ?? DateTime.fromMillisecondsSinceEpoch(0), readAt: _t(j, 'read_at'));
  final String id;
  final String? subject;
  final String body;
  final String category;
  final DateTime createdAt;
  final DateTime? readAt;
}

class CustomerSession {
  const CustomerSession({required this.id, required this.sessionId, this.deviceLabel, this.userAgent,
      this.ipAddress, required this.lastSeenAt, required this.status, required this.isCurrent, this.acr});
  factory CustomerSession.fromJson(Json j) => CustomerSession(
      id: _s(j, 'id'), sessionId: _s(j, 'session_id'), deviceLabel: _o(j, 'device_label'),
      userAgent: _o(j, 'user_agent'), ipAddress: _o(j, 'last_ip_address') ?? _o(j, 'ip_address'),
      lastSeenAt: _t(j, 'last_seen_at') ?? DateTime.fromMillisecondsSinceEpoch(0), status: _s(j, 'status'),
      isCurrent: _b(j, 'is_current'), acr: _o(j, 'acr'));
  final String id;
  final String sessionId;
  final String? deviceLabel;
  final String? userAgent;
  final String? ipAddress;
  final DateTime lastSeenAt;
  final String status;
  final bool isCurrent;
  final String? acr;

  bool get active => status == 'ACTIVE';
}

/// GET /v1/me/security (`object` in the contract; customer-channel-service SecurityOverview).
class SecurityOverview {
  const SecurityOverview({required this.level, required this.amr, this.hasSecondFactor, required this.factors,
      required this.stepUpMaxAgeSeconds});
  factory SecurityOverview.fromJson(Json j) {
    final auth = (j['authentication'] as Json?) ?? const {};
    final stepUp = (j['step_up'] as Json?) ?? const {};
    return SecurityOverview(
      level: (auth['level'] as num?)?.toInt() ?? 0,
      amr: (auth['amr'] as List? ?? const []).map((e) => e.toString()).toList(),
      hasSecondFactor: j['has_second_factor'] as bool?,
      factors: (j['factors'] as List? ?? const [])
          .whereType<Json>()
          .map((f) => '${f['type'] ?? ''}${f['label'] == null ? '' : ' (${f['label']})'}')
          .toList(),
      stepUpMaxAgeSeconds: (stepUp['max_age_seconds'] as num?)?.toInt() ?? 300,
    );
  }
  final int level;
  final List<String> amr;
  final bool? hasSecondFactor;
  final List<String> factors;
  final int stepUpMaxAgeSeconds;
}
