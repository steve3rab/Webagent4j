package io.webagent4j.workflow;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkflowExceptionContractTest {

    @Test
    void structuredWorkflowExceptionsRejectJavaNativeSerialization() {
        WorkflowVariable<String> variable = WorkflowVariable.publicValue("account", String.class);
        WorkflowFailure failure =
                new WorkflowFailure(
                        WorkflowFailureType.MISSING_VARIABLE,
                        "Required input is missing",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty());
        WorkflowResult result =
                new WorkflowResult(
                        new WorkflowId("contract-test"),
                        WorkflowStatus.FAILED,
                        List.of(),
                        WorkflowOutputs.empty(),
                        Optional.of(failure));

        assertSerializationRejected(new WorkflowFailedException(result));
        assertSerializationRejected(new WorkflowVariableMissingException(variable));
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
