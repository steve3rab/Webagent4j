package io.webagent4j.extraction.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class IExtractionValidatorTest {

    @Test
    void nonBlankAcceptsNonBlankText() {
        assertThatCode(() -> IExtractionValidator.nonBlank().validate("Laptop B"))
                .doesNotThrowAnyException();
    }

    @Test
    void nonBlankRejectsBlankText() {
        assertThatExceptionOfType(ExtractionValidationException.class)
                .isThrownBy(() -> IExtractionValidator.nonBlank().validate("   "))
                .satisfies(failure -> assertThat(failure.value()).isEqualTo("   "));
    }

    @Test
    void rangeAcceptsAnInclusiveBoundaryValue() {
        assertThatCode(() -> IExtractionValidator.range(0, 100).validate(100))
                .doesNotThrowAnyException();
    }

    @Test
    void rangeRejectsAValueOutsideTheBounds() {
        assertThatExceptionOfType(ExtractionValidationException.class)
                .isThrownBy(() -> IExtractionValidator.range(0, 100).validate(101));
    }

    @Test
    void rangeRejectsAnInvertedBoundsDeclarationEagerly() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> IExtractionValidator.range(100, 0));
    }

    @Test
    void matchesAcceptsAFullPatternMatch() {
        assertThatCode(
                        () ->
                                IExtractionValidator.matches(Pattern.compile("[A-Z]{2}\\d{4}"))
                                        .validate("AB1234"))
                .doesNotThrowAnyException();
    }

    @Test
    void matchesRejectsAPartialMatch() {
        assertThatExceptionOfType(ExtractionValidationException.class)
                .isThrownBy(
                        () ->
                                IExtractionValidator.matches(Pattern.compile("\\d+"))
                                        .validate("abc123"));
    }

    @Test
    void predicateRejectsAValueFailingTheSuppliedPredicateWithItsDescription() {
        assertThatExceptionOfType(ExtractionValidationException.class)
                .isThrownBy(
                        () ->
                                IExtractionValidator.<Integer>predicate(
                                                value -> value % 2 == 0, "must be even")
                                        .validate(3))
                .satisfies(failure -> assertThat(failure.description()).isEqualTo("must be even"));
    }
}
