#!/usr/bin/env python3
"""F-REF for deposit-service: wire CustomerClient into DepositAccountService.open() so opening an account
for a nonexistent customer is rejected (422 customer_not_found). Idempotent."""
import io, sys

P = "services/deposit-service/src/main/java/io/meridian/deposit/application/DepositAccountService.java"
src = io.open(P, encoding="utf-8").read()

edits = [
    ("import",
     "import io.meridian.deposit.infrastructure.ProductCatalogClient;\n",
     "import io.meridian.deposit.infrastructure.CustomerClient;\nimport io.meridian.deposit.infrastructure.ProductCatalogClient;\n",
     "import io.meridian.deposit.infrastructure.CustomerClient;"),
    ("field",
     "    private final AccountNumbering numbering;\n",
     "    private final AccountNumbering numbering;\n    private final CustomerClient customers;\n",
     "private final CustomerClient customers;"),
    ("ctor-sig",
     "                                 DepositProperties props, ProductCatalogClient catalog, Approvals approvals,\n"
     "                                 AccountNumbering numbering) {\n",
     "                                 DepositProperties props, ProductCatalogClient catalog, Approvals approvals,\n"
     "                                 AccountNumbering numbering, CustomerClient customers) {\n",
     "AccountNumbering numbering, CustomerClient customers)"),
    ("ctor-body",
     "        this.catalog = catalog;\n    }\n",
     "        this.catalog = catalog;\n        this.customers = customers;\n    }\n",
     "this.customers = customers;"),
    ("open-call",
     "        String currency = str(v, \"currency\");\n"
     "        if (currency == null || !currency.matches(\"[A-Z]{3}\") || !validCurrency(currency)) {\n"
     "            throw ApiException.unprocessable(\"invalid_currency\", \"currency must be an ISO 4217 code\");\n"
     "        }\n",
     "        String currency = str(v, \"currency\");\n"
     "        if (currency == null || !currency.matches(\"[A-Z]{3}\") || !validCurrency(currency)) {\n"
     "            throw ApiException.unprocessable(\"invalid_currency\", \"currency must be an ISO 4217 code\");\n"
     "        }\n"
     "        customers.requireExists(str(v, \"customer_id\"));\n",
     "customers.requireExists(str(v, \"customer_id\"));"),
]
for tag, old, new, guard in edits:
    if guard in src:
        print(f"  [{tag}] already applied"); continue
    if old not in src:
        print(f"  [{tag}] ANCHOR NOT FOUND — aborting"); sys.exit(2)
    src = src.replace(old, new, 1)
    print(f"  [{tag}] applied")
io.open(P, "w", encoding="utf-8").write(src)
print("deposit-service F-REF: done")
