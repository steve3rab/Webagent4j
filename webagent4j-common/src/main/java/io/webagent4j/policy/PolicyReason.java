package io.webagent4j.policy;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A stable, machine-comparable identifier for why a {@link PolicyDecision} was reached.
 *
 * <p>{@code code} is deliberately not free-form human text: it is validated against a strict
 * grammar so it is safe to render in logs, diagnostics, and decision-provenance traces without risk
 * of embedding secrets, control characters, or multi-line content. Built-in reason codes are
 * centralized as constants (see {@code ActionPolicyReasons} and {@code NetworkPolicyReasons} in
 * {@code webagent4j-action}) rather than duplicated as string literals; callers supplying their own
 * codes for custom policies must still satisfy the same grammar.
 *
 * <p>Values are never silently trimmed or normalized - a code containing leading/trailing
 * whitespace, or any character outside the allowed set, is rejected outright at construction rather
 * than quietly repaired, so a caller never mistakes a rejected value for an accepted one.
 */
public record PolicyReason(String code) {

    /**
     * {@code code} must start with an ASCII letter or digit and contain only ASCII letters, digits,
     * {@code .}, {@code _}, {@code :}, or {@code -} thereafter, up to 128 characters total. No
     * whitespace and no control characters (including CR/LF) are ever permitted.
     */
    private static final Pattern GRAMMAR = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    /** Validates {@code code} against the strict reason-code grammar. */
    public PolicyReason {
        Objects.requireNonNull(code, "code");
        if (!GRAMMAR.matcher(code).matches()) {
            throw new IllegalArgumentException(
                    "reason code must match "
                            + GRAMMAR.pattern()
                            + " (no whitespace, no control characters, 1-128 characters): "
                            + code);
        }
    }

    /** Convenience factory, equivalent to {@code new PolicyReason(code)}. */
    public static PolicyReason of(String code) {
        return new PolicyReason(code);
    }
}
