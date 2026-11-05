import type { FormSection, FormValues } from "@/components/entity-form";
import type { DetailSection } from "@/components/detail";

/** cards.yaml enums (kept in sync with spec/domains/cards.yaml). */
export const CARD_SCHEME = ["VISA", "MASTERCARD", "AMEX", "UNIONPAY", "JCB", "DISCOVER", "RUPAY", "ELO", "LOCAL"];
export const CARD_FUNDING_MODEL = ["DEBIT", "PREPAID", "CREDIT", "JIT"];
export const CARD_PROGRAM_TYPE = ["CONSUMER", "BUSINESS", "CORPORATE", "EXPENSE", "GIFT", "PAYROLL", "GOVERNMENT"];
export const CARD_FRAUD_POLICY = ["FAIL_OPEN", "FAIL_CLOSED"];
export const CARD_FORM_FACTOR = ["VIRTUAL", "PHYSICAL"];
export const CARD_LINKED_ACCOUNT_TYPE = ["DEPOSIT_ACCOUNT", "WALLET", "CREDIT_LINE", "PREPAID_LEDGER", "JIT_PROGRAM"];
export const CARD_SHIPPING_METHOD = ["STANDARD", "EXPRESS", "COURIER", "BRANCH_PICKUP"];

const isPhysical = (v: FormValues) => v.form_factor === "PHYSICAL";
const linksDeposit = (v: FormValues) => v.linked_account_type === "DEPOSIT_ACCOUNT";
const linksWallet = (v: FormValues) => v.linked_account_type === "WALLET" || v.linked_account_type === "PREPAID_LEDGER";
const linksCredit = (v: FormValues) => v.linked_account_type === "CREDIT_LINE";

/** The full card-program configuration — every writable column on card_program (json fields excluded). */
export const cardProgramFormSections: FormSection[] = [
  {
    title: "Program",
    description: "Identity and the scheme relationship. Programs start as DRAFT, go through scheme certification and are activated by a second user.",
    fields: [
      { key: "code", label: "Program code", type: "text", required: true, hint: "Short unique code" },
      { key: "name", label: "Program name", type: "text", required: true, span: 2 },
      { key: "legal_entity_id", label: "Issuing legal entity", type: "text", required: true, hint: "Bank entity holding the BIN" },
      { key: "scheme", label: "Card scheme", type: "select", options: CARD_SCHEME, required: true },
      { key: "program_type", label: "Program segment", type: "select", options: CARD_PROGRAM_TYPE },
      { key: "funding_model", label: "Funding model", type: "select", options: CARD_FUNDING_MODEL, required: true, hint: "How authorisations are funded" },
    ],
  },
  {
    title: "BIN & account range",
    description: "Issuer BIN and the PAN account range the program allocates numbers from.",
    fields: [
      { key: "bin", label: "Issuer BIN", type: "text", required: true, placeholder: "6 or 8 digits" },
      { key: "bin_range_start", label: "Range start", type: "text", required: true, hint: "First PAN prefix (starts with the BIN)" },
      { key: "bin_range_end", label: "Range end", type: "text", required: true, hint: "Last PAN prefix (same length as the start)" },
      { key: "pan_length", label: "PAN length", type: "number", hint: "13–19, including the Luhn check digit" },
      { key: "currency", label: "Billing currency", type: "currency", required: true },
      { key: "country", label: "Issuing country", type: "country", hint: "A merchant elsewhere makes the transaction international" },
    ],
  },
  {
    title: "Sponsorship & settlement",
    fields: [
      { key: "sponsor_bank", label: "Sponsor / BIN sponsor bank", type: "text", span: 2 },
      { key: "processor_member_id", label: "Scheme member / ICA / BID", type: "text" },
      { key: "ledger_account_id", label: "Program settlement account", type: "text", hint: "JIT holds are placed here" },
    ],
  },
  {
    title: "JIT funding & stand-in",
    description: "Just-in-time funding callout and the behaviour when the core ledger is unavailable.",
    fields: [
      { key: "jit_webhook_url", label: "JIT webhook URL", type: "text", span: 2, hint: "Client JIT funding endpoint" },
      { key: "jit_timeout_ms", label: "JIT timeout (ms)", type: "number", placeholder: "1500" },
      { key: "stand_in_enabled", label: "Stand-in enabled", type: "bool", hint: "Approve under stand-in rules when the core is unavailable" },
      { key: "stand_in_max_amount", label: "Stand-in max amount", type: "amount", hint: "Maximum amount approved in stand-in" },
    ],
  },
  {
    title: "Fraud & FX",
    fields: [
      { key: "fraud_policy", label: "Fraud policy", type: "select", options: CARD_FRAUD_POLICY, hint: "Decision when fraud scoring times out or fails" },
      { key: "fraud_timeout_ms", label: "Fraud timeout (ms)", type: "number", placeholder: "80" },
      { key: "fraud_decline_score", label: "Fraud decline score", type: "number", hint: "Decline at or above this value (0–1000)" },
      { key: "fx_markup_rate", label: "FX markup rate", type: "number", placeholder: "0.02 = 2%" },
    ],
  },
  {
    title: "Authorisation lifecycle",
    fields: [
      { key: "auth_hold_days", label: "Auth hold days", type: "number", hint: "Days before an uncleared hold expires" },
      { key: "renewal_window_days", label: "Renewal window days", type: "number", hint: "Days before expiry a card is reported expiring" },
    ],
  },
];

