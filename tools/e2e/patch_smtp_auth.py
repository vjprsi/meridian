#!/usr/bin/env python3
"""Enable authenticated STARTTLS/SSL SMTP in SmtpEmailAdapter so real relays (AWS SES, SendGrid, ...) work.
The password is resolved from an ENV var named by the provider's credentials_ref (secret never stored in the DB
or returned by the provider API); a dev-only smtp_password config field is a fallback. AUTH + STARTTLS (587) or
implicit TLS (465) are enabled only when a username is present, so the existing no-auth Mailpit provider is
unchanged. Idempotent."""
import io, sys

P = "services/notification-service/src/main/java/io/meridian/notification/infrastructure/SmtpEmailAdapter.java"
s = io.open(P, encoding="utf-8").read()

OLD = '''        String user = provider.config().path("smtp_username").asString(null);
        return senders.computeIfAbsent(host + ":" + port + ":" + user, k -> {
            JavaMailSenderImpl s = new JavaMailSenderImpl();
            s.setHost(host);
            s.setPort(port);
            if (user != null) {
                s.setUsername(user);
            }
            Properties p = new Properties();
            p.put("mail.smtp.connectiontimeout", "3000");
            p.put("mail.smtp.timeout", "5000");
            p.put("mail.smtp.writetimeout", "5000");
            s.setJavaMailProperties(p);
            return s;
        });'''

NEW = '''        String user = provider.config().path("smtp_username").asString(null);
        String pass = resolveSmtpPassword(provider.config());
        boolean auth = user != null && pass != null && !pass.isBlank();
        String cacheKey = host + ":" + port + ":" + user + ":" + (auth ? Integer.toHexString(pass.hashCode()) : "noauth");
        return senders.computeIfAbsent(cacheKey, k -> {
            JavaMailSenderImpl s = new JavaMailSenderImpl();
            s.setHost(host);
            s.setPort(port);
            if (user != null) {
                s.setUsername(user);
            }
            if (auth) {
                s.setPassword(pass);
            }
            Properties p = new Properties();
            p.put("mail.smtp.connectiontimeout", "3000");
            p.put("mail.smtp.timeout", "5000");
            p.put("mail.smtp.writetimeout", "5000");
            if (auth) {
                // authenticated relay (AWS SES etc.): AUTH + TLS. 465 = implicit TLS, else STARTTLS (587/25/2525).
                p.put("mail.smtp.auth", "true");
                if (port == 465) {
                    p.put("mail.smtp.ssl.enable", "true");
                } else {
                    p.put("mail.smtp.starttls.enable", "true");
                    p.put("mail.smtp.starttls.required", "true");
                }
            }
            s.setJavaMailProperties(p);
            return s;
        });
    }

    /**
     * SMTP password for an authenticated relay. Resolved from the ENV var named by {@code credentials_ref}
     * (so the secret lives in the service environment / secret store, never in the provider row or its API
     * response); {@code smtp_password} in the config is a dev-only fallback. Null for an unauthenticated relay.
     */
    private static String resolveSmtpPassword(tools.jackson.databind.JsonNode config) {
        String ref = config.path("credentials_ref").asString(null);
        if (ref != null && !ref.isBlank()) {
            String fromEnv = System.getenv(ref);
            if (fromEnv != null && !fromEnv.isBlank()) {
                return fromEnv;
            }
        }
        String inline = config.path("smtp_password").asString(null);
        return inline == null || inline.isBlank() ? null : inline;'''

if "resolveSmtpPassword" in s:
    print("already patched"); sys.exit(0)
if OLD not in s:
    print("ANCHOR NOT FOUND"); sys.exit(2)
io.open(P, "w", encoding="utf-8").write(s.replace(OLD, NEW, 1))
print("patched SmtpEmailAdapter: authenticated STARTTLS/SSL SMTP via credentials_ref env (SES-ready)")
