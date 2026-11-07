#!/usr/bin/env python3
"""F-REF-2 for loan-origination-service: validate customer_id + disbursement/repayment_account_id exist at
loan-application create (it already validates the product version). Reject only on a definitive downstream 404;
auth/5xx/timeout pass through. Idempotent."""
import io, sys

P = "services/loan-origination-service/src/main/java/io/meridian/loanorigination/application/ApplicationService.java"
s = io.open(P, encoding="utf-8").read()

edits = [
    ("import",
     "import io.meridian.loanorigination.infrastructure.ProductCatalogClient;\n",
     "import io.meridian.loanorigination.infrastructure.ProductCatalogClient;\nimport io.meridian.web.client.ServiceClient;\n",
     "import io.meridian.web.client.ServiceClient;"),
    ("field",
     "    private final ProductCatalogClient catalog;\n",
     "    private final ProductCatalogClient catalog;\n    private final ServiceClient serviceClient;\n",
     "private final ServiceClient serviceClient;"),
    ("ctor-sig",
     "    public ApplicationService(Support support, ProductCatalogClient catalog, OriginationProperties props,\n"
     "                              SignatureService signatures, Approvals approvals, OriginationApprovals originationApprovals) {\n",
     "    public ApplicationService(Support support, ProductCatalogClient catalog, OriginationProperties props,\n"
     "                              SignatureService signatures, Approvals approvals, OriginationApprovals originationApprovals,\n"
     "                              ServiceClient serviceClient) {\n",
     "ServiceClient serviceClient) {"),
    ("ctor-body",
     "        this.catalog = catalog;\n        this.props = props;\n",
     "        this.catalog = catalog;\n        this.serviceClient = serviceClient;\n        this.props = props;\n",
     "this.serviceClient = serviceClient;"),
    ("create-checks",
     "        validateRequest(terms, dec(v, \"requested_amount\"), integer(v, \"requested_term_months\"), currency);\n"
     "        v.put(\"product_version_id\", terms.productVersionId().toString());\n",
     "        validateRequest(terms, dec(v, \"requested_amount\"), integer(v, \"requested_term_months\"), currency);\n"
     "        requireRef(\"customer-service\", \"/v1/customers/\", str(v, \"customer_id\"), \"customer_not_found\", \"customer_id\");\n"
     "        requireRef(\"deposit-service\", \"/v1/deposit-accounts/\", str(v, \"disbursement_account_id\"),\n"
     "                \"disbursement_account_not_found\", \"disbursement_account_id\");\n"
     "        requireRef(\"deposit-service\", \"/v1/deposit-accounts/\", str(v, \"repayment_account_id\"),\n"
     "                \"repayment_account_not_found\", \"repayment_account_id\");\n"
     "        v.put(\"product_version_id\", terms.productVersionId().toString());\n",
     "requireRef(\"customer-service\", \"/v1/customers/\""),
    ("helper",
     "    // ------------------------------------------------------------------ intake\n",
     "    /** Rejects a create only on a definitive downstream 404 (the referenced party/account does not exist in\n"
     "     *  this tenant); auth / 5xx / timeout pass through so a transient downstream problem never blocks intake. */\n"
     "    private void requireRef(String service, String prefix, String id, String code, String label) {\n"
     "        if (id == null || id.isBlank()) {\n"
     "            return;\n"
     "        }\n"
     "        try {\n"
     "            serviceClient.get(service, prefix + id);\n"
     "        } catch (ApiException e) {\n"
     "            if (e.status() == 404) {\n"
     "                throw ApiException.unprocessable(code, label + \" \" + id + \" does not exist\");\n"
     "            }\n"
     "        }\n"
     "    }\n\n"
     "    // ------------------------------------------------------------------ intake\n",
     "private void requireRef(String service, String prefix, String id, String code, String label)"),
]
for tag, old, new, guard in edits:
    if guard in s:
        print(f"  [{tag}] already applied"); continue
    if old not in s:
        print(f"  [{tag}] ANCHOR NOT FOUND — aborting"); sys.exit(2)
    s = s.replace(old, new, 1)
    print(f"  [{tag}] applied")
io.open(P, "w", encoding="utf-8").write(s)
print("loan-origination F-REF-2: done")
