package io.webagent4j.locator;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectOutputStream;
import org.junit.jupiter.api.Test;

class LocatorExceptionContractTest {

    @Test
    void structuredLocatorExceptionsRejectJavaNativeSerialization() {
        assertSerializationRejected(new AmbiguousLocatorException("Ambiguous locator"));
        assertSerializationRejected(new LocatorNotFoundException("Locator not found"));
    }

    @Test
    void publicDiagnosticsConstructorsRejectNull() {
        assertThatThrownBy(() -> new AmbiguousLocatorException("Ambiguous locator", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LocatorNotFoundException("Locator not found", null))
                .isInstanceOf(NullPointerException.class);
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
