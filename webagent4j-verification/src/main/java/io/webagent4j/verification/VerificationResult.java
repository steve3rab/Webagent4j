package io.webagent4j.verification;

import java.util.Objects;

/**
 * Structured result of a deterministic condition check.
 *
 * @param success whether the condition was satisfied
 * @param description condition description
 * @param actual observed value useful for diagnostics
 */
public record VerificationResult(boolean success, String description, String actual) {

    /** Validates verification diagnostics. */
    public VerificationResult {
        description = Objects.requireNonNull(description, "description");
        actual = Objects.requireNonNull(actual, "actual");
    }
}
