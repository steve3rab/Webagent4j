package io.webagent4j.workflow;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * (even if the two declarations are identical), a step output structurally colliding with an
 * existing input or an earlier step's output (even if identical, and even if either producer is
 * itself guarded by {@link IWorkflowStep#when} - see below), a condition referencing a variable
 * that is neither a declared input nor a definitely-published earlier output, and a conditional
 * step nested deeper than {@link #MAX_CONDITIONAL_NESTING_DEPTH}. See {@code
 * docs/workflow.md#workflow-definitions} for the complete contract.
 *
 * <p><b>Guard-aware definite assignment:</b> an output is only ever treated as definitely available
 * to a later step's condition (or a conditional step's own branch selector) when every reachable
 * path from the start of the workflow to that point is structurally guaranteed to publish it -
 * never merely because some path <em>might</em>. A step guarded by {@link IWorkflowStep#when} may
 * be {@code SKIPPED} at runtime instead of publishing its output, so its output is never definite,
 * even though the step itself remains valid and does publish normally whenever its guard evaluates
 * {@code true}; for a conditional step, an output is definite only when both branches
 * unconditionally guarantee it (see {@code docs/workflow.md#branching}). This is deliberately a
 * purely structural, guard-blind analysis - the builder never attempts to prove a particular
 * condition instance is always {@code true} or {@code false} - and is independent of the separate,
 * unconditional structural-collision check above: a guarded producer can never "free up" its output
 * name for a second producer to reuse, since at runtime the guard may still evaluate {@code true}.
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

            Set<WorkflowVariable<?>> declared = new LinkedHashSet<>();
            declared.addAll(requiredInputs);
            declared.addAll(optionalInputs);
            Set<WorkflowVariable<?>> definite = new LinkedHashSet<>(declared);

            Set<WorkflowStepId> seenStepIds = new HashSet<>();
            for (IWorkflowStep step : steps) {
                validateStep(step, byName, declared, definite, seenStepIds, 0);
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
         * <p>Two distinct sets are threaded through this whole recursion, both mutated in place to
         * add this step's own contribution: {@code declared} is every output ever produced by any
         * reachable step, guarded or not - used only to reject a structurally conflicting or
         * duplicate producer (see {@link #registerStepOutput}) - while {@code definite} is the
         * strictly smaller set a later step or condition may actually statically rely on being
         * present. A step guarded by {@link IWorkflowStep#when} contributes its output to {@code
         * declared} but never to {@code definite}: the guard may evaluate to {@code false} at
         * runtime, in which case the step is {@code SKIPPED} and never publishes anything (see
         * {@code docs/workflow.md#conditions}), so nothing downstream may treat that output as
         * guaranteed. A conditional step's two branches are each validated independently, starting
         * from an identical snapshot of both sets - one branch's own contribution is never visible
         * while validating the other, since at runtime at most one of them ever executes - and then
         * merged back: {@code declared} by union (see {@link #mergeBranchDeclarations}) and {@code
         * definite} by intersection of what both branches unconditionally guarantee (see {@link
         * #mergeBranchDefinite} and {@code docs/workflow.md#branching}). {@code seenStepIds} is
         * shared, unmodified, across the whole recursive call tree, so every step ID - at any
         * nesting depth, in either branch - must be globally unique. {@code conditionalDepth} is
         * this step's own conditional nesting depth (0 for every top-level step, whether or not it
         * is itself conditional): a non-conditional step never changes it for whatever follows, and
         * a {@link ConditionalWorkflowStep} enforces {@link #MAX_CONDITIONAL_NESTING_DEPTH} against
         * its own {@code conditionalDepth + 1} before descending into either branch, passing that
         * same incremented depth as the starting point for both - never summed between them (see
         * {@link #MAX_CONDITIONAL_NESTING_DEPTH}'s Javadoc).
         */
        private static void validateStep(
                IWorkflowStep step,
                Map<String, WorkflowVariable<?>> byName,
                Set<WorkflowVariable<?>> declared,
                Set<WorkflowVariable<?>> definite,
                Set<WorkflowStepId> seenStepIds,
                int conditionalDepth) {
            if (!seenStepIds.add(step.id())) {
                throw new IllegalArgumentException("duplicate step ID '" + step.id() + "'");
            }

            Optional<IWorkflowCondition> guard = step.condition();
            guard.ifPresent(condition -> validateCondition(step, condition, definite));

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
                // declared/definite before the conditional - neither one's own contribution may
                // leak into the other's validation, since at runtime at most one of them ever
                // executes. Merging is deferred until after both have been independently
                // validated in full; merging THEN's outputs before validating ELSE would make
                // ELSE's own re-declaration of the very same output collide with itself.
                BranchResult thenResult =
                        validateBranch(
                                conditional.thenSteps(),
                                byName,
                                declared,
                                definite,
                                seenStepIds,
                                nestedDepth);
                BranchResult elseResult =
                        validateBranch(
                                conditional.elseSteps().orElse(List.of()),
                                byName,
                                declared,
                                definite,
                                seenStepIds,
                                nestedDepth);
                mergeBranchDeclarations(
                        byName, declared, thenResult.declared(), elseResult.declared());
                mergeBranchDefinite(definite, thenResult.definite(), elseResult.definite());
                return;
            }
            concreteStep
                    .outputVariable()
                    .ifPresent(
                            output ->
                                    registerStepOutput(
                                            byName,
                                            declared,
                                            definite,
                                            step,
                                            output,
                                            guard.isPresent()));
        }

        /**
         * One branch's own resulting {@code declared}/{@code definite} sets - see {@link
         * #validateBranch}.
         */
        private record BranchResult(
                Set<WorkflowVariable<?>> declared, Set<WorkflowVariable<?>> definite) {}

        /**
         * Validates one branch's steps in isolation, starting from a snapshot of {@code
         * byName}/{@code declared}/{@code definite} as they stood before the conditional - never
         * mutating any of them - and returns the branch's own resulting {@code declared}/{@code
         * definite} sets for the caller to merge back once both branches have been validated (see
         * {@link #validateStep}). {@code conditionalDepth} is the depth every step directly in
         * {@code branchSteps} starts at - see {@link #validateStep}.
         */
        private static BranchResult validateBranch(
                List<IWorkflowStep> branchSteps,
                Map<String, WorkflowVariable<?>> byName,
                Set<WorkflowVariable<?>> declared,
                Set<WorkflowVariable<?>> definite,
                Set<WorkflowStepId> seenStepIds,
                int conditionalDepth) {
            Map<String, WorkflowVariable<?>> branchByName = new LinkedHashMap<>(byName);
            Set<WorkflowVariable<?>> branchDeclared = new LinkedHashSet<>(declared);
            Set<WorkflowVariable<?>> branchDefinite = new LinkedHashSet<>(definite);
            for (IWorkflowStep step : branchSteps) {
                validateStep(
                        step,
                        branchByName,
                        branchDeclared,
                        branchDefinite,
                        seenStepIds,
                        conditionalDepth);
            }
            return new BranchResult(branchDeclared, branchDefinite);
        }

        /**
         * Folds a branch's newly-declared outputs (present in {@code branchDeclared} but not yet in
         * {@code declared}) into the shared, outer {@code byName}/{@code declared}, using the exact
         * same collision rule {@link #registerStepOutput} already applies to a single step's
         * output: an identical redeclaration (same name, same {@link WorkflowVariable}) is fine -
         * this runs for both branches in turn, and they may agree - but a same-named variable
         * declared with a different type or secret status is rejected. This is a union, by design,
         * unlike {@link #mergeBranchDefinite}: {@code declared} exists only to catch a structurally
         * conflicting or duplicate producer reachable from a single execution, regardless of any
         * guard, never to decide what a later step may statically rely on being present.
         */
        private static void mergeBranchDeclarations(
                Map<String, WorkflowVariable<?>> byName,
                Set<WorkflowVariable<?>> declared,
                Set<WorkflowVariable<?>> thenDeclared,
                Set<WorkflowVariable<?>> elseDeclared) {
            mergeOneBranchDeclaration(byName, declared, thenDeclared);
            mergeOneBranchDeclaration(byName, declared, elseDeclared);
        }

        private static void mergeOneBranchDeclaration(
                Map<String, WorkflowVariable<?>> byName,
                Set<WorkflowVariable<?>> declared,
                Set<WorkflowVariable<?>> branchDeclared) {
            for (WorkflowVariable<?> candidate : branchDeclared) {
                if (declared.contains(candidate)) {
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
                declared.add(candidate);
            }
        }

        /**
         * Computes <b>definite assignment</b> for a conditional's two branches: a variable this
         * conditional makes available to whatever structurally follows it only when <em>both</em>
         * {@code thenDefinite} and {@code elseDefinite} newly, unconditionally guarantee it -
         * relative to what was already definite before the conditional - with an identical {@link
         * WorkflowVariable} declaration. A variable only one branch unconditionally guarantees -
         * including one produced by a step that is itself guarded, anywhere inside either branch -
         * is never added: at runtime exactly one branch runs, and even inside the selected branch a
         * guarded producer may still be skipped, so a later step could otherwise statically appear
         * valid while actually reading a variable nothing on the executed path ever published. This
         * is intentionally an intersection, not the union {@link #mergeBranchDeclarations} computes
         * for {@code declared} - see {@code docs/workflow.md#branching}.
         *
         * <p>{@code elseDefinite} is exactly {@code definite} (no new names) whenever the
         * conditional has no {@code elseSteps} ({@code ifThen}), so this naturally rejects every
         * {@code thenSteps}-only output as not definite - {@code ifThen}'s branch may not have run
         * at all. Every name reachable here was already validated for a conflicting declaration by
         * {@link #mergeBranchDeclarations} (definite is always a subset of declared), so this need
         * not repeat that check.
         */
        private static void mergeBranchDefinite(
                Set<WorkflowVariable<?>> definite,
                Set<WorkflowVariable<?>> thenDefinite,
                Set<WorkflowVariable<?>> elseDefinite) {
            Map<String, WorkflowVariable<?>> thenNew = newlyIntroduced(definite, thenDefinite);
            Map<String, WorkflowVariable<?>> elseNew = newlyIntroduced(definite, elseDefinite);
            for (Map.Entry<String, WorkflowVariable<?>> entry : thenNew.entrySet()) {
                WorkflowVariable<?> thenVariable = entry.getValue();
                WorkflowVariable<?> elseVariable = elseNew.get(entry.getKey());
                if (elseVariable != null && thenVariable.equals(elseVariable)) {
                    definite.add(thenVariable);
                }
            }
        }

        /**
         * Returns {@code branchSet}'s own new entries, keyed by name: every variable it contains
         * that was not already present in {@code baseline} before the branch was validated.
         */
        private static Map<String, WorkflowVariable<?>> newlyIntroduced(
                Set<WorkflowVariable<?>> baseline, Set<WorkflowVariable<?>> branchSet) {
            Map<String, WorkflowVariable<?>> result = new LinkedHashMap<>();
            for (WorkflowVariable<?> candidate : branchSet) {
                if (!baseline.contains(candidate)) {
                    result.put(candidate.name(), candidate);
                }
            }
            return result;
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

        /**
         * Validates that {@code condition} - a step's own optional guard, or a conditional step's
         * mandatory branch selector - references only variables that are <b>definitely</b> present
         * at this point: a declared input, or an earlier step's output that is guaranteed to have
         * been published (see {@link #validateStep}). {@code definite} deliberately excludes any
         * output a guarded producer only <em>might</em> have published, since a false guard skips
         * that producer entirely (see {@code docs/workflow.md#conditions}).
         */
        private static void validateCondition(
                IWorkflowStep step,
                IWorkflowCondition condition,
                Set<WorkflowVariable<?>> definite) {
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
                if (!definite.contains(variable)) {
                    throw new IllegalArgumentException(
                            "step '"
                                    + step.id()
                                    + "' condition references variable '"
                                    + variable.name()
                                    + "', which is not a declared input or an earlier step's"
                                    + " definitely-published output (see"
                                    + " docs/workflow.md#definition-validation for guard-aware"
                                    + " definite assignment)");
                }
            }
        }

        /**
         * Registers one non-conditional step's declared output: always into {@code declared} (the
         * structural, guard-independent collision registry), but into {@code definite} - what a
         * later step or condition may statically rely on - only when {@code guarded} is {@code
         * false}. A step guarded by {@link IWorkflowStep#when} may be {@code SKIPPED} at runtime
         * and never publish anything, so its output can never be treated as definitely available to
         * whatever structurally follows it, even though the step itself remains perfectly valid and
         * still publishes normally whenever its guard does evaluate {@code true} (see {@code
         * docs/workflow.md#conditions}).
         */
        private static void registerStepOutput(
                Map<String, WorkflowVariable<?>> byName,
                Set<WorkflowVariable<?>> declared,
                Set<WorkflowVariable<?>> definite,
                IWorkflowStep step,
                WorkflowVariable<?> output,
                boolean guarded) {
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
            if (!declared.add(output)) {
                throw new IllegalArgumentException(
                        "step '"
                                + step.id()
                                + "' output '"
                                + output.name()
                                + "' collides with an existing input or an earlier step's"
                                + " output");
            }
            if (!guarded) {
                definite.add(output);
            }
        }
    }
}
