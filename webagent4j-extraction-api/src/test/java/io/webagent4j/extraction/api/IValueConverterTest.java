package io.webagent4j.extraction.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;

class IValueConverterTest {

    @Test
    void identityReturnsTheRawStringUnchanged() {
        assertThat(IValueConverter.identity().convert("Laptop B")).isEqualTo("Laptop B");
    }

    @Test
    void toIntegerParsesABase10Integer() {
        assertThat(IValueConverter.toInteger().convert("42")).isEqualTo(42);
    }

    @Test
    void toIntegerRejectsNonNumericTextWithTheRawValueAndTargetTypeRetained() {
        assertThatExceptionOfType(ExtractionConversionException.class)
                .isThrownBy(() -> IValueConverter.toInteger().convert("not a number"))
                .satisfies(
                        failure -> {
                            assertThat(failure.rawValue()).isEqualTo("not a number");
                            assertThat(failure.targetType()).isEqualTo(Integer.class);
                            assertThat(failure.getCause()).isNotNull();
                        });
    }

    @Test
    void toLongParsesABase10Long() {
        assertThat(IValueConverter.toLong().convert("9999999999")).isEqualTo(9_999_999_999L);
    }

    @Test
    void toBigDecimalParsesAnExactDecimalWithNoBinaryRounding() {
        assertThat(IValueConverter.toBigDecimal().convert("19.99"))
                .isEqualTo(new BigDecimal("19.99"));
    }

    @Test
    void toBooleanAcceptsExactlyTrueOrFalseCaseInsensitively() {
        assertThat(IValueConverter.toBoolean().convert("true")).isTrue();
        assertThat(IValueConverter.toBoolean().convert("FALSE")).isFalse();
    }

    @Test
    void toBooleanRejectsAHeuristicValueLikeYesInsteadOfGuessing() {
        assertThatExceptionOfType(ExtractionConversionException.class)
                .isThrownBy(() -> IValueConverter.toBoolean().convert("yes"));
    }

    @Test
    void toLocalDateParsesIso8601ByDefault() {
        assertThat(IValueConverter.toLocalDate().convert("2026-08-19"))
                .isEqualTo(LocalDate.of(2026, 8, 19));
    }

    @Test
    void toLocalDateUsesAnExplicitFormatWhenSupplied() {
        IValueConverter<LocalDate> converter =
                IValueConverter.toLocalDate(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        assertThat(converter.convert("19/08/2026")).isEqualTo(LocalDate.of(2026, 8, 19));
    }

    @Test
    void toLocalDateRejectsAMismatchedFormat() {
        assertThatExceptionOfType(ExtractionConversionException.class)
                .isThrownBy(() -> IValueConverter.toLocalDate().convert("19/08/2026"));
    }
}
