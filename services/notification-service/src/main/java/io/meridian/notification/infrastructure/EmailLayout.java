package io.meridian.notification.infrastructure;

/**
 * Wraps a plain (or already-HTML) notification body in a branded, responsive, email-client-safe HTML layout
 * (table-based, inline CSS, light background, white content card, Meridian header and a security/automated-message
 * footer). Bodies that are already full HTML documents are returned unchanged. This gives every e-mail the platform
 * sends a consistent, professional presentation without each caller having to author HTML.
 */
public final class EmailLayout {
    private EmailLayout() {
    }

    private static final int YEAR = java.time.Year.now().getValue();

    /** True when the body is already a full HTML document / rich markup we should not double-wrap. */
    static boolean isHtml(String body) {
        if (body == null) {
            return false;
        }
        String b = body.toLowerCase(java.util.Locale.ROOT);
        return b.contains("<!doctype") || b.contains("<html") || b.contains("<table")
                || b.contains("<div") || b.contains("<body");
    }

    /** Branded HTML for a plain-text body; already-HTML bodies pass through untouched. */
    public static String wrap(String subject, String body) {
        String text = body == null ? "" : body;
        if (isHtml(text)) {
            return text;
        }
        String title = escape(subject == null || subject.isBlank() ? "Meridian" : subject);
        String preheader = escape(firstLine(text));
        StringBuilder paras = new StringBuilder();
        for (String para : text.strip().split("\\n\\s*\\n")) {
            if (!para.isBlank()) {
                paras.append("<p style=\"margin:0 0 16px;font-size:15px;line-height:1.6;color:#2d3748;\">")
                        .append(escape(para.strip()).replace("\n", "<br>")).append("</p>");
            }
        }
        if (paras.length() == 0) {
            paras.append("<p style=\"margin:0;font-size:15px;line-height:1.6;color:#2d3748;\">")
                    .append(escape(text.strip())).append("</p>");
        }
        return """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <meta name="color-scheme" content="light only">
                <title>%s</title></head>
                <body style="margin:0;padding:0;background:#eef1f5;">
                <span style="display:none;max-height:0;overflow:hidden;opacity:0;">%s</span>
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#eef1f5;padding:24px 12px;">
                  <tr><td align="center">
                    <table role="presentation" width="600" cellpadding="0" cellspacing="0" style="max-width:600px;width:100%%;background:#ffffff;border-radius:14px;overflow:hidden;box-shadow:0 1px 4px rgba(16,39,64,.08);font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;">
                      <tr><td style="background:#0f2740;padding:22px 32px;">
                        <span style="font-size:20px;font-weight:700;letter-spacing:.3px;color:#ffffff;">Meridian</span>
                        <span style="font-size:12px;color:#8fb0c7;margin-left:8px;">Banking</span>
                      </td></tr>
                      <tr><td style="padding:32px;">
                        <h1 style="margin:0 0 18px;font-size:18px;line-height:1.3;color:#0f2740;">%s</h1>
                        %s
                      </td></tr>
                      <tr><td style="padding:18px 32px 28px;border-top:1px solid #eef1f5;">
                        <p style="margin:0 0 6px;font-size:12px;line-height:1.5;color:#8a97a8;">This is an automated message from Meridian. Please do not reply. If you did not expect this e-mail, contact your bank immediately and never share any code or password.</p>
                        <p style="margin:0;font-size:12px;color:#aab4c0;">&copy; %d Meridian. All rights reserved.</p>
                      </td></tr>
                    </table>
                  </td></tr>
                </table></body></html>""".formatted(title, preheader, title, paras, YEAR);
    }

    private static String firstLine(String s) {
        String t = s == null ? "" : s.strip();
        int nl = t.indexOf('\n');
        t = nl > 0 ? t.substring(0, nl) : t;
        return t.length() > 110 ? t.substring(0, 110) : t;
    }

    static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
