package io.webagent4j.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.Test;

class UrlContainsVerificationTest {

    @Test
    void reportsMismatchWithoutThrowing() {
        UrlContainsVerification verification = new UrlContainsVerification("/checkout");

        VerificationResult result = verification.verify(context("https://example.test/cart"));

        assertThat(result.success()).isFalse();
        assertThat(result.actual()).endsWith("/cart");
    }

    @Test
    void verifiesConditionsInEncounterOrder() {
        IVerificationContext context = context("https://example.test/checkout");

        List<VerificationResult> results =
                new Verifier()
                        .verifyAll(
                                context,
                                List.of(
                                        new UrlContainsVerification("example.test"),
                                        new UrlContainsVerification("/checkout")));

        assertThat(results).allMatch(VerificationResult::success);
        assertThatIllegalArgumentException().isThrownBy(() -> new UrlContainsVerification(" "));
    }

    private static IVerificationContext context(String url) {
        return new IVerificationContext() {
            @Override
            public String url() {
                return url;
            }

            @Override
            public String title() {
                return "Example";
            }
        };
    }
}
