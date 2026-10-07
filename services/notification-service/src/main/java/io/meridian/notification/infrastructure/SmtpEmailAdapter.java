package io.meridian.notification.infrastructure;

import jakarta.mail.internet.MimeMessage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * EMAIL over SMTP. Used for EMAIL providers unless their config says {@code "sandbox": true}. The SMTP server comes
 * from the provider config ({@code smtp_host}, {@code smtp_port}, optional {@code smtp_username}; the password is
 * resolved from {@code credentials_ref} by the secret store in production) or, failing that, from the application's
 * {@code spring.mail.*} JavaMailSender. Without either, the sandbox adapter is used.
 */
@Component
@Order(10)
public class SmtpEmailAdapter implements ChannelAdapter {
    /** Submission ports a tenant SMTP relay may use (SEC-18: no arbitrary port scanning through the adapter). */
    static final java.util.Set<Integer> SMTP_PORTS = java.util.Set.of(25, 465, 587, 2525);

    private final ObjectProvider<JavaMailSender> defaultSender;
    private final Map<String, JavaMailSenderImpl> senders = new ConcurrentHashMap<>();
    private final io.meridian.web.outbound.OutboundUrlPolicy policy;
    private final java.util.List<String> platformDomains;

    public SmtpEmailAdapter(ObjectProvider<JavaMailSender> defaultSender, io.meridian.web.outbound.OutboundUrlPolicy policy,
                            @org.springframework.beans.factory.annotation.Value(
                                    "${meridian.notifications.platform-sender-domains:meridian.local}") java.util.List<String> platformDomains) {
        this.defaultSender = defaultSender;
        this.policy = policy;
        this.platformDomains = platformDomains.stream().map(d -> d.trim().toLowerCase(java.util.Locale.ROOT))
                .filter(d -> !d.isEmpty()).toList();
    }

    /**
     * SEC-38: on the shared platform relay a tenant may only send From a verified platform domain
     * ({@code meridian.notifications.platform-sender-domains}); any other sender_id becomes Reply-To and the From is
     * the platform's no-reply address. A tenant's own SMTP relay sends as configured.
     */
    String fromFor(Provider provider, boolean platformRelay) {
        String sender = provider.senderId() == null || provider.senderId().isBlank() ? null : provider.senderId().trim();
        String noReply = "no-reply@" + (platformDomains.isEmpty() ? "meridian.local" : platformDomains.getFirst());
        if (sender == null) {
            return noReply;
        }
        if (!platformRelay) {
            return sender;
        }
        int at = sender.lastIndexOf('@');
        String domain = at < 0 ? "" : sender.substring(at + 1).replace(">", "").trim().toLowerCase(java.util.Locale.ROOT);
        return platformDomains.contains(domain) ? sender : noReply;
    }

    /** SEC-18: a tenant-configured SMTP host must be a public address on a mail submission port. */
    void checkHost(String host, int port) {
        if (!SMTP_PORTS.contains(port)) {
            throw new io.meridian.web.outbound.OutboundUrlPolicy.RejectedException("port_not_allowed",
                    "smtp_port must be one of " + SMTP_PORTS);
        }
        policy.check("https://" + host + ":" + port + "/");
    }

    @Override
    public boolean supports(Provider provider) {
        if (!"EMAIL".equals(provider.channel()) || provider.config().path("sandbox").asBoolean(false)) {
            return false;
        }
        return provider.config().hasNonNull("smtp_host") || defaultSender.getIfAvailable() != null;
    }

    @Override
    public Result send(Provider provider, Message message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("adapter", "smtp");
        String messageId = "<" + UUID.randomUUID() + "@meridian>";
        try {
            boolean platformRelay = !provider.config().hasNonNull("smtp_host");
            JavaMailSender sender = sender(provider);
            MimeMessage mime = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, "UTF-8");
            String from = fromFor(provider, platformRelay);
            helper.setFrom(from);
            if (provider.senderId() != null && !provider.senderId().isBlank() && !from.equals(provider.senderId().trim())) {
                helper.setReplyTo(provider.senderId().trim());
            }
            helper.setTo(message.destination());
            helper.setSubject(message.subject() == null ? "" : message.subject());
            helper.setText(message.body(), true);
            mime.setHeader("X-Meridian-Notification-Id", String.valueOf(message.notificationId()));
            mime.saveChanges();
            mime.setHeader("Message-ID", messageId);
            sender.send(mime);
            response.put("message_id", messageId);
            return new Result("ACCEPTED", messageId, null, response);
        } catch (io.meridian.web.outbound.OutboundUrlPolicy.RejectedException e) {
            response.put("error", "smtp host not allowed");
            return new Result("REJECTED", null, "smtp_host_not_allowed", response);
        } catch (MailParseException | MailPreparationException | MailAuthenticationException e) {
            // SEC-18: an error category only, never the remote server's text (no port-scan / banner oracle)
            response.put("error", e.getClass().getSimpleName());
            return new Result("REJECTED", null, e.getClass().getSimpleName(), response);
        } catch (Exception e) {
            response.put("error", e.getClass().getSimpleName());
            return new Result("ERROR", null, "smtp_error", response);
        }
    }

    private JavaMailSender sender(Provider provider) {
        if (!provider.config().hasNonNull("smtp_host")) {
            return defaultSender.getObject();
        }
        String host = provider.config().get("smtp_host").asString();
        int port = provider.config().path("smtp_port").asInt(25);
        checkHost(host, port);
        String user = provider.config().path("smtp_username").asString(null);
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
        });
    }
}
