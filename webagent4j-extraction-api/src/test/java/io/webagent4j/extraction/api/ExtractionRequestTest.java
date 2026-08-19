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
    void textFactoryHasNoAttributeNameConverterOrValidator() {
        ExtractionRequest<String> request = ExtractionRequest.text(SOURCE);

        assertThat(request.readType()).isEqualTo(ExtractionReadType.TEXT);
        assertThat(request.attributeName()).isEmpty();
        assertThat(request.converter()).isEmpty();
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
                                        java.util.Optional.empty(),
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
                                        java.util.Optional.empty(),
                                        java.util.Optional.empty()));
    }

    @Test
    void convertReplacesTheResultTypeAndDropsAnyPreviousValidator() {
        ExtractionRequest<String> withValidator =
                ExtractionRequest.text(SOURCE).validate(IExtractionValidator.nonBlank());

        ExtractionRequest<Integer> converted = withValidator.convert(IValueConverter.toInteger());

        assertThat(converted.converter()).isPresent();
        assertThat(converted.validator()).isEmpty();
    }

    @Test
    void validateAfterConvertKeepsTheConverterAndAddsTheNewValidator() {
        ExtractionRequest<Integer> request =
                ExtractionRequest.text(SOURCE)
                        .convert(IValueConverter.toInteger())
                        .validate(IExtractionValidator.range(0, 100));

        assertThat(request.converter()).isPresent();
        assertThat(request.validator()).isPresent();
    }
}
