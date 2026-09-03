package io.webagent4j.workflow;

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
 * <p>A {@code Workflow} is a pure definition: building one ({@link Builder#build()}) never calls an
 * {@link IWorkflowActionFactory} and never performs a backend side effect - only structural
 * validation. It does read a custom {@link IWorkflowCondition}'s metadata methods ({@code
 * referencedVariables()}) if one is attached to a step, since that is required to validate the
 * definition's dataflow; those methods are required to be side-effect-free (see {@code
 * docs/workflow.md#conditions}). The same immutable instance can be passed to {@link
 * WorkflowEngine#execute(Workflow, WorkflowInputs)} any number of times, and each call is fully
 * independent (see {@code docs/workflow.md#determinism}).
 *
 * <p>{@link Builder#build()} rejects, before any execution: a blank ID, an empty step list, a
 * duplicate step ID, a variable name declared more than once among required and optional inputs
 * (even if the two declarations are identical), a step output colliding with an existing input or
 * an earlier step's output (even if identical), a condition referencing a variable that is neither
 * a declared input nor produced by an earlier step, and a conditional step nested deeper than
 * {@link #MAX_CONDITIONAL_NESTING_DEPTH}. See {@code docs/workflow.md#workflow-definitions} for the
 * complete contract.
 */
public final class Workflow {

    /**
     * Maximum accepted conditional nesting depth of a workflow definition. A top-level {@code
     * ifElse}/{@code ifThen} step is depth 1; one nested inside either of its branches is depth 2;
     * and so on. A non-conditional step never contributes to depth, and a conditional's {@code
     * thenSteps}/{@code elseSteps} are each measured independently starting from the same depth -
     * never summed - so two branches that each individually respect this limit are both accepted
     * regardless of how deep the other one goes.
     *
     * <p>{@link Builder#build()} is the single place this invariant is enforced, and the only way
     * to obtain a {@link Workflow} instance at all - its constructor is private and every step type
     * is either sealed ({@link IWorkflowStep}) or package-private ({@link
     * ConditionalWorkflowStep}), constructible only through {@link WorkflowSteps}. A definition
     * exceeding this depth is therefore rejected with {@link IllegalArgumentException} before it
     * can ever become an executable {@code Workflow}, so {@link WorkflowEngine}'s own recursive
     * traversal of a conditional's branches never needs a second, independent depth check to stay
     * within a normal JVM stack: every {@code Workflow} it can possibly receive is already bounded
     * by this same constant, checked once, here.
     *
     * <p>Mirrors the existing structural-nesting-bound precedent set by {@code
     * JsonWorkflowRecordingCodec#MAX_NESTING_DEPTH} elsewhere in this codebase: generous relative
     * to any workflow a human or generator would author by hand, while keeping recursive validation
     * and execution comfortably within stack limits for a definition shaped to be adversarially
     * deep.
     */
    static final int MAX_CONDITIONAL_NESTING_DEPTH = 64;

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

    /**
     * Mutable builder for {@link Workflow}. {@link #build()} never performs a backend or
     * action-factory side effect; it does invoke an attached custom {@link IWorkflowCondition}'s
     * metadata methods for structural validation, which are required to be side-effect-free
     * themselves (see {@code docs/workflow.md#conditions}).
     */
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
            Set<String> declaredInputNames = new HashSet<>();
            registerInputDeclarations(byName, declaredInputNames, requiredInputs);
            registerInputDeclarations(byName, declaredInputNames, optionalInputs);

            Set<WorkflowVariable<?>> available = new LinkedHashSet<>();
            available.addAll(requiredInputs);
            available.addAll(optionalInputs);

            Set<WorkflowStepId> seenStepIds = new HashSet<>();
            for (IWorkflowStep step : steps) {
                validateStep(step, byName, available, seenStepIds, 0);
            }

            return new Workflow(
                    id,
                    List.copyOf(requiredInputs),
                    List.copyOf(optionalInputs),
                    List.copyOf(steps));
        }

        /**
         * Validates one step - recursively, for a {@link ConditionalWorkflowStep}, into both of its
         * branches - and registers whatever it (or its branches) makes available for the steps that
         * structurally follow it.
         *
         * <p>{@code available}/{@code byName} are mutated in place to add this step's own
         * contribution, exactly like the pre-branching code did for a flat step list. A conditional
         * step's two branches are each validated independently, starting from an identical snapshot
         * of what is available before the conditional - one branch's own step outputs are never
         * visible while validating the other, since at runtime at most one of them ever executes -
         * and then each branch's resulting outputs are merged back into the shared {@code
         * available}/{@code byName} for whatever comes after the conditional, exactly like a single
         * guarded step's output is already treated as statically available today regardless of
         * whether its guard turns out true at runtime (see {@code docs/workflow.md#conditions}).
         * {@code seenStepIds} is shared, unmodified, across the whole recursive call tree, so every
         * step ID - at any nesting depth, in either branch - must be globally unique. {@code
         * conditionalDepth} is this step's own conditional nesting depth (0 for every top-level
         * step, whether or not it is itself conditional): a non-conditional step never changes it
         * for whatever follows, and a {@link ConditionalWorkflowStep} enforces {@link
         * #MAX_CONDITIONAL_NESTING_DEPTH} against its own {@code conditionalDepth + 1} before
         * descending into either branch, passing that same incremented depth as the starting point
         * for both - never summed between them (see {@link #MAX_CONDITIONAL_NESTING_DEPTH}'s
         * Javadoc).
         */
        private static void validateStep(
                IWorkflowStep step,
                Map<String, WorkflowVariable<?>> byName,
                Set<WorkflowVariable<?>> available,
                Set<WorkflowStepId> seenStepIds,
                int conditionalDepth) {
            if (!seenStepIds.add(step.id())) {
                throw new IllegalArgumentException("duplicate step ID '" + step.id() + "'");
            }

            step.condition().ifPresent(condition -> validateCondition(step, condition, available));

            // Safe: IWorkflowStep is sealed and permits only AWorkflowStep (see its Javadoc), so
            // every instance reachable here is guaranteed to be one.
            AWorkflowStep concreteStep = (AWorkflowStep) step;
            if (concreteStep instanceof ConditionalWorkflowStep conditional) {
                int nestedDepth = conditionalDepth + 1;
                if (nestedDepth > MAX_CONDITIONAL_NESTING_DEPTH) {
                    throw new IllegalArgumentException(
                            "step '"
                                    + step.id()
                                    + "' exceeds the maximum supported conditional nesting depth"
                                    + " of "
                                    + MAX_CONDITIONAL_NESTING_DEPTH);
                }
                // Both branches must validate from an identical starting snapshot of what is
                // available before the conditional - neither one's own outputs may leak into the
                // other's validation, since at runtime at most one of them ever executes. Merging
                // is deferred until after both have been independently validated in full; merging
                // THEN's outputs before validating ELSE would make ELSE's own re-declaration of
                // the very same output collide with itself.
                Set<WorkflowVariable<?>> thenAvailable =
                        validateBranch(
                                conditional.thenSteps(),
                                byName,
                                available,
                                seenStepIds,
                                nestedDepth);
                Set<WorkflowVariable<?>> elseAvailable =
                        validateBranch(
                                conditional.elseSteps().orElse(List.of()),
                                byName,
                                available,
                                seenStepIds,
                                nestedDepth);
                mergeBranchOutputs(byName, available, thenAvailable);
                mergeBranchOutputs(byName, available, elseAvailable);
                return;
            }
            concreteStep
                    .outputVariable()
                    .ifPresent(output -> registerStepOutput(byName, available, step, output));
        }

        /**
         * Validates one branch's steps in isolation, starting from a snapshot of {@code
         * byName}/{@code available} as they stood before the conditional - never mutating either -
         * and returns the branch's own resulting {@code available} set for the caller to merge back
         * once both branches have been validated (see {@link #validateStep}). {@code
         * conditionalDepth} is the depth every step directly in {@code branchSteps} starts at - see
         * {@link #validateStep}.
         */
        private static Set<WorkflowVariable<?>> validateBranch(
                List<IWorkflowStep> branchSteps,
                Map<String, WorkflowVariable<?>> byName,
                Set<WorkflowVariable<?>> available,
                Set<WorkflowStepId> seenStepIds,
                int conditionalDepth) {
            Map<String, WorkflowVariable<?>> branchByName = new LinkedHashMap<>(byName);
            Set<WorkflowVariable<?>> branchAvailable = new LinkedHashSet<>(available);
            for (IWorkflowStep step : branchSteps) {
                validateStep(step, branchByName, branchAvailable, seenStepIds, conditionalDepth);
            }
            return branchAvailable;
        }

        /**
         * Folds a branch's newly-declared outputs (present in {@code branchAvailable} but not yet
         * in {@code available}) into the shared, outer {@code byName}/{@code available}, using the
         * exact same collision rule {@link #registerStepOutput} already applies to a single step's
         * output: an identical redeclaration (same name, same {@link WorkflowVariable}) is fine -
         * this runs for both branches in turn, and they may agree - but a same-named variable
         * declared with a different type or secret status is rejected.
         */
        private static void mergeBranchOutputs(
                Map<String, WorkflowVariable<?>> byName,
                Set<WorkflowVariable<?>> available,
                Set<WorkflowVariable<?>> branchAvailable) {
            for (WorkflowVariable<?> candidate : branchAvailable) {
                if (available.contains(candidate)) {
                    continue;
                }
                WorkflowVariable<?> existing = byName.putIfAbsent(candidate.name(), candidate);
                if (existing != null && !existing.equals(candidate)) {
                    throw new IllegalArgumentException(
                            "branch output '"
                                    + candidate.name()
                                    + "' conflicts with an existing input or output declared with"
                                    + " a different type or secret status");
                }
                available.add(candidate);
            }
        }

        private static void registerInputDeclarations(
                Map<String, WorkflowVariable<?>> byName,
                Set<String> declaredInputNames,
                List<WorkflowVariable<?>> inputs) {
            for (WorkflowVariable<?> variable : inputs) {
                if (!declaredInputNames.add(variable.name())) {
                    throw new IllegalArgumentException(
                            "input '" + variable.name() + "' is declared more than once");
                }
                byName.put(variable.name(), variable);
            }
        }

        private static void validateCondition(
                IWorkflowStep step,
                IWorkflowCondition condition,
                Set<WorkflowVariable<?>> available) {
            Set<WorkflowVariable<?>> referenced;
            try {
                referenced = condition.referencedVariables();
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(
                        "step '"
                                + step.id()
                                + "' condition's referencedVariables() threw "
                                + e.getClass().getSimpleName(),
                        e);
            }
            if (referenced == null) {
                throw new IllegalArgumentException(
                        "step '"
                                + step.id()
                                + "' condition returned a null referencedVariables() set");
            }
            for (WorkflowVariable<?> variable : referenced) {
                if (variable == null) {
                    throw new IllegalArgumentException(
                            "step '"
                                    + step.id()
                                    + "' condition's referencedVariables() contains a null"
                                    + " entry");
                }
                if (!available.contains(variable)) {
                    throw new IllegalArgumentException(
                            "step '"
                                    + step.id()
                                    + "' condition references variable '"
                                    + variable.name()
                                    + "', which is not a declared input or an earlier step's"
                                    + " output");
                }
            }
        }

        private static void registerStepOutput(
                Map<String, WorkflowVariable<?>> byName,
                Set<WorkflowVariable<?>> available,
                IWorkflowStep step,
                WorkflowVariable<?> output) {
            WorkflowVariable<?> existing = byName.putIfAbsent(output.name(), output);
            if (existing != null && !existing.equals(output)) {
                throw new IllegalArgumentException(
                        "step '"
                                + step.id()
                                + "' output '"
                                + output.name()
                                + "' conflicts with an existing input or output declared with a"
                                + " different type or secret status");
            }
            if (!available.add(output)) {
                throw new IllegalArgumentException(
                        "step '"
                                + step.id()
                                + "' output '"
                                + output.name()
                                + "' collides with an existing input or an earlier step's"
                                + " output");
            }
        }
    }
}
