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
 * docs/workflow.md#workflow-definitions} for the complete contract. {@link Builder#validate()} is
 * an additive, read-only companion that explains the exact same invariants as a structured {@link
 * WorkflowValidationReport} instead of throwing on the first one - see its Javadoc and {@code
 * docs/workflow.md#validation-report}.
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
     * by this same constant, checked once, here. {@link Builder#validate()} shares this exact same
     * check (see {@link #analyze}), so it never recurses past this same bound either.
     *
     * <p>Mirrors the existing structural-nesting-bound precedent set by {@code
     * JsonWorkflowRecordingCodec#MAX_NESTING_DEPTH} elsewhere in this codebase: generous relative
     * to any workflow a human or generator would author by hand, while keeping recursive validation
     * and execution comfortably within stack limits for a definition shaped to be adversarially
     * deep.
     */
    static final int MAX_CONDITIONAL_NESTING_DEPTH = 64;

    /**
     * Maximum accepted <b>combined</b> control-flow nesting depth of a workflow definition - added
     * in 1.3.0, generalizing {@link #MAX_CONDITIONAL_NESTING_DEPTH} (kept as its own name and value
     * for a {@code CONDITIONAL} step, and for every existing test that names it) to also cover a
     * {@link WorkflowStepType#LOOP} step: the two share one counter and one bound, exactly equal in
     * value, rather than two independently-tracked limits. A top-level {@code ifElse}/{@code
     * ifThen} or {@code loop} step is depth 1; one of either kind nested inside it is depth 2; and
     * so on - a {@code loop} nested inside a conditional branch, or a conditional nested inside a
     * loop body, contributes to the exact same running depth. See {@link
     * #MAX_CONDITIONAL_NESTING_DEPTH}'s Javadoc for why a single {@link Builder#build()}-time check
     * here is sufficient to keep both {@link WorkflowEngine}'s and {@link WorkflowPlanner}'s
     * recursive traversals within a normal JVM stack, with no independent runtime depth check
     * needed in either.
     */
    static final int MAX_CONTROL_FLOW_NESTING_DEPTH = MAX_CONDITIONAL_NESTING_DEPTH;

    /**
     * Maximum accepted {@code maxIterations} for a {@link WorkflowStepType#LOOP} step - added in
     * 1.3.0. A bounded loop is an explicit, framework-enforced control structure, never an
     * unbounded or disguised repeat-until-success mechanism: even a workflow author who declares an
     * excessive bound cannot obtain one, and {@link WorkflowEngine} never needs a second,
     * independent runtime cap layered on top of this single definition-time one (see {@code
     * docs/workflow.md#bounded-loops}).
     */
    static final int MAX_LOOP_ITERATIONS = 10_000;

    /**
     * Minimum accepted number of branches for a {@link WorkflowStepType#PARALLEL} step - added in
     * 1.3.0. A single-branch "parallel" step would run no differently from that one branch's steps
     * appearing directly in sequence, so {@link Builder#build()} rejects it rather than accept a
     * vacuous use of the primitive.
     */
    static final int MIN_PARALLEL_BRANCHES = 2;

    /**
     * Maximum accepted number of branches for a {@link WorkflowStepType#PARALLEL} step - added in
     * 1.3.0. {@code WorkflowEngine} dedicates one worker thread per branch of a single {@code
     * PARALLEL} step to a freshly created, internally owned, bounded executor (see {@code
     * docs/workflow.md#parallel}), so this bound doubles as the maximum number of concurrent worker
     * threads any single {@code PARALLEL} step can ever create. Chosen generously relative to a
     * realistic observational fan-out (reading a handful of independent page regions or
     * already-open tabs concurrently) while keeping that thread count, and the structural size of
     * the resulting plan/tree/recording, small and predictable - a workflow author who genuinely
     * needs more independent concurrent reads than this should reconsider the workflow's shape
     * rather than obtain an ever-larger single fan-out.
     */
    static final int MAX_PARALLEL_BRANCHES = 8;

    /**
     * Maximum number of diagnostics {@link Builder#validate()} accumulates before setting {@link
     * WorkflowValidationReport#diagnosticsTruncated()} and discarding the rest - a
     * caller-controlled definition could otherwise force unbounded retention of diagnostic text
     * (see {@code docs/workflow.md#validation-report}). {@link Builder#build()} never accumulates
     * more than one, since it throws on the first.
     */
    static final int MAX_VALIDATION_DIAGNOSTICS = 256;

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
     * Returns the declared {@code maxIterations} bound for the {@link WorkflowStepType#LOOP} step
     * identified by {@code stepId}, if this workflow declares one with that ID at any nesting depth
     * (inside any conditional branch or loop body) - added in 1.3.0. Empty if no such step exists,
     * or if {@code stepId} identifies a step of a different type.
     *
     * <p>This is the single piece of a {@code LoopWorkflowStep}'s otherwise package-private
     * structure this module exposes across the module boundary: {@code
     * io.webagent4j.recording.replay.ReplayValidator} uses it to reject a recording whose recorded
     * iteration count for a loop exceeds what the live workflow actually authorizes - a check the
     * recording's own structural plan cannot make on its own, since a {@link WorkflowExecutionPlan}
     * deliberately never encodes {@code maxIterations} (see {@code
     * docs/workflow.md#bounded-loops}).
     */
    public Optional<Integer> loopMaxIterations(WorkflowStepId stepId) {
        Objects.requireNonNull(stepId, "stepId");
        return findLoopMaxIterations(steps, stepId);
    }

    private static Optional<Integer> findLoopMaxIterations(
            List<IWorkflowStep> steps, WorkflowStepId stepId) {
        for (IWorkflowStep step : steps) {
            // Safe: IWorkflowStep is sealed and permits only AWorkflowStep.
            AWorkflowStep concreteStep = (AWorkflowStep) step;
            if (concreteStep instanceof LoopWorkflowStep loop) {
                if (step.id().equals(stepId)) {
                    return Optional.of(loop.maxIterations());
                }
                Optional<Integer> nested = findLoopMaxIterations(loop.body(), stepId);
                if (nested.isPresent()) {
                    return nested;
                }
            } else if (concreteStep instanceof ConditionalWorkflowStep conditional) {
                Optional<Integer> thenMatch =
                        findLoopMaxIterations(conditional.thenSteps(), stepId);
                if (thenMatch.isPresent()) {
                    return thenMatch;
                }
                Optional<Integer> elseMatch =
                        findLoopMaxIterations(conditional.elseSteps().orElse(List.of()), stepId);
                if (elseMatch.isPresent()) {
                    return elseMatch;
                }
            } else if (concreteStep instanceof ParallelWorkflowStep parallel) {
                for (List<IWorkflowStep> branch : parallel.branches()) {
                    Optional<Integer> branchMatch = findLoopMaxIterations(branch, stepId);
                    if (branchMatch.isPresent()) {
                        return branchMatch;
                    }
                }
            }
        }
        return Optional.empty();
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
     * themselves (see {@code docs/workflow.md#conditions}). {@link #validate()} shares that exact
     * same property and the exact same underlying analysis - see its own Javadoc.
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
            analyze(true);
            return new Workflow(
                    id,
                    List.copyOf(requiredInputs),
                    List.copyOf(optionalInputs),
                    List.copyOf(steps));
        }

        /**
         * Explains this builder's <em>current</em> definition state as a structured {@link
         * WorkflowValidationReport}, without ever throwing and without ever mutating this builder:
         * calling {@code validate()} any number of times, in any order relative to {@link #step},
         * {@link #requiredInput}, {@link #optionalInput}, or {@link #build()}, never changes this
         * builder's own state or any subsequent call's result for the same state.
         *
         * <p><b>Single source of truth:</b> this method and {@link #build()} both delegate to the
         * exact same internal analysis ({@link #analyze}) - the same recursive traversal, the same
         * definite-assignment rules, the same nesting-depth bound - so a definition {@link
         * #build()} accepts always produces {@link WorkflowValidationReport#valid()}, and one it
         * rejects always produces at least one diagnostic here. There is no second, independently
         * maintained validation algorithm that could drift out of sync with {@link #build()}'s own
         * rules.
         *
         * <p><b>Zero side effects:</b> like {@link #build()}, this never calls an {@link
         * IWorkflowActionFactory}, never evaluates an {@link IWorkflowCondition} ({@code
         * evaluate()} is never invoked - only the side-effect-free {@code referencedVariables()}
         * metadata method {@link #build()} already reads today), and never touches a backend,
         * browser, or network resource.
         *
         * <p><b>Fail-fast vs. accumulate:</b> {@link #build()} throws on the very first invariant
         * violation it encounters, exactly as before. This method instead continues analyzing every
         * remaining structurally independent part of the definition it safely can, so a caller sees
         * every diagnostic reachable from that continued walk in one report - but it never resumes
         * analyzing a step (or a conditional's branches) whose own violation would make continuing
         * to interpret its contribution unsafe: such a step is simply skipped, and the walk
         * continues with whatever structurally follows it. See {@code
         * docs/workflow.md#validation-report} for the exact per-invariant continuation rules.
         */
        public WorkflowValidationReport validate() {
            Analysis analysis = analyze(false);
            List<WorkflowValidationOutput> outputs = new ArrayList<>(analysis.producers.size());
            for (Map.Entry<WorkflowVariable<?>, WorkflowStepId> entry :
                    analysis.producers.entrySet()) {
                WorkflowVariable<?> variable = entry.getKey();
                outputs.add(
                        new WorkflowValidationOutput(
                                entry.getValue(), variable, analysis.definite.contains(variable)));
            }
            return new WorkflowValidationReport(
                    id,
                    analysis.diagnostics,
                    analysis.truncated,
                    List.copyOf(requiredInputs),
                    List.copyOf(optionalInputs),
                    outputs,
                    analysis.stepCount,
                    analysis.conditionalCount,
                    analysis.maxObservedDepth);
        }

        /**
         * Runs the single shared structural analysis this builder's current state undergoes for
         * both {@link #build()} ({@code failFast=true}: throws {@link IllegalArgumentException} on
         * the first violation, with the exact same message {@code build()} has always thrown) and
         * {@link #validate()} ({@code failFast=false}: every violation becomes a {@link
         * WorkflowValidationDiagnostic} instead, up to {@link #MAX_VALIDATION_DIAGNOSTICS}).
         */
        private Analysis analyze(boolean failFast) {
            Analysis analysis = new Analysis(failFast);
            if (steps.isEmpty()) {
                analysis.report(
                        WorkflowValidationCode.EMPTY_STEP_LIST,
                        null,
                        null,
                        "workflow '" + id + "' must declare at least one step");
            }

            Map<String, WorkflowVariable<?>> byName = new LinkedHashMap<>();
            Set<String> declaredInputNames = new HashSet<>();
            registerInputDeclarations(byName, declaredInputNames, requiredInputs, analysis);
            registerInputDeclarations(byName, declaredInputNames, optionalInputs, analysis);

            analysis.declared.addAll(requiredInputs);
            analysis.declared.addAll(optionalInputs);
            analysis.definite.addAll(analysis.declared);

            Set<WorkflowStepId> seenStepIds = new HashSet<>();
            for (IWorkflowStep step : steps) {
                validateStep(
                        step,
                        byName,
                        analysis.declared,
                        analysis.definite,
                        seenStepIds,
                        0,
                        false,
                        analysis);
            }
            return analysis;
        }

        /**
         * Mutable accumulator threaded through one {@link #analyze} call: either throws immediately
         * on the first reported violation ({@code failFast}), or records every one, bounded, for
         * {@link #validate()} - see {@link #report}. {@code declared}/{@code definite} hold the
         * final, top-level sets once analysis completes; {@code producers} correlates each
         * successfully declared output back to the step that declares it, for {@link
         * WorkflowValidationOutput#producerStepId()}.
         */
        private static final class Analysis {
            private final boolean failFast;
            private final List<WorkflowValidationDiagnostic> diagnostics = new ArrayList<>();
            private final Map<WorkflowVariable<?>, WorkflowStepId> producers =
                    new LinkedHashMap<>();
            private final Set<WorkflowVariable<?>> declared = new LinkedHashSet<>();
            private final Set<WorkflowVariable<?>> definite = new LinkedHashSet<>();
            private boolean truncated;
            private int stepCount;
            private int conditionalCount;
            private int maxObservedDepth;

            private Analysis(boolean failFast) {
                this.failFast = failFast;
            }

            /**
             * Records one violation: throws immediately, exactly as {@link #build()} always has,
             * when {@code failFast}; otherwise appends a diagnostic (dropping it, and setting
             * {@code truncated}, once {@link #MAX_VALIDATION_DIAGNOSTICS} is reached) and returns
             * normally so the caller can decide how to safely continue.
             */
            private void report(
                    WorkflowValidationCode code,
                    WorkflowStepId stepId,
                    String variableName,
                    String message) {
                if (failFast) {
                    throw new IllegalArgumentException(message);
                }
                addDiagnostic(code, stepId, variableName, message);
            }

            /**
             * Same as {@link #report}, but preserves {@code cause} only for the thrown exception.
             */
            private void reportWithCause(
                    WorkflowValidationCode code,
                    WorkflowStepId stepId,
                    String variableName,
                    String message,
                    RuntimeException cause) {
                if (failFast) {
                    throw new IllegalArgumentException(message, cause);
                }
                addDiagnostic(code, stepId, variableName, message);
            }

            private void addDiagnostic(
                    WorkflowValidationCode code,
                    WorkflowStepId stepId,
                    String variableName,
                    String message) {
                if (diagnostics.size() >= MAX_VALIDATION_DIAGNOSTICS) {
                    truncated = true;
                    return;
                }
                diagnostics.add(
                        new WorkflowValidationDiagnostic(
                                code,
                                WorkflowValidationSeverity.ERROR,
                                Optional.ofNullable(stepId),
                                Optional.ofNullable(variableName),
                                message));
            }

            private void observeDepth(int depth) {
                if (depth > maxObservedDepth) {
                    maxObservedDepth = depth;
                }
            }

            private void recordProducer(WorkflowVariable<?> output, WorkflowStepId stepId) {
                producers.put(output, stepId);
            }
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
         *
         * <p>A duplicate step ID or an over-depth conditional is reported and this step's own
         * contribution is skipped entirely - neither its condition, output, nor (for a conditional)
         * its branches are analyzed further - since nothing about its contents can be trusted once
         * either invariant is violated; the walk still continues with whatever structurally follows
         * this step. Every other violation site below documents its own, narrower continuation
         * rule.
         *
         * <p>{@code insideParallel} is {@code true} for every step reachable from inside any {@link
         * WorkflowStepType#PARALLEL} branch, at any nesting depth (including a further conditional,
         * loop, or nested parallel step's own contents) - added in 1.3.0, see {@link
         * #validateParallelBranches}. When {@code true}, an {@link ActionWorkflowStep} whose
         * factory is not provably parallel-safe is reported with {@link
         * WorkflowValidationCode#PARALLEL_BRANCH_UNSAFE_STEP}; this step's own output, guard, and
         * (for a container step) its branches are still analyzed normally afterward, exactly like
         * every other non-fatal violation this method reports.
         */
        private static void validateStep(
                IWorkflowStep step,
                Map<String, WorkflowVariable<?>> byName,
                Set<WorkflowVariable<?>> declared,
                Set<WorkflowVariable<?>> definite,
                Set<WorkflowStepId> seenStepIds,
                int conditionalDepth,
                boolean insideParallel,
                Analysis analysis) {
            if (!seenStepIds.add(step.id())) {
                analysis.report(
                        WorkflowValidationCode.DUPLICATE_STEP_ID,
                        step.id(),
                        null,
                        "duplicate step ID '" + step.id() + "'");
                return;
            }
            analysis.stepCount++;

            Optional<IWorkflowCondition> guard = step.condition();
            guard.ifPresent(condition -> validateCondition(step, condition, definite, analysis));

            // Safe: IWorkflowStep is sealed and permits only AWorkflowStep (see its Javadoc), so
            // every instance reachable here is guaranteed to be one.
            AWorkflowStep concreteStep = (AWorkflowStep) step;
            if (insideParallel && concreteStep instanceof ActionWorkflowStep<?> action) {
                if (!isDeclaredParallelSafe(action.factory())) {
                    analysis.report(
                            WorkflowValidationCode.PARALLEL_BRANCH_UNSAFE_STEP,
                            step.id(),
                            null,
                            "step '"
                                    + step.id()
                                    + "' is inside a PARALLEL branch but its action factory does"
                                    + " not declare itself parallel-safe (see"
                                    + " IWorkflowActionFactory#isParallelSafe) - no ACTION step is"
                                    + " ever treated as safe to run concurrently by default");
                }
            }
            if (concreteStep instanceof ConditionalWorkflowStep conditional) {
                analysis.conditionalCount++;
                int nestedDepth = conditionalDepth + 1;
                analysis.observeDepth(nestedDepth);
                if (nestedDepth > MAX_CONDITIONAL_NESTING_DEPTH) {
                    analysis.report(
                            WorkflowValidationCode.CONDITIONAL_DEPTH_EXCEEDED,
                            step.id(),
                            null,
                            "step '"
                                    + step.id()
                                    + "' exceeds the maximum supported conditional nesting depth"
                                    + " of "
                                    + MAX_CONDITIONAL_NESTING_DEPTH);
                    return;
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
                                nestedDepth,
                                insideParallel,
                                analysis);
                BranchResult elseResult =
                        validateBranch(
                                conditional.elseSteps().orElse(List.of()),
                                byName,
                                declared,
                                definite,
                                seenStepIds,
                                nestedDepth,
                                insideParallel,
                                analysis);
                mergeBranchDeclarations(
                        byName, declared, thenResult.declared(), elseResult.declared(), analysis);
                mergeBranchDefinite(definite, thenResult.definite(), elseResult.definite());
                return;
            }
            if (concreteStep instanceof LoopWorkflowStep loop) {
                int nestedDepth = conditionalDepth + 1;
                analysis.observeDepth(nestedDepth);
                if (nestedDepth > MAX_CONTROL_FLOW_NESTING_DEPTH) {
                    analysis.report(
                            WorkflowValidationCode.LOOP_NESTING_DEPTH_EXCEEDED,
                            step.id(),
                            null,
                            "step '"
                                    + step.id()
                                    + "' exceeds the maximum supported control-flow nesting depth"
                                    + " of "
                                    + MAX_CONTROL_FLOW_NESTING_DEPTH);
                    return;
                }
                if (loop.maxIterations() < 1 || loop.maxIterations() > MAX_LOOP_ITERATIONS) {
                    analysis.report(
                            WorkflowValidationCode.LOOP_INVALID_MAX_ITERATIONS,
                            step.id(),
                            null,
                            "step '"
                                    + step.id()
                                    + "' declares maxIterations="
                                    + loop.maxIterations()
                                    + ", which must be between 1 and "
                                    + MAX_LOOP_ITERATIONS
                                    + " (inclusive)");
                    return;
                }
                validateCondition(step, loop.continueCondition(), definite, analysis);
                // The loop's body is validated exactly like a single ifThen branch with no else:
                // its own newly-declared outputs join the outer, guard-independent `declared` set
                // (so a sibling step can never redeclare one of them), but never `definite` - the
                // loop may run zero iterations, so nothing it might produce can ever be statically
                // guaranteed to a later step, exactly like a guarded producer's output (see
                // WorkflowSteps#loop's Javadoc).
                BranchResult bodyResult =
                        validateBranch(
                                loop.body(),
                                byName,
                                declared,
                                definite,
                                seenStepIds,
                                nestedDepth,
                                insideParallel,
                                analysis);
                mergeOneBranchDeclaration(byName, declared, bodyResult.declared(), analysis);
                mergeBranchDefinite(definite, bodyResult.definite(), definite);
                return;
            }
            if (concreteStep instanceof ParallelWorkflowStep parallel) {
                int nestedDepth = conditionalDepth + 1;
                analysis.observeDepth(nestedDepth);
                if (nestedDepth > MAX_CONTROL_FLOW_NESTING_DEPTH) {
                    analysis.report(
                            WorkflowValidationCode.PARALLEL_NESTING_DEPTH_EXCEEDED,
                            step.id(),
                            null,
                            "step '"
                                    + step.id()
                                    + "' exceeds the maximum supported control-flow nesting depth"
                                    + " of "
                                    + MAX_CONTROL_FLOW_NESTING_DEPTH);
                    return;
                }
                List<List<IWorkflowStep>> branches = parallel.branches();
                if (branches.size() < MIN_PARALLEL_BRANCHES
                        || branches.size() > MAX_PARALLEL_BRANCHES) {
                    analysis.report(
                            WorkflowValidationCode.PARALLEL_INVALID_BRANCH_COUNT,
                            step.id(),
                            null,
                            "step '"
                                    + step.id()
                                    + "' declares "
                                    + branches.size()
                                    + " branches, which must be between "
                                    + MIN_PARALLEL_BRANCHES
                                    + " and "
                                    + MAX_PARALLEL_BRANCHES
                                    + " (inclusive)");
                    return;
                }
                validateParallelBranches(
                        branches,
                        byName,
                        declared,
                        definite,
                        seenStepIds,
                        nestedDepth,
                        guard.isPresent(),
                        analysis);
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
                                            guard.isPresent(),
                                            analysis));
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
         * {@code branchSteps} starts at - see {@link #validateStep}. {@code insideParallel} is
         * simply threaded through to every step in {@code branchSteps} unchanged - see {@link
         * #validateStep}'s Javadoc.
         */
        private static BranchResult validateBranch(
                List<IWorkflowStep> branchSteps,
                Map<String, WorkflowVariable<?>> byName,
                Set<WorkflowVariable<?>> declared,
                Set<WorkflowVariable<?>> definite,
                Set<WorkflowStepId> seenStepIds,
                int conditionalDepth,
                boolean insideParallel,
                Analysis analysis) {
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
                        conditionalDepth,
                        insideParallel,
                        analysis);
            }
            return new BranchResult(branchDeclared, branchDefinite);
        }

        /**
         * Validates every branch of one {@link WorkflowStepType#PARALLEL} step, in definition
         * order, committing each branch's own contribution to the shared {@code byName}/{@code
         * declared}/{@code definite} sets immediately after that branch is validated - before the
         * next branch is validated - rather than deferring every branch's merge to the end the way
         * {@link #validateStep}'s {@code CONDITIONAL} handling does for its two mutually exclusive
         * branches.
         *
         * <p>This sequential-commit order is exactly what makes cross-branch output collisions fail
         * closed automatically, using the exact same mechanism (and the exact same {@link
         * WorkflowValidationCode#OUTPUT_COLLISION}/{@link
         * WorkflowValidationCode#OUTPUT_TYPE_MISMATCH}/ {@link
         * WorkflowValidationCode#OUTPUT_SECRET_CLASSIFICATION_MISMATCH} diagnostics) already used
         * for a single step's own output: since every {@code PARALLEL} branch genuinely executes
         * (unlike {@code ifElse}'s two mutually exclusive branches), branch 1 is validated against
         * a {@code declared}/{@code byName} baseline that already includes branch 0's committed
         * outputs - so branch 1 attempting to redeclare one of them, even identically, is caught by
         * {@link #registerStepOutput} during branch 1's own validation exactly like any other
         * collision, never silently treated as a safe redeclaration the way two conditional
         * branches may be.
         *
         * <p>{@code definite} is folded by <b>union</b>, not the intersection {@link
         * #mergeBranchDefinite} computes for a conditional's two mutually exclusive branches: every
         * declared {@code PARALLEL} branch unconditionally runs once this step is reached and its
         * own optional guard (if any) evaluates {@code true} - there is no "only one branch
         * actually runs" exclusivity to intersect over, and no "may run zero times" loop-body
         * caveat either. Nothing becomes definite when {@code parallelGuarded} is {@code true}: the
         * whole step, and therefore every one of its branches, may be skipped entirely.
         */
        private static void validateParallelBranches(
                List<List<IWorkflowStep>> branches,
                Map<String, WorkflowVariable<?>> byName,
                Set<WorkflowVariable<?>> declared,
                Set<WorkflowVariable<?>> definite,
                Set<WorkflowStepId> seenStepIds,
                int nestedDepth,
                boolean parallelGuarded,
                Analysis analysis) {
            for (List<IWorkflowStep> branch : branches) {
                BranchResult branchResult =
                        validateBranch(
                                branch,
                                byName,
                                declared,
                                definite,
                                seenStepIds,
                                nestedDepth,
                                true,
                                analysis);
                mergeOneBranchDeclaration(byName, declared, branchResult.declared(), analysis);
                if (!parallelGuarded) {
                    definite.addAll(branchResult.definite());
                }
            }
        }

        /**
         * Calls {@code factory.isParallelSafe()} defensively: a thrown {@link RuntimeException} is
         * treated as {@code false} (fail-closed), exactly like {@link #validateCondition} treats a
         * malformed {@code referencedVariables()} as untrustworthy rather than propagating the
         * exception.
         */
        private static boolean isDeclaredParallelSafe(IWorkflowActionFactory<?> factory) {
            try {
                return factory.isParallelSafe();
            } catch (RuntimeException e) {
                return false;
            }
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
         * guard, never to decide what a later step may statically rely on being present. A
         * conflicting candidate is skipped - never added to {@code declared} - and folding
         * continues with the rest of the branch's own declarations, exactly mirroring {@link
         * #registerStepOutput}'s own continuation rule.
         */
        private static void mergeBranchDeclarations(
                Map<String, WorkflowVariable<?>> byName,
                Set<WorkflowVariable<?>> declared,
                Set<WorkflowVariable<?>> thenDeclared,
                Set<WorkflowVariable<?>> elseDeclared,
                Analysis analysis) {
            mergeOneBranchDeclaration(byName, declared, thenDeclared, analysis);
            mergeOneBranchDeclaration(byName, declared, elseDeclared, analysis);
        }

        private static void mergeOneBranchDeclaration(
                Map<String, WorkflowVariable<?>> byName,
                Set<WorkflowVariable<?>> declared,
                Set<WorkflowVariable<?>> branchDeclared,
                Analysis analysis) {
            for (WorkflowVariable<?> candidate : branchDeclared) {
                if (declared.contains(candidate)) {
                    continue;
                }
                WorkflowVariable<?> existing = byName.putIfAbsent(candidate.name(), candidate);
                if (existing != null && !existing.equals(candidate)) {
                    analysis.report(
                            mismatchCode(existing, candidate),
                            null,
                            candidate.name(),
                            "branch output '"
                                    + candidate.name()
                                    + "' conflicts with an existing input or output declared with"
                                    + " a different type or secret status");
                    continue;
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
         * for {@code declared} - see {@code docs/workflow.md#branching}. Never throws and never
         * reports a diagnostic: purely a set computation over already-validated names, shared
         * unchanged by {@link #build()} and {@link #validate()}.
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

        /**
         * Registers each input's declaration, reporting a diagnostic for every name declared more
         * than once (across {@code requiredInputs} and {@code optionalInputs} combined) and keeping
         * only the first declaration of that name - never mutating {@code byName} for a duplicate -
         * before continuing to register the rest, independently of any other duplicate found.
         */
        private static void registerInputDeclarations(
                Map<String, WorkflowVariable<?>> byName,
                Set<String> declaredInputNames,
                List<WorkflowVariable<?>> inputs,
                Analysis analysis) {
            for (WorkflowVariable<?> variable : inputs) {
                if (!declaredInputNames.add(variable.name())) {
                    analysis.report(
                            WorkflowValidationCode.DUPLICATE_INPUT_DECLARATION,
                            null,
                            variable.name(),
                            "input '" + variable.name() + "' is declared more than once");
                    continue;
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
         * that producer entirely (see {@code docs/workflow.md#conditions}). A metadata violation (a
         * thrown exception, a {@code null} set, or a {@code null} entry) is reported and the
         * remaining reference check for this condition is skipped - nothing about {@code
         * referencedVariables()}'s result can be trusted once it has violated its own contract -
         * but this step's own output, and whatever structurally follows it, are still analyzed
         * normally.
         */
        private static void validateCondition(
                IWorkflowStep step,
                IWorkflowCondition condition,
                Set<WorkflowVariable<?>> definite,
                Analysis analysis) {
            Set<WorkflowVariable<?>> referenced;
            try {
                referenced = condition.referencedVariables();
            } catch (RuntimeException e) {
                analysis.reportWithCause(
                        WorkflowValidationCode.CONDITION_METADATA_INVALID,
                        step.id(),
                        null,
                        "step '"
                                + step.id()
                                + "' condition's referencedVariables() threw "
                                + e.getClass().getSimpleName(),
                        e);
                return;
            }
            if (referenced == null) {
                analysis.report(
                        WorkflowValidationCode.CONDITION_METADATA_INVALID,
                        step.id(),
                        null,
                        "step '"
                                + step.id()
                                + "' condition returned a null referencedVariables() set");
                return;
            }
            for (WorkflowVariable<?> variable : referenced) {
                if (variable == null) {
                    analysis.report(
                            WorkflowValidationCode.CONDITION_METADATA_INVALID,
                            step.id(),
                            null,
                            "step '"
                                    + step.id()
                                    + "' condition's referencedVariables() contains a null"
                                    + " entry");
                    continue;
                }
                if (!definite.contains(variable)) {
                    analysis.report(
                            WorkflowValidationCode.OUTPUT_NOT_DEFINITELY_AVAILABLE,
                            step.id(),
                            variable.name(),
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
         * docs/workflow.md#conditions}). A conflicting or colliding output is reported and simply
         * never registered - neither into {@code declared} nor {@code definite}, and never recorded
         * as a producer - while the walk continues with whatever structurally follows this step.
         */
        private static void registerStepOutput(
                Map<String, WorkflowVariable<?>> byName,
                Set<WorkflowVariable<?>> declared,
                Set<WorkflowVariable<?>> definite,
                IWorkflowStep step,
                WorkflowVariable<?> output,
                boolean guarded,
                Analysis analysis) {
            WorkflowVariable<?> existing = byName.putIfAbsent(output.name(), output);
            if (existing != null && !existing.equals(output)) {
                analysis.report(
                        mismatchCode(existing, output),
                        step.id(),
                        output.name(),
                        "step '"
                                + step.id()
                                + "' output '"
                                + output.name()
                                + "' conflicts with an existing input or output declared with a"
                                + " different type or secret status");
                return;
            }
            if (!declared.add(output)) {
                analysis.report(
                        WorkflowValidationCode.OUTPUT_COLLISION,
                        step.id(),
                        output.name(),
                        "step '"
                                + step.id()
                                + "' output '"
                                + output.name()
                                + "' collides with an existing input or an earlier step's"
                                + " output");
                return;
            }
            analysis.recordProducer(output, step.id());
            if (!guarded) {
                definite.add(output);
            }
        }

        /**
         * Classifies why {@code existing} and {@code candidate} - two declarations of the same
         * output name - disagree: a differing {@link WorkflowVariable#secret()} classification is
         * reported as such even when the runtime type also differs, since a secret/public mismatch
         * is the more safety-relevant fact.
         */
        private static WorkflowValidationCode mismatchCode(
                WorkflowVariable<?> existing, WorkflowVariable<?> candidate) {
            if (existing.secret() != candidate.secret()) {
                return WorkflowValidationCode.OUTPUT_SECRET_CLASSIFICATION_MISMATCH;
            }
            return WorkflowValidationCode.OUTPUT_TYPE_MISMATCH;
        }
    }
}
