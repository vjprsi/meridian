package io.meridian.document.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DocumentDomainTest {

    /** AWS documentation example "Query string authentication" (GET examplebucket/test.txt, 2013-05-24). */
    @Test
    void sigV4MatchesAwsReferenceVector() {
        SigV4Presigner p = new SigV4Presigner("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1");
        String url = p.presign("GET", "https://examplebucket.s3.amazonaws.com", "/test.txt", Map.of(), 86400,
                Instant.parse("2013-05-24T00:00:00Z"));
        assertThat(url).isEqualTo("https://examplebucket.s3.amazonaws.com/test.txt"
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request"
                + "&X-Amz-Date=20130524T000000Z&X-Amz-Expires=86400&X-Amz-SignedHeaders=host"
                + "&X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404");
    }

    @Test
    void sigV4RejectsExpiryBeyondSevenDays() {
        SigV4Presigner p = new SigV4Presigner("a", "b", "us-east-1");
        assertThatThrownBy(() -> p.presign("GET", "http://localhost:19000", "/b/k", Map.of(), 604_801, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rendererEscapesHtmlAndReportsMissing() {
        var r = TemplateRenderer.render("<p>Dear {{ customer.name }}, balance {{balance}} {{unknown}}</p>",
                Map.of("customer", Map.of("name", "<script>x</script>"), "balance", "10.00"), true);
        assertThat(r.output()).isEqualTo("<p>Dear &lt;script&gt;x&lt;/script&gt;, balance 10.00 </p>");
        assertThat(r.missing()).containsExactly("unknown");
        assertThat(TemplateRenderer.render("a {{x}}", Map.of("x", "<b>"), false).output()).isEqualTo("a <b>");
    }

    @Test
    void rendererRejectsUnsupportedTags() {
        assertThatThrownBy(() -> TemplateRenderer.validate("{{#each items}}{{/each}}")).isInstanceOf(TemplateRenderer.SyntaxError.class);
        assertThatThrownBy(() -> TemplateRenderer.validate("{{ name }")).isInstanceOf(TemplateRenderer.SyntaxError.class);
    }

    @Test
    void lifecyclesAndFileNames() {
        assertThat(Lifecycles.allowed(Lifecycles.ESIGN, "SENT", "SIGNED")).isTrue();
        assertThat(Lifecycles.allowed(Lifecycles.ESIGN, "SIGNED", "CANCELLED")).isFalse();
        assertThat(Lifecycles.allowed(Lifecycles.DOCUMENT, "QUARANTINED", "ARCHIVED")).isFalse();
        assertThat(Lifecycles.safeFileName("../../etc/pass wd.pdf")).isEqualTo("pass_wd.pdf");
    }
}
