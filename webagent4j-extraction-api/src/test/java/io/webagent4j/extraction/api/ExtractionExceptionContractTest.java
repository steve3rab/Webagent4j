package io.webagent4j.extraction.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectOutputStream;
import org.junit.jupiter.api.Test;

class ExtractionExceptionContractTest {

    private static final String SENSITIVE_VALUE = "credential-value-918273";

    @Test
    void extractionExceptionsKeepRawValuesOutOfMessages() {
        ExtractionValidationException validation =
                new ExtractionValidationException(SENSITIVE_VALUE, "must match the policy");
        ExtractionConversionException conversion =
                new ExtractionConversionException(
                        SENSITIVE_VALUE,
                        Integer.class,
                        new NumberFormatException("failure " + SENSITIVE_VALUE));

        assertThat(validation.getMessage()).doesNotContain(SENSITIVE_VALUE);
        assertThat(conversion.getMessage()).doesNotContain(SENSITIVE_VALUE);
        assertThat(validation.value()).isEqualTo(SENSITIVE_VALUE);
        assertThat(conversion.rawValue()).isEqualTo(SENSITIVE_VALUE);
    }

    @Test
    void validationCanDescribeAnExplicitlyRejectedNullWithoutLeakingItIntoTheMessage() {
        ExtractionValidationException failure =
                new ExtractionValidationException(null, "must not be null");

        assertThat(failure.value()).isNull();
        assertThat(failure.getMessage()).isEqualTo("Extracted value failed validation");
    }

    @Test
    void structuredExtractionExceptionsRejectJavaNativeSerialization() {
        assertSerializationRejected(
                new ExtractionValidationException("value", "must match the policy"));
        assertSerializationRejected(
                new ExtractionConversionException(
                        "value", Integer.class, new NumberFormatException("invalid")));
    }

    private static void assertSerializationRejected(Object value) {
        assertThatThrownBy(() -> serialize(value)).isInstanceOf(NotSerializableException.class);
    }

    private static void serialize(Object value) throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
        }
    }
}
