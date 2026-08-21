package io.webagent4j.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WorkflowVariableTest {

    private static final String SECRET_SENTINEL = "WA4J_SUPER_SECRET_982734";

    @Test
    void publicValueRejectsNullValue() {
        WorkflowVariable<String> variable = WorkflowVariable.publicValue("username", String.class);

        assertThatThrownBy(() -> variable.requireValid(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username");
    }

    @Test
    void publicValueRejectsWrongType() {
        WorkflowVariable<Integer> variable = WorkflowVariable.publicValue("count", Integer.class);

        assertThatThrownBy(() -> variable.requireValid("not-an-integer"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publicValueAcceptsCorrectType() {
        WorkflowVariable<Integer> variable = WorkflowVariable.publicValue("count", Integer.class);

        variable.requireValid(42);
    }

    @Test
    void secretVariableIsAlwaysStringTyped() {
        WorkflowVariable<String> variable = WorkflowVariable.secret("password");

        assertThat(variable.type()).isEqualTo(String.class);
        assertThat(variable.secret()).isTrue();
    }

    @Test
    void blankNameRejected() {
        assertThatThrownBy(() -> WorkflowVariable.publicValue("  ", String.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkflowVariable.secret(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalityRequiresSameNameTypeAndSecrecy() {
        WorkflowVariable<String> a = WorkflowVariable.publicValue("x", String.class);
        WorkflowVariable<String> b = WorkflowVariable.publicValue("x", String.class);
        WorkflowVariable<Integer> differentType = WorkflowVariable.publicValue("x", Integer.class);
        WorkflowVariable<String> differentSecrecy = WorkflowVariable.secret("x");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(differentType);
        assertThat(a).isNotEqualTo(differentSecrecy);
    }

    @Test
    void toStringNeverRendersAValue() {
        WorkflowVariable<String> secret = WorkflowVariable.secret("password");

        String rendered = secret.toString();

        assertThat(rendered)
                .doesNotContain(SECRET_SENTINEL)
                .contains("password")
                .contains("secret");
    }
}
