package io.meridian.brebsim;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Bre-B key (llave) types, normalisation and validation, and the masked holder name shown before confirmation. */
public final class BrebKeys {
    public static final Set<String> TYPES = Set.of("CELULAR", "CORREO", "DOCUMENTO", "ALFANUMERICA", "COMERCIO");
    private static final Pattern MOBILE = Pattern.compile("3[0-9]{9}");
    private static final Pattern EMAIL = Pattern.compile("[a-z0-9._%+-]+@[a-z0-9-]+(\\.[a-z0-9-]+)+");
    private static final Pattern DOCUMENT = Pattern.compile("[0-9]{5,15}");
    private static final Pattern ALPHA = Pattern.compile("@[a-z0-9._-]{3,20}");
    private static final Pattern MERCHANT = Pattern.compile("[0-9]{5,10}");

    private BrebKeys() {
    }

    /** Normalised key value, or null when the value is not valid for the type. */
    public static String normalise(String type, String value) {
        if (type == null || value == null || !TYPES.contains(type)) {
            return null;
        }
        String v = value.trim();
        return switch (type) {
            case "CELULAR" -> {
                String d = v.replaceAll("[\\s()-]", "");
                if (d.startsWith("+57")) {
                    d = d.substring(3);
                } else if (d.startsWith("57") && d.length() == 12) {
                    d = d.substring(2);
                }
                yield MOBILE.matcher(d).matches() ? d : null;
            }
            case "CORREO" -> {
                String e = v.toLowerCase(Locale.ROOT);
                yield EMAIL.matcher(e).matches() && e.length() <= 92 ? e : null;
            }
            case "DOCUMENTO" -> DOCUMENT.matcher(v).matches() ? v : null;
            case "ALFANUMERICA" -> {
                String a = v.toLowerCase(Locale.ROOT);
                yield ALPHA.matcher(a).matches() ? a : null;
            }
            case "COMERCIO" -> MERCHANT.matcher(v).matches() ? v : null;
            default -> null;
        };
    }

    /** "Juan Carlos Pérez" -> "Jua* Car*** Pér**": enough to confirm the payee, not enough to identify them. */
    public static String mask(String name) {
        if (name == null || name.isBlank()) {
            return "***";
        }
        StringBuilder out = new StringBuilder();
        for (String w : name.trim().split("\\s+")) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            int keep = Math.min(3, w.length());
            out.append(w, 0, keep).append("*".repeat(Math.max(1, w.length() - keep)));
        }
        return out.toString();
    }
}
