package io.webagent4j.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkflowConditionsTest {

    private static final String SECRET_SENTINEL = "WA4J_SUPER_SECRET_982734";
    private static final WorkflowVariable<String> NAME =
            WorkflowVariable.publicValue("name", String.class);
    private static final WorkflowVariable<Boolean> FLAG =
            WorkflowVariable.publicValue("flag", Boolean.class);
    private static final WorkflowVariable<String> PASSWORD = WorkflowVariable.secret("password");

    /** Minimal in-memory {@link IWorkflowVariables} for exercising conditions directly. */
    private static final class MapVariables implements IWorkflowVariables {
        private final Map<String, Object> values = new HashMap<>();

        <T> MapVariables with(WorkflowVariable<T> variable, T value) {
            values.put(variable.name(), value);
            return this;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T require(WorkflowVariable<T> variable) {
            if (!values.containsKey(variable.name())) {
                throw new WorkflowVariableMissingException(variable);
            }
            return (T) values.get(variable.name());
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<T> find(WorkflowVariable<T> variable) {
            return Optional.ofNullable((T) values.get(variable.name()));
        }

        @Override
        public boolean exists(WorkflowVariable<?> variable) {
            return values.containsKey(variable.name());
        }
    }

    @Test
    void cond001ExistsTrueWhenPresent() {
        assertThat(WorkflowConditions.exists(NAME).evaluate(new MapVariables().with(NAME, "x")))
                .isTrue();
    }

    @Test
    void cond002ExistsFalseWhenMissing() {
        assertThat(WorkflowConditions.exists(NAME).evaluate(new MapVariables())).isFalse();
    }

    @Test
    void cond003NotExistsTrueWhenMissing() {
        assertThat(WorkflowConditions.notExists(NAME).evaluate(new MapVariables())).isTrue();
    }

    @Test
    void cond004NotExistsFalseWhenPresent() {
        assertThat(WorkflowConditions.notExists(NAME).evaluate(new MapVariables().with(NAME, "x")))
                .isFalse();
    }

    @Test
    void cond005EqualsTrueWhenMatching() {
        assertThat(
                        WorkflowConditions.equals(NAME, "alice")
                                .evaluate(new MapVariables().with(NAME, "alice")))
                .isTrue();
    }

    @Test
    void cond006EqualsFalseWhenNotMatching() {
        assertThat(
                        WorkflowConditions.equals(NAME, "alice")
                                .evaluate(new MapVariables().with(NAME, "bob")))
                .isFalse();
    }

    @Test
    void cond007EqualsThrowsWhenMissing() {
        assertThatThrownBy(
                        () -> WorkflowConditions.equals(NAME, "alice").evaluate(new MapVariables()))
                .isInstanceOf(WorkflowVariableMissingException.class);
    }

    @Test
    void cond008NotEquals() {
        MapVariables vars = new MapVariables().with(NAME, "bob");

        assertThat(WorkflowConditions.notEquals(NAME, "alice").evaluate(vars)).isTrue();
        assertThat(WorkflowConditions.notEquals(NAME, "bob").evaluate(vars)).isFalse();
    }

    @Test
    void cond009IsTrue() {
        assertThat(WorkflowConditions.isTrue(FLAG).evaluate(new MapVariables().with(FLAG, true)))
                .isTrue();
        assertThat(WorkflowConditions.isTrue(FLAG).evaluate(new MapVariables().with(FLAG, false)))
                .isFalse();
    }

    @Test
    void cond010IsFalse() {
        assertThat(WorkflowConditions.isFalse(FLAG).evaluate(new MapVariables().with(FLAG, false)))
                .isTrue();
        assertThat(WorkflowConditions.isFalse(FLAG).evaluate(new MapVariables().with(FLAG, true)))
                .isFalse();
    }

    @Test
    void cond011Not() {
        IWorkflowCondition negated = WorkflowConditions.not(WorkflowConditions.exists(NAME));

        assertThat(negated.evaluate(new MapVariables())).isTrue();
        assertThat(negated.evaluate(new MapVariables().with(NAME, "x"))).isFalse();
    }

    @Test
    void cond012AllOf() {
        MapVariables vars = new MapVariables().with(NAME, "alice").with(FLAG, true);
        IWorkflowCondition allTrue =
                WorkflowConditions.allOf(
                        WorkflowConditions.equals(NAME, "alice"), WorkflowConditions.isTrue(FLAG));
        IWorkflowCondition oneFalse =
                WorkflowConditions.allOf(
                        WorkflowConditions.equals(NAME, "alice"), WorkflowConditions.isFalse(FLAG));

        assertThat(allTrue.evaluate(vars)).isTrue();
        assertThat(oneFalse.evaluate(vars)).isFalse();
    }

    @Test
    void cond013AnyOf() {
        MapVariables vars = new MapVariables().with(NAME, "alice").with(FLAG, false);
        IWorkflowCondition anyTrue =
                WorkflowConditions.anyOf(
                        WorkflowConditions.equals(NAME, "bob"), WorkflowConditions.isFalse(FLAG));
        IWorkflowCondition noneTrue =
                WorkflowConditions.anyOf(
                        WorkflowConditions.equals(NAME, "bob"), WorkflowConditions.isTrue(FLAG));

        assertThat(anyTrue.evaluate(vars)).isTrue();
        assertThat(noneTrue.evaluate(vars)).isFalse();
    }

    @Test
    void secretComparisonDescriptionIsMasked() {
        String description = WorkflowConditions.equals(PASSWORD, SECRET_SENTINEL).describe();

        assertThat(description).doesNotContain(SECRET_SENTINEL).contains("***");
    }

    @Test
    void referencedVariablesReflectComposition() {
        IWorkflowCondition composed =
                WorkflowConditions.allOf(
                        WorkflowConditions.exists(NAME), WorkflowConditions.isTrue(FLAG));

        assertThat(composed.referencedVariables()).containsExactlyInAnyOrder(NAME, FLAG);
    }

    @Test
    void allOfAndAnyOfRejectEmptyVarargs() {
        assertThatThrownBy(WorkflowConditions::allOf).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(WorkflowConditions::anyOf).isInstanceOf(IllegalArgumentException.class);
    }
}
