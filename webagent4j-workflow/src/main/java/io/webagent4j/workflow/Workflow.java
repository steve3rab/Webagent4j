package io.webagent4j.workflow;

import io.webagent4j.workflow.internal.IExecutableWorkflowStep;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, reusable, ordered sequence of {@link IWorkflowStep}s over a declared set of typed
 * inputs.
 *
 * <p>A {@code Workflow} is a pure definition: building one ({@link Builder#build()}) never touches
 * a backend, never calls an {@link IWorkflowActionFactory}, and never performs any side effect -
 * only structural validation. The same immutable instance can be passed to {@link
 * WorkflowEngine#execute(Workflow, WorkflowInputs)} any number of times, and each call is fully
 * independent (see {@code docs/workflow.md#determinism}).
 *
 * <p>{@link Builder#build()} rejects, before any execution: a blank ID, an empty step list, a
 * duplicate step ID, a variable name reused with a conflicting type or secret status, a step output
 * colliding with an existing input or earlier output, and a condition referencing a variable that
 * is neither a declared input nor produced by an earlier step. See {@code
 * docs/workflow.md#workflow-definitions} for the complete contract.
 */
public final class Workflow {

    private final WorkflowId id;
    private final List<WorkflowVariable<?>> requiredInputs;
    private final List<WorkflowVariable<?>> optionalInputs;
    private final List<IWorkflowStep> steps;

    private Workflow(
            WorkflowId id,
            List<WorkflowVariable<?>> requiredInputs,
            List<WorkflowVariable<?>> optionalInputs,
            List<IWorkflowStep> steps) {
        this.id = id;
        this.requiredInputs = requiredInputs;
        this.optionalInputs = optionalInputs;
        this.steps = steps;
    }

    /** Returns a new builder for a workflow named {@code id}. */
    public static Builder builder(String id) {
        return new Builder(new WorkflowId(id));
    }

    /** Returns this workflow's identifier. */
    public WorkflowId id() {
        return id;
    }

    /** Returns every declared required input, in declaration order - for {@link WorkflowEngine}. */
    List<WorkflowVariable<?>> requiredInputs() {
        return requiredInputs;
    }

    /** Returns every declared optional input, in declaration order - for {@link WorkflowEngine}. */
    List<WorkflowVariable<?>> optionalInputs() {
        return optionalInputs;
    }

    /** Returns every step, in execution order - for {@link WorkflowEngine}. */
    List<IWorkflowStep> steps() {
        return steps;
    }

    /**
     * Renders the workflow's ID, declared inputs (secret ones marked, never valued), and step IDs.
     */
    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("Workflow[id=").append(id).append(", inputs=[");
        boolean first = true;
        for (WorkflowVariable<?> input : requiredInputs) {
            if (!first) {
                text.append(", ");
            }
            first = false;
            text.append(input.name()).append(input.secret() ? "(secret)" : "");
        }
        for (WorkflowVariable<?> input : optionalInputs) {
            if (!first) {
                text.append(", ");
            }
            first = false;
            text.append(input.name()).append("(optional)").append(input.secret() ? "(secret)" : "");
        }
        text.append("], steps=[");
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(steps.get(i).id().value());
        }
        return text.append("]]").toString();
    }

    /** Mutable builder for {@link Workflow}. Building never performs a side effect. */
    public static final class Builder {

        private final WorkflowId id;
        private final List<WorkflowVariable<?>> requiredInputs = new ArrayList<>();
        private final List<WorkflowVariable<?>> optionalInputs = new ArrayList<>();
        private final List<IWorkflowStep> steps = new ArrayList<>();

        private Builder(WorkflowId id) {
            this.id = id;
        }

        /**
         * Declares {@code variable} as required: execution fails before step 0 if it is missing.
         */
        public Builder requiredInput(WorkflowVariable<?> variable) {
            requiredInputs.add(Objects.requireNonNull(variable, "variable"));
            return this;
        }

        /**
         * Declares {@code variable} as optional: absent unless supplied, testable via conditions.
         */
        public Builder optionalInput(WorkflowVariable<?> variable) {
            optionalInputs.add(Objects.requireNonNull(variable, "variable"));
            return this;
        }

        /** Appends {@code step} to the ordered execution sequence. */
        public Builder step(IWorkflowStep step) {
            steps.add(Objects.requireNonNull(step, "step"));
            return this;
        }

        /**
         * Validates and builds an immutable {@link Workflow}.
         *
         * @throws IllegalArgumentException if the definition is structurally invalid
         */
        public Workflow build() {
            if (steps.isEmpty()) {
                throw new IllegalArgumentException(
                        "workflow '" + id + "' must declare at least one step");
            }

            Map<String, WorkflowVariable<?>> byName = new LinkedHashMap<>();
            requiredInputs.forEach(variable -> registerVariable(byName, variable));
            optionalInputs.forEach(variable -> registerVariable(byName, variable));

            Set<WorkflowVariable<?>> available = new LinkedHashSet<>();
            available.addAll(requiredInputs);
            available.addAll(optionalInputs);

            Set<WorkflowStepId> seenStepIds = new HashSet<>();
            for (IWorkflowStep step : steps) {
                if (!seenStepIds.add(step.id())) {
                    throw new IllegalArgumentException("duplicate step ID '" + step.id() + "'");
                }
                step.condition()
                        .ifPresent(
                                condition -> {
                                    for (WorkflowVariable<?> referenced :
                                            condition.referencedVariables()) {
                                        if (!available.contains(referenced)) {
                                            throw new IllegalArgumentException(
                                                    "step '"
                                                            + step.id()
                                                            + "' condition '"
                                                            + condition.describe()
                                                            + "' references variable '"
                                                            + referenced.name()
                                                            + "', which is not a declared input or"
                                                            + " an earlier step's output");
                                        }
                                    }
                                });

                IExecutableWorkflowStep executable = (IExecutableWorkflowStep) step;
                executable
                        .outputVariable()
                        .ifPresent(
                                output -> {
                                    registerVariable(byName, output);
                                    if (!available.add(output)) {
                                        throw new IllegalArgumentException(
                                                "step '"
                                                        + step.id()
                                                        + "' output '"
                                                        + output.name()
                                                        + "' collides with an existing input or an"
                                                        + " earlier step's output");
                                    }
                                });
            }

            return new Workflow(
                    id,
                    List.copyOf(requiredInputs),
                    List.copyOf(optionalInputs),
                    List.copyOf(steps));
        }

        private static void registerVariable(
                Map<String, WorkflowVariable<?>> byName, WorkflowVariable<?> variable) {
            WorkflowVariable<?> existing = byName.putIfAbsent(variable.name(), variable);
            if (existing != null && !existing.equals(variable)) {
                throw new IllegalArgumentException(
                        "variable '"
                                + variable.name()
                                + "' is declared more than once with a conflicting type or secret"
                                + " status");
            }
        }
    }
}