/** The full card-issuance field set — every writable column on card (plus request-only shipping / initial PIN). */
export const cardIssueFormSections: FormSection[] = [
  {
    title: "Cardholder & product",
    description: "The cardholder, the program and product the card is issued on, and how it is embossed. A virtual card is issued ACTIVE instantly; the PAN is generated from the program's account range and stored only in the vault.",
    fields: [
      { key: "card_program_id", label: "Card program", type: "text", required: true },
      { key: "card_product_id", label: "Card product", type: "text", required: true },
      { key: "customer_id", label: "Cardholder customer", type: "text", required: true },
      { key: "business_customer_id", label: "Business customer", type: "text", hint: "Company for business / corporate cards" },
      { key: "cardholder_name", label: "Cardholder name", type: "text", required: true, span: 2, hint: "Name as embossed (max 26 characters)" },
      { key: "form_factor", label: "Form factor", type: "select", options: CARD_FORM_FACTOR, required: true },
      { key: "nickname", label: "Nickname", type: "text", hint: "Cardholder nickname" },
    ],
  },
  {
    title: "Funding",
    description: "The ledger account authorisations hold and post against, plus the funding account for the program's funding model.",
    fields: [
      { key: "linked_account_type", label: "Linked account type", type: "select", options: CARD_LINKED_ACCOUNT_TYPE, required: true },
      { key: "ledger_account_id", label: "Ledger account", type: "text", required: true, hint: "Authorisations hold and post here" },
      { key: "deposit_account_id", label: "Deposit account", type: "text", when: linksDeposit, hint: "Debit cards" },
      { key: "wallet_id", label: "Wallet", type: "text", when: linksWallet, hint: "Prepaid / wallet cards" },
      { key: "credit_line_id", label: "Credit line", type: "text", when: linksCredit, hint: "Credit cards" },
    ],
  },
  {
    title: "Physical delivery",
    description: "Shipping details for a physical card (ignored for virtual cards).",
    fields: [
      { key: "shipping_method", label: "Shipping method", type: "select", options: CARD_SHIPPING_METHOD, when: isPhysical },
      { key: "ship_recipient_name", label: "Recipient name", type: "text", when: isPhysical },
      { key: "ship_phone", label: "Recipient phone", type: "phone", when: isPhysical, hint: "Contact phone for the courier" },
      { key: "ship_line1", label: "Address line 1", type: "text", span: 2, when: isPhysical },
      { key: "ship_line2", label: "Address line 2", type: "text", span: 2, when: isPhysical },
      { key: "ship_city", label: "City", type: "text", when: isPhysical },
      { key: "ship_region", label: "State / region", type: "text", when: isPhysical },
      { key: "ship_postal_code", label: "Postal code", type: "text", when: isPhysical },
      { key: "ship_country", label: "Country", type: "country", when: isPhysical },
    ],
  },
  {
    title: "Initial PIN (advanced)",
    description: "Optional: set the initial PIN at issuance as an ISO 9564 format 0 PIN block wrapped under a zone key. Handled only by the vault and never logged.",
    fields: [
      { key: "encrypted_pin_block", label: "Encrypted PIN block", type: "text", span: 2, hint: "AES-GCM wrapped, base64" },
      { key: "pin_key_id", label: "PIN key id", type: "text", hint: "Zone key / HSM label the block is wrapped under" },
    ],
  },
];

/**
 * Folds the flat shipping_* inputs from the issue form into the nested `shipping_address` object
 * the card-service expects, and drops the flat keys. Everything else is posted as-is.
 */
export function buildCardIssueBody(body: Record<string, unknown>): Record<string, unknown> {
  const map: Record<string, string> = {
    ship_recipient_name: "recipient_name",
    ship_line1: "address_line1",
    ship_line2: "address_line2",
    ship_city: "city",
    ship_region: "region",
    ship_postal_code: "postal_code",
    ship_country: "country",
    ship_phone: "phone",
  };
  const out: Record<string, unknown> = { ...body };
  const addr: Record<string, unknown> = {};
  for (const [k, target] of Object.entries(map)) {
    if (out[k] !== undefined && out[k] !== "") addr[target] = out[k];
    delete out[k];
  }
  if (Object.keys(addr).length > 0) out.shipping_address = addr;
  return out;
}

