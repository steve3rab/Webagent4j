package io.webagent4j.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WorkflowInputsTest {

    private static final String SECRET_SENTINEL = "WA4J_SUPER_SECRET_982734";
    private static final WorkflowVariable<String> USERNAME =
            WorkflowVariable.publicValue("username", String.class);
    private static final WorkflowVariable<String> PASSWORD = WorkflowVariable.secret("password");

    @Test
    void findReturnsSuppliedValue() {
        WorkflowInputs inputs = WorkflowInputs.builder().put(USERNAME, "alice").build();

        assertThat(inputs.find(USERNAME)).contains("alice");
        assertThat(inputs.exists(USERNAME)).isTrue();
    }

    @Test
    void findReturnsEmptyWhenNotSupplied() {
        WorkflowInputs inputs = WorkflowInputs.empty();

        assertThat(inputs.find(USERNAME)).isEmpty();
        assertThat(inputs.exists(USERNAME)).isFalse();
    }

    @Test
    void putRejectsNullValue() {
        WorkflowInputs.Builder builder = WorkflowInputs.builder();

        assertThatThrownBy(() -> builder.put(USERNAME, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void putRejectsConflictingRedeclarationOfSameName() {
        WorkflowInputs.Builder builder = WorkflowInputs.builder().put(USERNAME, "alice");
        WorkflowVariable<Integer> conflicting =
                WorkflowVariable.publicValue("username", Integer.class);

        assertThatThrownBy(() -> builder.put(conflicting, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username");
    }

    @Test
    void toStringMasksSecretValue() {
        WorkflowInputs inputs =
                WorkflowInputs.builder()
                        .put(USERNAME, "alice")
                        .put(PASSWORD, SECRET_SENTINEL)
                        .build();

        String rendered = inputs.toString();

        assertThat(rendered).contains("alice").contains("***").doesNotContain(SECRET_SENTINEL);
    }

    @Test
    void emptyInputsRenderSafely() {
        assertThat(WorkflowInputs.empty().toString()).isEqualTo("WorkflowInputs[]");
    }
}
