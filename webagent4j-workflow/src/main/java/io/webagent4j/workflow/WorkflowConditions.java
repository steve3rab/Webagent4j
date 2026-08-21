package io.webagent4j.workflow;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Factory for the small, fixed set of declarative {@link IWorkflowCondition}s Phase 0.8 supports.
 *
 * <p>There is deliberately no expression language, regex, or scripting facade here - see {@code
 * docs/workflow.md#conditions}. Missing-variable semantics are fail-closed and asymmetric: {@link
 * #exists}/{@link #notExists} tolerate a missing variable (that is exactly what they test for),
 * while {@link #equals}, {@link #notEquals}, {@link #isTrue}, and {@link #isFalse} treat a missing
 * variable as an evaluation failure rather than silently treating it as {@code null} or {@code
 * false}.
 */
public final class WorkflowConditions {

    private WorkflowConditions() {}

    /** True when {@code variable} currently has a value. */
    public static IWorkflowCondition exists(WorkflowVariable<?> variable) {
        Objects.requireNonNull(variable, "variable");
        return new IWorkflowCondition() {
            @Override
            public boolean evaluate(IWorkflowVariables variables) {
                return variables.exists(variable);
            }

            @Override
            public String describe() {
                return "exists(" + variable.name() + ")";
            }

            @Override
            public Set<WorkflowVariable<?>> referencedVariables() {
                return Set.of(variable);
            }
        };
    }

    /** True when {@code variable} currently has no value. */
    public static IWorkflowCondition notExists(WorkflowVariable<?> variable) {
        Objects.requireNonNull(variable, "variable");
        return new IWorkflowCondition() {
            @Override
            public boolean evaluate(IWorkflowVariables variables) {
                return !variables.exists(variable);
            }

            @Override
            public String describe() {
                return "notExists(" + variable.name() + ")";
            }

            @Override
            public Set<WorkflowVariable<?>> referencedVariables() {
                return Set.of(variable);
            }
        };
    }

    /**
     * True when {@code variable}'s current value equals {@code expected}.
     *
     * @throws WorkflowVariableMissingException at evaluation time if {@code variable} is missing
     */
    public static <T> IWorkflowCondition equals(WorkflowVariable<T> variable, T expected) {
        Objects.requireNonNull(variable, "variable");
        Objects.requireNonNull(expected, "expected");
        return new IWorkflowCondition() {
            @Override
            public boolean evaluate(IWorkflowVariables variables) {
                return Objects.equals(variables.require(variable), expected);
            }

            @Override
            public String describe() {
                return "equals("
                        + variable.name()
                        + ", "
                        + SafeRendering.render(variable, expected)
                        + ")";
            }

            @Override
            public Set<WorkflowVariable<?>> referencedVariables() {
                return Set.of(variable);
            }
        };
    }

    /**
     * True when {@code variable}'s current value does not equal {@code expected}.
     *
     * @throws WorkflowVariableMissingException at evaluation time if {@code variable} is missing
     */
    public static <T> IWorkflowCondition notEquals(WorkflowVariable<T> variable, T expected) {
        return not(
                equals(variable, expected),
                "notEquals("
                        + variable.name()
                        + ", "
                        + SafeRendering.render(variable, expected)
                        + ")",
                Set.of(variable));
    }

    /**
     * True when boolean {@code variable}'s current value is {@code true}.
     *
     * @throws WorkflowVariableMissingException at evaluation time if {@code variable} is missing
     */
    public static IWorkflowCondition isTrue(WorkflowVariable<Boolean> variable) {
        return equals(variable, Boolean.TRUE);
    }

    /**
     * True when boolean {@code variable}'s current value is {@code false}.
     *
     * @throws WorkflowVariableMissingException at evaluation time if {@code variable} is missing
     */
    public static IWorkflowCondition isFalse(WorkflowVariable<Boolean> variable) {
        return equals(variable, Boolean.FALSE);
    }

    /**
     * Negates {@code condition}. {@code condition}'s own {@code describe()}/{@code
     * referencedVariables()} are invoked lazily - only when the returned condition's corresponding
     * method is itself invoked - so a custom {@code condition} that throws from either is handled
     * by the same defensive paths as any other condition ({@link WorkflowEngine} at evaluation
     * time, {@code Workflow.Builder#build()} for {@code referencedVariables()}), not eagerly at
     * composition time. A {@code null} {@code condition.describe()} is preserved as {@code null} -
     * never normalized to the literal text {@code "null"} - so {@link WorkflowEngine} still
     * classifies a malformed wrapped condition as {@code CONDITION_EVALUATION_FAILED}.
     */
    public static IWorkflowCondition not(IWorkflowCondition condition) {
        Objects.requireNonNull(condition, "condition");
        return new IWorkflowCondition() {
            @Override
            public boolean evaluate(IWorkflowVariables variables) {
                return !condition.evaluate(variables);
            }

            @Override
            public String describe() {
                String childDescription = condition.describe();
                if (childDescription == null) {
                    return null;
                }
                return "not(" + childDescription + ")";
            }

            @Override
            public Set<WorkflowVariable<?>> referencedVariables() {
                return condition.referencedVariables();
            }
        };
    }

    private static IWorkflowCondition not(
            IWorkflowCondition condition, String description, Set<WorkflowVariable<?>> referenced) {
        return new IWorkflowCondition() {
            @Override
            public boolean evaluate(IWorkflowVariables variables) {
                return !condition.evaluate(variables);
            }

            @Override
            public String describe() {
                return description;
            }

            @Override
            public Set<WorkflowVariable<?>> referencedVariables() {
                return referenced;
            }
        };
    }

    /**
     * True only when every one of {@code conditions} is true. {@code describe()} preserves a {@code
     * null} child description as {@code null} - see {@link #describeAll}.
     */
    public static IWorkflowCondition allOf(IWorkflowCondition... conditions) {
        List<IWorkflowCondition> copy = List.of(conditions);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("allOf requires at least one condition");
        }
        return new IWorkflowCondition() {
            @Override
            public boolean evaluate(IWorkflowVariables variables) {
                for (IWorkflowCondition condition : copy) {
                    if (!condition.evaluate(variables)) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public String describe() {
                String allDescriptions = describeAll(copy);
                if (allDescriptions == null) {
                    return null;
                }
                return "allOf(" + allDescriptions + ")";
            }

            @Override
            public Set<WorkflowVariable<?>> referencedVariables() {
                return referencedByAll(copy);
            }
        };
    }

    /**
     * True when at least one of {@code conditions} is true. {@code describe()} preserves a {@code
     * null} child description as {@code null} - see {@link #describeAll}.
     */
    public static IWorkflowCondition anyOf(IWorkflowCondition... conditions) {
        List<IWorkflowCondition> copy = List.of(conditions);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("anyOf requires at least one condition");
        }
        return new IWorkflowCondition() {
            @Override
            public boolean evaluate(IWorkflowVariables variables) {
                for (IWorkflowCondition condition : copy) {
                    if (condition.evaluate(variables)) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public String describe() {
                String allDescriptions = describeAll(copy);
                if (allDescriptions == null) {
                    return null;
                }
                return "anyOf(" + allDescriptions + ")";
            }

            @Override
            public Set<WorkflowVariable<?>> referencedVariables() {
                return referencedByAll(copy);
            }
        };
    }

    /**
     * Joins every condition's {@code describe()}, in declaration order, calling each at most once.
     * Returns {@code null} - never the literal text {@code "null"} - as soon as any child's
     * description is {@code null}, without calling {@code describe()} on the remaining children:
     * {@link WorkflowEngine} must still see a malformed wrapped condition as malformed, not as a
     * composite whose text happens to contain the word "null".
     */
    private static String describeAll(List<IWorkflowCondition> conditions) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < conditions.size(); i++) {
            String description = conditions.get(i).describe();
            if (description == null) {
                return null;
            }
            if (i > 0) {
                text.append(", ");
            }
            text.append(description);
        }
        return text.toString();
    }

    private static Set<WorkflowVariable<?>> referencedByAll(List<IWorkflowCondition> conditions) {
        Set<WorkflowVariable<?>> all = new LinkedHashSet<>();
        conditions.forEach(condition -> all.addAll(condition.referencedVariables()));
        // Collections.unmodifiableSet, not Set.copyOf: the latter does not guarantee it preserves
        // the source's iteration order, and this project treats deterministic order as load-bearing
        // even where it is not directly exposed through public API.
        return Collections.unmodifiableSet(all);
    }
}
