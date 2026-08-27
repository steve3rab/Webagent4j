package io.webagent4j.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PolicyReasonTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "a",
                "A",
                "0",
                "ACTION_ALLOWED",
                "network.destination.denied",
                "reason:with:colons",
                "reason-with-dashes",
                "reason_with_underscores",
                "MixedCase123.with_all-chars:here"
            })
    void acceptsValidCodes(String code) {
        assertThat(new PolicyReason(code).code()).isEqualTo(code);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> new PolicyReason(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsEmpty() {
        assertThatThrownBy(() -> new PolicyReason("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsLeadingWhitespace() {
        assertThatThrownBy(() -> new PolicyReason(" reason"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTrailingWhitespace() {
        assertThatThrownBy(() -> new PolicyReason("reason "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInternalWhitespace() {
        assertThatThrownBy(() -> new PolicyReason("reason with spaces"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCarriageReturnAndNewline() {
        assertThatThrownBy(() -> new PolicyReason("reason\r\ninjected"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PolicyReason("reason\ninjected"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsLeadingSymbol() {
        assertThatThrownBy(() -> new PolicyReason(".reason"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PolicyReason("-reason"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PolicyReason("_reason"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTooLong() {
        String tooLong = "a".repeat(129);
        assertThatThrownBy(() -> new PolicyReason(tooLong))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsMaximumLength() {
        String maxLength = "a".repeat(128);
        assertThat(new PolicyReason(maxLength).code()).isEqualTo(maxLength);
    }

    @Test
    void doesNotSilentlyTrim() {
        assertThatThrownBy(() -> new PolicyReason("  padded  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ofFactoryIsEquivalentToConstructor() {
        assertThat(PolicyReason.of("some.reason")).isEqualTo(new PolicyReason("some.reason"));
    }
}
