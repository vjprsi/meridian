package io.meridian.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class BodySizeFilterTest {

    @Test
    void declaredOversizedBodiesAreRefusedWith413() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/x");
        req.setContent(new byte[2048]);
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();
        new BodySizeFilter(1024).doFilter(req, res, (rq, rs) -> called.set(true));
        assertThat(res.getStatus()).isEqualTo(413);
        assertThat(res.getContentAsString()).contains("payload_too_large");
        assertThat(called).isFalse();
    }

    @Test
    void bodiesWithoutALengthFailOncePastTheCap() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/x") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        req.setContent(new byte[2048]);
        new BodySizeFilter(1024).doFilter(req, new MockHttpServletResponse(), (rq, rs) ->
                assertThatThrownBy(() -> rq.getInputStream().readAllBytes()).isInstanceOf(IOException.class));
        MockHttpServletRequest small = new MockHttpServletRequest("POST", "/v1/x");
        small.setContent(new byte[10]);
        new BodySizeFilter(1024).doFilter(small, new MockHttpServletResponse(), (rq, rs) ->
                assertThat(rq.getInputStream().readAllBytes()).hasSize(10));
    }
}
