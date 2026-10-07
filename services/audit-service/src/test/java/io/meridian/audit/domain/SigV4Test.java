package io.meridian.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SigV4Test {

    /** AWS documentation example "GET Object" (Signature Version 4, header-based authentication). */
    @Test
    void matchesTheAwsReferenceSignature() {
        SigV4 signer = new SigV4("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Host", "examplebucket.s3.amazonaws.com");
        headers.put("Range", "bytes=0-9");
        Map<String, String> signed = signer.sign("GET", "/test.txt", headers, SigV4.EMPTY_SHA256,
                Instant.parse("2013-05-24T00:00:00Z"));
        assertThat(signed.get("authorization")).isEqualTo("AWS4-HMAC-SHA256 "
                + "Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, "
                + "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, "
                + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41");
        assertThat(signed.get("x-amz-date")).isEqualTo("20130524T000000Z");
    }

    @Test
    void pathSegmentsAreEncoded() {
        assertThat(SigV4.encodePath("/bucket/audit/a b+c/2026/10/07/events.jsonl"))
                .isEqualTo("/bucket/audit/a%20b%2Bc/2026/10/07/events.jsonl");
    }
}