type C = Record<string, unknown>;

/** The full card-program record, grouped into sections, for the detail page. */
export function cardProgramDetailSections(p: C): DetailSection[] {
  return [
    {
      title: "Program",
      rows: [
        { label: "Code", value: p.code, kind: "mono" },
        { label: "Name", value: p.name },
        { label: "Scheme", value: p.scheme, kind: "badge" },
        { label: "Funding model", value: p.funding_model, kind: "badge" },
        { label: "Segment", value: p.program_type, kind: "badge" },
        { label: "Status", value: p.status, kind: "badge" },
        { label: "Status reason", value: p.status_reason },
        { label: "Legal entity", value: p.legal_entity_id, kind: "mono" },
      ],
    },
    {
      title: "BIN & account range",
      rows: [
        { label: "BIN", value: p.bin, kind: "mono" },
        { label: "Range start", value: p.bin_range_start, kind: "mono" },
        { label: "Range end", value: p.bin_range_end, kind: "mono" },
        { label: "PAN length", value: p.pan_length },
        { label: "Billing currency", value: p.currency },
        { label: "Issuing country", value: p.country },
      ],
    },
    {
      title: "Sponsorship & settlement",
      rows: [
        { label: "Sponsor bank", value: p.sponsor_bank },
        { label: "Scheme member / ICA / BID", value: p.processor_member_id, kind: "mono" },
        { label: "Settlement account", value: p.ledger_account_id, kind: "mono" },
      ],
    },
    {
      title: "JIT funding & stand-in",
      rows: [
        { label: "JIT webhook", value: p.jit_webhook_url },
        { label: "JIT timeout (ms)", value: p.jit_timeout_ms },
        { label: "Stand-in enabled", value: p.stand_in_enabled, kind: "bool" },
        { label: "Stand-in max", value: p.stand_in_max_amount, kind: "money", currency: p.currency as string },
      ],
    },
    {
      title: "Fraud & FX",
      rows: [
        { label: "Fraud policy", value: p.fraud_policy, kind: "badge" },
        { label: "Fraud timeout (ms)", value: p.fraud_timeout_ms },
        { label: "Fraud decline score", value: p.fraud_decline_score },
        { label: "FX markup rate", value: p.fx_markup_rate },
      ],
    },
    {
      title: "Authorisation lifecycle",
      rows: [
        { label: "Auth hold days", value: p.auth_hold_days },
        { label: "Renewal window days", value: p.renewal_window_days },
        { label: "Certified", value: p.certified_at, kind: "datetime" },
        { label: "Launched", value: p.launched_at, kind: "datetime" },
        { label: "Activated by", value: p.activated_by },
        { label: "Created", value: p.created_at, kind: "datetime" },
      ],
    },
  ];
}

/** The full card record, grouped into sections, for the detail page. */
export function cardDetailSections(c: C): DetailSection[] {
  return [
    {
      title: "Card",
      rows: [
        { label: "BIN", value: c.bin, kind: "mono" },
        { label: "Last four", value: c.last4, kind: "mono" },
        { label: "Expiry month", value: c.expiry_month },
        { label: "Expiry year", value: c.expiry_year },
        { label: "Sequence", value: c.sequence_number },
        { label: "Form factor", value: c.form_factor, kind: "badge" },
        { label: "Status", value: c.status, kind: "badge" },
        { label: "Status reason", value: c.status_reason },
        { label: "Cardholder name", value: c.cardholder_name },
        { label: "Nickname", value: c.nickname },
      ],
    },
    {
      title: "Linkage",
      rows: [
        { label: "Program", value: c.card_program_id, kind: "mono" },
        { label: "Product", value: c.card_product_id, kind: "mono" },
        { label: "Customer", value: c.customer_id, kind: "mono" },
        { label: "Business customer", value: c.business_customer_id, kind: "mono" },
        { label: "Linked account type", value: c.linked_account_type, kind: "badge" },
        { label: "Ledger account", value: c.ledger_account_id, kind: "mono" },
        { label: "Deposit account", value: c.deposit_account_id, kind: "mono" },
        { label: "Wallet", value: c.wallet_id, kind: "mono" },
        { label: "Credit line", value: c.credit_line_id, kind: "mono" },
        { label: "Replaces card", value: c.replaces_card_id, kind: "mono" },
      ],
    },
    {
      title: "PIN & lifecycle",
      rows: [
        { label: "PIN set", value: c.pin_set, kind: "bool" },
        { label: "Failed PIN tries", value: c.pin_try_count },
        { label: "Activated", value: c.activated_at, kind: "datetime" },
        { label: "Frozen", value: c.frozen_at, kind: "datetime" },
        { label: "Closed", value: c.closed_at, kind: "datetime" },
        { label: "Issued", value: c.created_at, kind: "datetime" },
      ],
    },
  ];
}
