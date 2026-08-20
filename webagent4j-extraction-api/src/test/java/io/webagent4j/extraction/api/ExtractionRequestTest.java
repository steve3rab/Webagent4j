package io.webagent4j.extraction.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.LocatorDefinition;
import org.junit.jupiter.api.Test;

class ExtractionRequestTest {

    private static final LocatorDefinition SOURCE =
            LocatorDefinition.forRole(ElementRole.HEADING).named("Total");

    @Test
    void textFactoryHasNoAttributeNameOrValidatorButAlwaysHasAConverter() {
        ExtractionRequest<String> request = ExtractionRequest.text(SOURCE);

        assertThat(request.readType()).isEqualTo(ExtractionReadType.TEXT);
        assertThat(request.attributeName()).isEmpty();
        assertThat(request.converter()).isNotNull();
        assertThat(request.validator()).isEmpty();
    }

    @Test
    void attributeFactoryCarriesTheRequestedAttributeName() {
        ExtractionRequest<String> request = ExtractionRequest.attribute(SOURCE, "href");

        assertThat(request.readType()).isEqualTo(ExtractionReadType.ATTRIBUTE);
        assertThat(request.attributeName()).contains("href");
    }

    @Test
    void anAttributeRequestWithNoAttributeNameIsRejected() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(
                        () ->
                                new ExtractionRequest<>(
                                        SOURCE,
                                        ExtractionReadType.ATTRIBUTE,
                                        java.util.Optional.empty(),
                                        IValueConverter.identity(),
                                        java.util.Optional.empty()));
    }

    @Test
    void aTextRequestWithAnAttributeNameIsRejected() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(
                        () ->
                                new ExtractionRequest<>(
                                        SOURCE,
                                        ExtractionReadType.TEXT,
                                        java.util.Optional.of("href"),
                                        IValueConverter.identity(),
                                        java.util.Optional.empty()));
    }

    @Test
    void convertReplacesTheResultTypeAndDropsAnyPreviousValidator() {
        ExtractionRequest<String> withValidator =
                ExtractionRequest.text(SOURCE).validate(IExtractionValidator.nonBlank());

        ExtractionRequest<Integer> converted = withValidator.convert(IValueConverter.toInteger());

        assertThat(converted.converter()).isNotNull();
        assertThat(converted.validator()).isEmpty();
    }

    @Test
    void validateAfterConvertKeepsTheConverterAndAddsTheNewValidator() {
        ExtractionRequest<Integer> request =
                ExtractionRequest.text(SOURCE)
                        .convert(IValueConverter.toInteger())
                        .validate(IExtractionValidator.range(0, 100));

        assertThat(request.converter()).isNotNull();
        assertThat(request.validator()).isPresent();
    }

    @Test
    void convertAndValidateAppliesTheConverterThenTheValidatorInOrder() {
        ExtractionRequest<Integer> request =
                ExtractionRequest.text(SOURCE)
                        .convert(IValueConverter.toInteger())
                        .validate(IExtractionValidator.range(0, 100));

        assertThat(request.convertAndValidate("42")).isEqualTo(42);
    }

    @Test
    void convertAndValidateTurnsANullConverterResultIntoAConversionFailure() {
        ExtractionRequest<String> request = ExtractionRequest.text(SOURCE).convert(raw -> null);

        assertThatExceptionOfType(ExtractionConversionException.class)
                .isThrownBy(() -> request.convertAndValidate("anything"))
                .satisfies(failure -> assertThat(failure.rawValue()).isEqualTo("anything"));
    }
}
