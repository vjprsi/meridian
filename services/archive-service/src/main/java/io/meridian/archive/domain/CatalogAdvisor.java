package io.meridian.archive.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the generated data dictionary (the spec catalogue) and suggests a data class and sensitivity per
 * table from its service, name and column markers (PII / secret columns).
 */
public final class CatalogAdvisor {
    private static final Pattern SERVICE = Pattern.compile("^### Service `([^`]+)` — database `([^`]+)`");
    private static final Pattern TABLE = Pattern.compile("^#### `([^`]+)`");
    private static final Set<String> PII_NAMES = Set.of("email", "phone", "first_name", "last_name", "full_name",
            "date_of_birth", "national_id", "address_line1", "address_line2", "tax_id", "passport_number");

    private CatalogAdvisor() {}

    public record Table(String service, String database, String table, List<String> piiColumns, boolean hasSecrets) {}

    public record Suggestion(Table table, String dataClass, String sensitivity) {}

    public static List<Table> parse(String markdown) {
        List<Table> out = new ArrayList<>();
        String service = null;
        String database = null;
        String table = null;
        Set<String> pii = new LinkedHashSet<>();
        boolean secrets = false;
        for (String line : markdown.split("\\r?\\n")) {
            Matcher s = SERVICE.matcher(line);
            Matcher t = TABLE.matcher(line);
            if (s.find() || t.find()) {
                if (table != null) {
                    out.add(new Table(service, database, table, List.copyOf(pii), secrets));
                }
                table = null;
                pii.clear();
                secrets = false;
                if (s.find(0)) {
                    service = s.group(1);
                    database = s.group(2);
                } else if (t.find(0)) {
                    table = t.group(1);
                }
                continue;
            }
            if (table != null && line.startsWith("| ") && !line.startsWith("| Column") && !line.startsWith("| ---")) {
                String[] cells = line.split("\\|");
                if (cells.length > 6) {
                    String column = cells[1].trim();
                    String description = cells[6].trim();
                    if (description.endsWith("PII") || description.contains(" PII ") || PII_NAMES.contains(column)) {
                        pii.add(column);
                    }
                    if (cells[5].contains("—") && (column.contains("secret") || column.contains("hash")
                            || column.contains("key_ref") || column.contains("token"))) {
                        secrets = true;
                    }
                }
            }
        }
        if (table != null) {
            out.add(new Table(service, database, table, List.copyOf(pii), secrets));
        }
        return out;
    }

    public static Suggestion suggest(Table t) {
        String name = (t.service() + " " + t.table()).toLowerCase(Locale.ROOT);
        String dataClass;
        if (name.contains("card-vault") || name.contains("pan") || name.contains("card_number")) {
            dataClass = "CARD";
        } else if (name.contains("kyc") || name.contains("onboarding") || name.contains("identity_document")) {
            dataClass = "KYC";
        } else if (name.contains("audit")) {
            dataClass = "AUDIT";
        } else if (name.contains("notification") || name.contains("inbox") || name.contains("message")
                || name.contains("campaign")) {
            dataClass = "COMMUNICATION";
        } else if (name.contains("journal") || name.contains("ledger") || name.contains("payment")
                || name.contains("transaction") || name.contains("posting") || name.contains("transfer")) {
            dataClass = "TRANSACTION";
        } else if (name.contains("card")) {
            dataClass = "CARD";
        } else if (name.contains("customer") || name.contains("party") || name.contains("crm")) {
            dataClass = "CUSTOMER";
        } else if (name.contains("aml") || name.contains("sanction") || name.contains("fraud") || name.contains("case")) {
            dataClass = "COMPLIANCE";
        } else {
            dataClass = "OPERATIONAL";
        }
        String sensitivity;
        if ("CARD".equals(dataClass) && (name.contains("vault") || name.contains("pan"))) {
            sensitivity = "PCI";
        } else if ("KYC".equals(dataClass) && !t.piiColumns().isEmpty()) {
            sensitivity = "SPECIAL_CATEGORY";
        } else if (!t.piiColumns().isEmpty()) {
            sensitivity = "PII";
        } else if (t.hasSecrets()) {
            sensitivity = "RESTRICTED";
        } else if ("TRANSACTION".equals(dataClass) || "AUDIT".equals(dataClass) || "COMPLIANCE".equals(dataClass)) {
            sensitivity = "CONFIDENTIAL";
        } else {
            sensitivity = "INTERNAL";
        }
        return new Suggestion(t, dataClass, sensitivity);
    }
}
