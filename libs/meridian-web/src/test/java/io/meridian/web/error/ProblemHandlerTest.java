package io.meridian.web.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotAcceptableException;

class ProblemHandlerTest {

    @Test
    void notAcceptableIs406NotAnInternalError() {
        // Found live: GET of a text/plain receipt with Accept: application/json answered 500 internal_error.
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/wallet-transactions/x/receipt");
        ResponseEntity<ProblemDetail> r = new ProblemHandler().notAcceptable(
                new HttpMediaTypeNotAcceptableException(List.of(MediaType.TEXT_PLAIN)), req);
        assertThat(r.getStatusCode().value()).isEqualTo(406);
        assertThat(r.getBody().getProperties()).containsEntry("code", "not_acceptable");
        assertThat(r.getBody().getDetail()).contains("text/plain");
    }
}
