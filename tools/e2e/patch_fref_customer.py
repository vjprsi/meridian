#!/usr/bin/env python3
"""F-REF + F-CTY for customer-service:
 - ExternalServices.requireLegalEntity(id): reject create when legal_entity_id is definitively unknown (404).
 - CustomerService: inject ExternalServices, call it in create(); add ISO-3166 country validation in validateProfile.
Idempotent: re-running is a no-op if already applied."""
import io, sys

def patch(path, olds_news):
    src = io.open(path, encoding="utf-8").read()
    for tag, old, new, guard in olds_news:
        if guard in src:
            print(f"  [{tag}] already applied"); continue
        if old not in src:
            print(f"  [{tag}] ANCHOR NOT FOUND — aborting"); sys.exit(2)
        src = src.replace(old, new, 1)
        print(f"  [{tag}] applied")
    io.open(path, "w", encoding="utf-8").write(src)

EXT = "services/customer-service/src/main/java/io/meridian/customer/infrastructure/ExternalServices.java"
patch(EXT, [(
    "ExternalServices.requireLegalEntity",
    "    public ExternalServices(ServiceClient client) {\n        this.client = client;\n    }\n",
    "    public ExternalServices(ServiceClient client) {\n        this.client = client;\n    }\n"
    "\n"
    "    /**\n"
    "     * Rejects customer creation when legal_entity_id points to a legal entity that does not exist in the\n"
    "     * caller's tenant (referential integrity, F-REF). Only a definitive 404 from tenant-service rejects;\n"
    "     * auth / 5xx / timeout are allowed through so a transient downstream problem never blocks onboarding.\n"
    "     */\n"
    "    public void requireLegalEntity(String legalEntityId) {\n"
    "        if (legalEntityId == null || legalEntityId.isBlank()) {\n"
    "            return;\n"
    "        }\n"
    "        try {\n"
    "            client.get(\"tenant-service\", \"/v1/legal-entities/\" + legalEntityId);\n"
    "        } catch (ApiException e) {\n"
    "            if (e.status() == 404) {\n"
    "                throw ApiException.unprocessable(\"legal_entity_not_found\",\n"
    "                        \"legal_entity_id \" + legalEntityId + \" does not exist\");\n"
    "            }\n"
    "            // transient / auth downstream issue: do not block onboarding on it\n"
    "        }\n"
    "    }\n",
    "requireLegalEntity",
)])

CS = "services/customer-service/src/main/java/io/meridian/customer/application/CustomerService.java"
patch(CS, [
    ("import",
     "import io.meridian.customer.infrastructure.Rows;\n",
     "import io.meridian.customer.infrastructure.ExternalServices;\nimport io.meridian.customer.infrastructure.Rows;\n",
     "import io.meridian.customer.infrastructure.ExternalServices;"),
    ("iso-field",
     "    private static final int AGE_OF_MAJORITY = 18;\n",
     "    private static final int AGE_OF_MAJORITY = 18;\n"
     "    private static final Set<String> ISO_COUNTRIES = Set.of(java.util.Locale.getISOCountries());\n",
     "ISO_COUNTRIES"),
    ("field",
     "    private final ContactService contacts;\n",
     "    private final ContactService contacts;\n    private final ExternalServices external;\n",
     "private final ExternalServices external;"),
    ("ctor",
     "    public CustomerService(Rows rows, CrudEngine crud, Events events, JsonMapper json, Approvals approvals,\n"
     "                           ContactService contacts) {\n"
     "        this.rows = rows;\n"
     "        this.contacts = contacts;\n"
     "        this.crud = crud;\n"
     "        this.events = events;\n"
     "        this.json = json;\n"
     "        this.approvals = approvals;\n"
     "    }\n",
     "    public CustomerService(Rows rows, CrudEngine crud, Events events, JsonMapper json, Approvals approvals,\n"
     "                           ContactService contacts, ExternalServices external) {\n"
     "        this.rows = rows;\n"
     "        this.contacts = contacts;\n"
     "        this.crud = crud;\n"
     "        this.events = events;\n"
     "        this.json = json;\n"
     "        this.approvals = approvals;\n"
     "        this.external = external;\n"
     "    }\n",
     "ContactService contacts, ExternalServices external)"),
    ("create-call",
     "        v.put(\"display_name\", display);\n        validateProfile(v, type);\n",
     "        v.put(\"display_name\", display);\n        validateProfile(v, type);\n"
     "        external.requireLegalEntity(text(v, \"legal_entity_id\"));\n",
     "external.requireLegalEntity(text(v, \"legal_entity_id\"))"),
    ("country-validation",
     "        if (text(v, \"number_of_employees\") != null && v.get(\"number_of_employees\").asInt() < 0) {\n"
     "            errors.add(new FieldError(\"number_of_employees\", \"negative\", \"number_of_employees cannot be negative\"));\n"
     "        }\n",
     "        if (text(v, \"number_of_employees\") != null && v.get(\"number_of_employees\").asInt() < 0) {\n"
     "            errors.add(new FieldError(\"number_of_employees\", \"negative\", \"number_of_employees cannot be negative\"));\n"
     "        }\n"
     "        for (String f : List.of(\"nationality\", \"residence_country\", \"tax_residence_country\")) {\n"
     "            String cc = text(v, f);\n"
     "            if (cc != null && !cc.isBlank() && !ISO_COUNTRIES.contains(cc)) {\n"
     "                errors.add(new FieldError(f, \"invalid_country\", f + \" must be an ISO 3166-1 alpha-2 country code\"));\n"
     "            }\n"
     "        }\n",
     "must be an ISO 3166-1 alpha-2 country code"),
])
print("customer-service F-REF + F-CTY: done")
