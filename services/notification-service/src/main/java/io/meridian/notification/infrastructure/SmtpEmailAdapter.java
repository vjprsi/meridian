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
    private final ObjectProvider<JavaMailSender> defaultSender;
    private final Map<String, JavaMailSenderImpl> senders = new ConcurrentHashMap<>();

    public SmtpEmailAdapter(ObjectProvider<JavaMailSender> defaultSender) {
        this.defaultSender = defaultSender;
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
            JavaMailSender sender = sender(provider);
            MimeMessage mime = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, "UTF-8");
            String from = provider.senderId() == null || provider.senderId().isBlank()
                    ? "no-reply@meridian.local" : provider.senderId();
            helper.setFrom(from);
            helper.setTo(message.destination());
            helper.setSubject(message.subject() == null ? "" : message.subject());
            helper.setText(message.body(), true);
            mime.setHeader("X-Meridian-Notification-Id", String.valueOf(message.notificationId()));
            mime.saveChanges();
            mime.setHeader("Message-ID", messageId);
            sender.send(mime);
            response.put("message_id", messageId);
            return new Result("ACCEPTED", messageId, null, response);
        } catch (MailParseException | MailPreparationException | MailAuthenticationException e) {
            response.put("error", String.valueOf(e.getMessage()));
            return new Result("REJECTED", null, e.getClass().getSimpleName(), response);
        } catch (Exception e) {
            response.put("error", String.valueOf(e.getMessage()));
            return new Result("ERROR", null, "smtp_error", response);
        }
    }

    private JavaMailSender sender(Provider provider) {
        if (!provider.config().hasNonNull("smtp_host")) {
            return defaultSender.getObject();
        }
        String host = provider.config().get("smtp_host").asString();
        int port = provider.config().path("smtp_port").asInt(25);
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
