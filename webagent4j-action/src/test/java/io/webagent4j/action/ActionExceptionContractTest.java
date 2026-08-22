package io.webagent4j.action;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ActionExceptionContractTest {

    @Test
    void structuredActionExceptionRejectsJavaNativeSerialization() {
        ActionFailure failure =
                new ActionFailure(
                        ActionFailureType.BACKEND_FAILURE, "Action failed", Optional.empty());
        ActionResult<Void> result =
                new ActionResult<>(false, null, Duration.ZERO, List.of(), Optional.of(failure));
        ActionFailedException exception = new ActionFailedException(result);

        assertThatThrownBy(() -> serialize(exception))
                .isInstanceOf(NotSerializableException.class)
                .hasMessageContaining(ActionFailedException.class.getName());
    }

    private static void serialize(Object value) throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
        }
    }
}
