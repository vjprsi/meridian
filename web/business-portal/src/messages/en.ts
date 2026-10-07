/**
 * English texts of the business portal (the only locale for now; customer-web shows how to add more).
 * Problem codes of the business channel map to a clear headline; the server's `detail` is shown under it.
 */
export const PROBLEM_TITLES: Record<string, string> = {
  not_entitled: "You are not entitled to do this",
  role_not_allowed: "You are not entitled to do this",
  self_approval_forbidden: "You cannot approve a payment you initiated",
  out_of_sequence: "It is not your turn to approve yet",
  already_decided: "This has already been decided",
  daily_limit_exceeded: "This would exceed your daily limit",
  bulk_has_invalid_items: "The batch has invalid lines",
  step_up_required: "Please confirm it is you",
  not_found: "Not found",
  approval_matrix_unsatisfiable: "Your approval rules cannot be met for this",
  technical_role_not_allowed: "API clients can only read or draft",
  application_closed: "The application was already submitted",
  journey_not_available: "This journey is not open to businesses",
  business_user_type_mismatch: "This identity cannot be used here",
};

export const PROBLEM_HINTS: Record<string, string> = {
  not_entitled: "Your role or account entitlements do not allow this action. Ask an administrator of your business.",
  role_not_allowed: "Your role does not allow this action. Ask an administrator of your business.",
  self_approval_forbidden: "Another approver of your business has to approve it (four-eyes principle).",
  out_of_sequence: "The approval rule is sequential: an earlier approver has to decide first.",
  already_decided: "Refresh to see the latest status.",
  daily_limit_exceeded: "Ask an administrator to raise your daily limit, or split the payment.",
  bulk_has_invalid_items: "Fix the lines listed under validation errors and upload the file again.",
  approval_matrix_unsatisfiable: "The rule names you as an approver, or too few other approvers. Ask an administrator to adjust the rule.",
  technical_role_not_allowed: "A technical user of an API client is a MAKER or a VIEWER; approvals and administration stay with people.",
};

export const STATUS_HINTS: Record<number, string> = {
  0: "The bank could not be reached. Check your connection and try again.",
  401: "Your session has expired. Sign in again.",
  403: "You are not allowed to do this.",
  404: "This item was not found.",
  409: "This item changed in the meantime. Refresh and try again.",
  422: "The request could not be processed.",
  429: "Too many requests. Wait a moment and try again.",
};

export const GENERIC_ERROR = "Something went wrong";
export const SERVER_ERROR = "The bank had a problem processing this. Try again shortly.";

export const BULK_CSV_HEADER =
  "creditor_name,creditor_account_number,amount,creditor_account_id_type,creditor_agent_bic,creditor_agent_clearing_id,currency,remittance";
