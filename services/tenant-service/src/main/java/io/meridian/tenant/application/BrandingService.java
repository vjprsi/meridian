package io.meridian.tenant.application;

import io.meridian.common.error.ApiException;
import io.meridian.tenant.domain.Validators;
import io.meridian.tenant.infrastructure.TenantStore;
import io.meridian.web.crud.CrudEngine;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** White-label branding validation and the rendered preview of a branding on sample screens. */
@Service
public class BrandingService {
    /** WCAG AA minimum contrast for normal text. */
    private static final double MIN_CONTRAST = 4.5;

    private final TenantStore store;
    private final CrudEngine crud;
    private final JsonMapper json;

    public BrandingService(TenantStore store, CrudEngine crud, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.json = json;
    }

    /** Validates a create or patch body (merged with the current row for patches). */
    public void validate(Object body, UUID existingId) {
        ObjectNode v = crud.toColumns("tenant_branding", body);
        ObjectNode merged = existingId == null ? v : store.require("tenant_branding", existingId).deepCopy().setAll(v);
        for (String f : new String[] {"primary_color", "secondary_color", "accent_color"}) {
            if (merged.hasNonNull(f) && !Validators.isHexColor(merged.get(f).asString())) {
                throw ApiException.unprocessable("invalid_color", f + " must be a hex colour such as #1F3864");
            }
        }
        if (merged.hasNonNull("supported_locales")) {
            JsonNode locales = merged.get("supported_locales");
            if (locales.isObject()) {
                locales = locales.path("locales"); // contract types json columns as objects: {"locales":[...]}
            }
            if (!locales.isArray()) {
                throw ApiException.unprocessable("invalid_locales",
                        "supported_locales must be {\"locales\":[BCP 47 tags]} or an array");
            }
            boolean found = false;
            for (JsonNode l : locales) {
                if (l.asString().equals(merged.path("default_locale").asString("en"))) {
                    found = true;
                }
            }
            if (!found) {
                throw ApiException.unprocessable("invalid_locales", "default_locale must be one of supported_locales");
            }
        }
        if (merged.hasNonNull("sms_sender_id") && !merged.get("sms_sender_id").asString().matches("[A-Za-z0-9 ]{1,11}")) {
            throw ApiException.unprocessable("invalid_sms_sender_id", "sms_sender_id must be 1-11 alphanumeric characters");
        }
        for (String f : new String[] {"terms_url", "privacy_url"}) {
            if (merged.hasNonNull(f) && !merged.get(f).asString().startsWith("https://")) {
                throw ApiException.unprocessable("invalid_url", f + " must be an https URL");
            }
        }
        if (v.hasNonNull("legal_entity_id")
                && store.find("legal_entity", UUID.fromString(v.get("legal_entity_id").asString())).isEmpty()) {
            throw ApiException.unprocessable("legal_entity_not_found", "Legal entity does not exist");
        }
    }

    /** Resolved design tokens, sample screens and accessibility checks for a branding. */
    public JsonNode preview(UUID id) {
        ObjectNode b = store.require("tenant_branding", id);
        String primary = b.get("primary_color").asString();
        String secondary = b.path("secondary_color").asString(primary);
        String accent = b.path("accent_color").asString(secondary);
        String onPrimary = Validators.contrastRatio(primary, "#FFFFFF") >= Validators.contrastRatio(primary, "#000000")
                ? "#FFFFFF" : "#000000";
        String font = b.path("font_family").asString("Inter");
        String appName = b.get("app_name").asString();

        ObjectNode out = json.createObjectNode();
        out.put("branding_id", id.toString());
        out.put("channel", b.get("channel").asString());
        ObjectNode tokens = out.putObject("tokens");
        tokens.put("color.primary", primary);
        tokens.put("color.on_primary", onPrimary);
        tokens.put("color.secondary", secondary);
        tokens.put("color.accent", accent);
        tokens.put("font.family", font);
        tokens.put("locale.default", b.path("default_locale").asString("en"));
        if (b.hasNonNull("theme_tokens") && b.get("theme_tokens").isObject()) {
            b.get("theme_tokens").properties().forEach(e -> tokens.set(e.getKey(), e.getValue()));
        }

        ArrayNode screens = out.putArray("screens");
        screens.add(screen("login", "Welcome to " + appName, primary, onPrimary, font,
                "Sign in", b.path("logo_document_id").asString(null)));
        screens.add(screen("home", appName + " - Accounts", secondary, onPrimary, font, "Transfer", null));
        ObjectNode email = screen("email", "Your " + appName + " statement is ready", primary, onPrimary, font,
                "View statement", b.path("logo_document_id").asString(null));
        email.put("from", b.path("email_sender_name").asString(appName) + " <"
                + b.path("email_sender_address").asString("no-reply@example.com") + ">");
        email.put("footer", "Support: " + b.path("support_email").asString("-") + " | Terms: " + b.path("terms_url").asString("-"));
        screens.add(email);
        ObjectNode sms = json.createObjectNode();
        sms.put("screen", "sms");
        sms.put("sender", b.path("sms_sender_id").asString(appName.replaceAll("[^A-Za-z0-9]", "")
                .substring(0, Math.min(11, appName.replaceAll("[^A-Za-z0-9]", "").length()))));
        sms.put("text", appName + ": your one-time code is 123456");
        screens.add(sms);

        ObjectNode a11y = out.putObject("accessibility");
        double ratio = Validators.contrastRatio(primary, onPrimary);
        a11y.put("primary_contrast_ratio", Math.round(ratio * 100) / 100.0);
        a11y.put("wcag_aa", ratio >= MIN_CONTRAST);
        ArrayNode warnings = out.putArray("warnings");
        if (ratio < MIN_CONTRAST) {
            warnings.add(String.format(Locale.ROOT, "Primary colour contrast %.2f:1 is below WCAG AA 4.5:1", ratio));
        }
        if (!b.hasNonNull("logo_document_id")) {
            warnings.add("No logo configured; the app name is shown instead");
        }
        if (!b.hasNonNull("privacy_url")) {
            warnings.add("No privacy policy URL configured");
        }
        return out;
    }

    private ObjectNode screen(String name, String title, String background, String foreground, String font,
                              String cta, String logo) {
        ObjectNode s = json.createObjectNode();
        s.put("screen", name);
        s.put("title", title);
        s.put("background", background);
        s.put("foreground", foreground);
        s.put("font_family", font);
        s.put("call_to_action", cta);
        if (logo != null) {
            s.put("logo_document_id", logo);
        }
        return s;
    }
}
