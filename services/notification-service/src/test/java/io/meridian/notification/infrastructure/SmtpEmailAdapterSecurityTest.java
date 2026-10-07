package io.meridian.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.notification.infrastructure.ChannelAdapter.Message;
import io.meridian.notification.infrastructure.ChannelAdapter.Provider;
import io.meridian.web.outbound.OutboundUrlPolicy;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

/** SEC-18 (tenant SMTP hosts) and SEC-38 (From on the shared relay). */
class SmtpEmailAdapterSecurityTest {
    final JsonMapper json = JsonMapper.builder().build();
    final AtomicReference<MimeMessage> sent = new AtomicReference<>();
    final JavaMailSenderImpl relay = new JavaMailSenderImpl() {
        @Override
        public void send(MimeMessage... messages) {
            sent.set(messages[0]);
        }
    };

    SmtpEmailAdapter adapter() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("mail", relay));
        return new SmtpEmailAdapter(beans.getBeanProvider(JavaMailSender.class),
                OutboundUrlPolicy.fromEnvironment(new MockEnvironment().withProperty("meridian.security-mode", "jwt")),
                List.of("meridian.local", "mail.meridian.example"), List.of());
    }

    Provider provider(String sender, String config) {
        return new Provider(UUID.randomUUID(), "EMAIL", "smtp", sender, json.readTree(config), "ACTIVE");
    }

    Message message() {
        return new Message(UUID.randomUUID(), "EMAIL", "c@example.org", "Hi", "<p>Hi</p>");
    }

    @Test
    void anotherBanksDomainIsNeverTheFromOnTheSharedRelay() throws Exception {
        ChannelAdapter.Result r = adapter().send(provider("alerts@otherbank.com", "{}"), message());
        assertThat(r.status()).isEqualTo("ACCEPTED");
        assertThat(((InternetAddress) sent.get().getFrom()[0]).getAddress()).isEqualTo("no-reply@meridian.local");
        assertThat(((InternetAddress) sent.get().getReplyTo()[0]).getAddress()).isEqualTo("alerts@otherbank.com");

        adapter().send(provider("alerts@mail.meridian.example", "{}"), message());
        assertThat(((InternetAddress) sent.get().getFrom()[0]).getAddress()).isEqualTo("alerts@mail.meridian.example");
    }

    @Test
    void tenantSmtpHostsMustBePublicAndOnASubmissionPort() {
        for (String cfg : List.of("{\"smtp_host\":\"127.0.0.1\",\"smtp_port\":587}",
                "{\"smtp_host\":\"10.0.0.5\",\"smtp_port\":25}", "{\"smtp_host\":\"smtp.example.org\",\"smtp_port\":6379}")) {
            ChannelAdapter.Result r = adapter().send(provider("x@bank.example", cfg), message());
            assertThat(r.status()).as(cfg).isEqualTo("REJECTED");
            assertThat(r.errorCode()).as(cfg).isEqualTo("smtp_host_not_allowed");
        }
    }
}
