package io.webagent4j.verification;

import java.util.Objects;

/** Verifies that the current page URL contains an expected fragment. */
public final class UrlContainsVerification implements IVerification {

    private final String expectedFragment;

    /** Creates a URL verification for a non-blank fragment. */
    public UrlContainsVerification(String expectedFragment) {
        Objects.requireNonNull(expectedFragment, "expectedFragment");
        if (expectedFragment.isBlank()) {
            throw new IllegalArgumentException("expectedFragment cannot be blank");
        }
        this.expectedFragment = expectedFragment;
    }

    @Override
    public VerificationType type() {
        return VerificationType.URL_CONTAINS;
    }

    @Override
    public VerificationResult verify(IVerificationContext context) {
        Objects.requireNonNull(context, "context");
        String actual = context.url();
        return new VerificationResult(
                actual.contains(expectedFragment),
                type(),
                "URL contains the expected fragment",
                expectedFragment,
                actual,
                java.time.Duration.ZERO,
                false);
    }
}
