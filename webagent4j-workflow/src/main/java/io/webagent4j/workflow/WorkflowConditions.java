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
     * <p>{@code describe()}, called directly, renders {@code expected} immediately (crash-safe,
     * deliberately unbounded - see {@link SafeRendering}). {@link WorkflowEngine} instead calls
     * {@link IDeferredConditionDescription#describeFinal}, deferring that same rendering to
     * finalization time so the unbounded text is never retained for the workflow's whole execution
     * (see {@code WF-MEM-001}); both paths produce the identical final text for a well-behaved
     * {@code expected}.
     *
     * @throws WorkflowVariableMissingException at evaluation time if {@code variable} is missing
     */
    public static <T> IWorkflowCondition equals(WorkflowVariable<T> variable, T expected) {
        Objects.requireNonNull(variable, "variable");
        Objects.requireNonNull(expected, "expected");
        return new IDeferredConditionDescription() {
            @Override
            public boolean evaluate(IWorkflowVariables variables) {
                return Objects.equals(variables.require(variable), expected);
            }

            @Override
            public String describe() {
                return describeEquals(variable, SafeRendering.render(variable, expected));
            }

            @Override
            public String describeFinal(SecretRedactor finalRedactor) {
                // Deliberately not bounded here - see IDeferredConditionDescription's Javadoc: the
                // engine applies exactly one bound to the complete, possibly-composed description
                // text, the same way it always has, rather than each leaf condition bounding only
                // its own contribution (which would let a composite of many built-in conditions
                // grow
                // unbounded overall even though each individual leaf were bounded).
                return describeEquals(
                        variable, finalRedactor.redact(SafeRendering.render(variable, expected)));
            }

            @Override
            public Set<WorkflowVariable<?>> referencedVariables() {
                return Set.of(variable);
            }
        };
    }

    private static String describeEquals(WorkflowVariable<?> variable, String renderedExpected) {
        return "equals(" + variable.name() + ", " + renderedExpected + ")";
    }

    /**
     * True when {@code variable}'s current value does not equal {@code expected}. Shares {@link
     * #equals}'s deferred-description behavior - see that method's Javadoc.
     *
     * @throws WorkflowVariableMissingException at evaluation time if {@code variable} is missing
     */
    public static <T> IWorkflowCondition notEquals(WorkflowVariable<T> variable, T expected) {
        IWorkflowCondition equalsCondition = equals(variable, expected);
        return new IDeferredConditionDescription() {
            @Override
            public boolean evaluate(IWorkflowVariables variables) {
                return !equalsCondition.evaluate(variables);
            }

            @Override
            public String describe() {
                return describeNotEquals(variable, SafeRendering.render(variable, expected));
            }

            @Override
            public String describeFinal(SecretRedactor finalRedactor) {
                // See equals()'s describeFinal - deliberately not bounded here for the same reason.
                return describeNotEquals(
                        variable, finalRedactor.redact(SafeRendering.render(variable, expected)));
            }

            @Override
            public Set<WorkflowVariable<?>> referencedVariables() {
                return Set.of(variable);
            }
        };
    }

    private static String describeNotEquals(WorkflowVariable<?> variable, String renderedExpected) {
        return "notEquals(" + variable.name() + ", " + renderedExpected + ")";
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
        if (condition instanceof IDeferredConditionDescription deferred) {
            return new IDeferredConditionDescription() {
                @Override
                public boolean evaluate(IWorkflowVariables variables) {
                    return !condition.evaluate(variables);
                }

                @Override
                public String describe() {
                    return describeNot(condition.describe());
                }

                @Override
                public String describeFinal(SecretRedactor finalRedactor) {
                    return describeNot(deferred.describeFinal(finalRedactor));
                }

                @Override
                public Set<WorkflowVariable<?>> referencedVariables() {
                    return condition.referencedVariables();
                }
            };
        }
        return new IWorkflowCondition() {
            @Override
            public boolean evaluate(IWorkflowVariables variables) {
                return !condition.evaluate(variables);
            }

            @Override
            public String describe() {
                return describeNot(condition.describe());
            }

            @Override
            public Set<WorkflowVariable<?>> referencedVariables() {
                return condition.referencedVariables();
            }
        };
    }

    /**
     * Wraps a (possibly {@code null}) child description as {@code "not(" + childDescription + ")"},
     * preserving a {@code null} child description as {@code null} rather than the literal text
     * {@code "null"} - see {@link #not(IWorkflowCondition)}.
     */
    private static String describeNot(String childDescription) {
        return childDescription == null ? null : "not(" + childDescription + ")";
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
        if (allDeferred(copy)) {
            return new IDeferredConditionDescription() {
                @Override
                public boolean evaluate(IWorkflowVariables variables) {
                    return allTrue(copy, variables);
                }

                @Override
                public String describe() {
                    return describeComposite("allOf", describeAll(copy));
                }

                @Override
                public String describeFinal(SecretRedactor finalRedactor) {
                    return describeComposite("allOf", describeAllFinal(copy, finalRedactor));
                }

                @Override
                public Set<WorkflowVariable<?>> referencedVariables() {
                    return referencedByAll(copy);
                }
            };
        }
        return new IWorkflowCondition() {
            @Override
            public boolean evaluate(IWorkflowVariables variables) {
                return allTrue(copy, variables);
            }

            @Override
            public String describe() {
                return describeComposite("allOf", describeAll(copy));
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
        if (allDeferred(copy)) {
            return new IDeferredConditionDescription() {
                @Override
                public boolean evaluate(IWorkflowVariables variables) {
                    return anyTrue(copy, variables);
                }

                @Override
                public String describe() {
                    return describeComposite("anyOf", describeAll(copy));
                }

                @Override
                public String describeFinal(SecretRedactor finalRedactor) {
                    return describeComposite("anyOf", describeAllFinal(copy, finalRedactor));
                }

                @Override
                public Set<WorkflowVariable<?>> referencedVariables() {
                    return referencedByAll(copy);
                }
            };
        }
        return new IWorkflowCondition() {
            @Override
            public boolean evaluate(IWorkflowVariables variables) {
                return anyTrue(copy, variables);
            }

            @Override
            public String describe() {
                return describeComposite("anyOf", describeAll(copy));
            }

            @Override
            public Set<WorkflowVariable<?>> referencedVariables() {
                return referencedByAll(copy);
            }
        };
    }

    private static boolean allTrue(
            List<IWorkflowCondition> conditions, IWorkflowVariables variables) {
        for (IWorkflowCondition condition : conditions) {
            if (!condition.evaluate(variables)) {
                return false;
            }
        }
        return true;
    }

    private static boolean anyTrue(
            List<IWorkflowCondition> conditions, IWorkflowVariables variables) {
        for (IWorkflowCondition condition : conditions) {
            if (condition.evaluate(variables)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True only when every one of {@code conditions} already implements {@link
     * IDeferredConditionDescription} - the only case in which the composite built from them can
     * itself safely defer its own description to finalization time (see {@link
     * IDeferredConditionDescription}'s Javadoc on why only framework-owned description logic may do
     * so).
     */
    private static boolean allDeferred(List<IWorkflowCondition> conditions) {
        for (IWorkflowCondition condition : conditions) {
            if (!(condition instanceof IDeferredConditionDescription)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Wraps a (possibly {@code null}) joined child description as {@code name + "(" + joined +
     * ")"}, preserving {@code null} rather than the literal text {@code "null"}.
     */
    private static String describeComposite(String name, String joinedDescriptions) {
        return joinedDescriptions == null ? null : name + "(" + joinedDescriptions + ")";
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

    /**
     * {@link #describeAll}'s finalization-time counterpart: only ever called when {@link
     * #allDeferred} has already confirmed every element of {@code conditions} implements {@link
     * IDeferredConditionDescription}, so the cast below is safe.
     */
    private static String describeAllFinal(
            List<IWorkflowCondition> conditions, SecretRedactor finalRedactor) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < conditions.size(); i++) {
            IDeferredConditionDescription deferred =
                    (IDeferredConditionDescription) conditions.get(i);
            String description = deferred.describeFinal(finalRedactor);
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
