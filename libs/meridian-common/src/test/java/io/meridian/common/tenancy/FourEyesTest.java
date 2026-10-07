package io.meridian.common.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import org.junit.jupiter.api.Test;

class FourEyesTest {

    @Test
    void anUnknownMakerFailsClosed() {
        for (String maker : new String[] {null, "", "  "}) {
            assertThatThrownBy(() -> FourEyes.require(maker, "checker", "x")).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.code()).isEqualTo("four_eyes_maker_unknown"));
        }
    }

    @Test
    void theCheckerMustBeAnotherKnownPrincipal() {
        assertThatThrownBy(() -> FourEyes.require("anna", "anna", "x")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("four_eyes_violation"));
        assertThatThrownBy(() -> FourEyes.require("anna", null, "x")).isInstanceOf(ApiException.class);
        assertThatCode(() -> FourEyes.require("anna", "bob", "x")).doesNotThrowAnyException();
    }

    @Test
    void servicePrincipalsAreRecognisedInBothSpellings() {
        assertThat(FourEyes.isService("svc:meridian-service")).isTrue();
        assertThat(FourEyes.isService("service:legacy")).isTrue();
        assertThat(FourEyes.isService("anna")).isFalse();
    }
}
