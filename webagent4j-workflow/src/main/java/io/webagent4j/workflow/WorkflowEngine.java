package io.webagent4j.workflow;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Deterministic executor for {@link Workflow} definitions.
 *
 * <p>{@code WorkflowEngine} itself is stateless and safe to reuse: {@link #execute} constructs a
 * private, per-call session holding all mutable execution state (variables, discovered secrets,
 * step results), mirroring how {@code BrowserCrawler} isolates one crawl's state per call. Two
 * {@code execute} calls against the same {@link Workflow} - even concurrently - never share
 * variable state, discovered secrets, or step results.
 *
 * <p><b>Execution model:</b> deterministic and fail-fast. Every step runs, in definition order, on
 * the exact thread that calls {@link #execute} - with one bounded exception, added in 1.3.0: a
 * {@link WorkflowStepType#PARALLEL} step's own declared branches, each of which runs on its own
 * dedicated worker thread drawn from a small executor this engine creates, owns, and always shuts
 * down before that step's own result is produced (see {@link #executeWithTree} and {@code
 * docs/workflow.md#parallel}). Outside a {@code PARALLEL} step, there is no {@code
 * ExecutorService}, no {@code CompletableFuture}, and no parallelism anywhere in this engine, so a
 * caller-owned resource an action factory closes over (an {@code IPage}, for example) is never
 * touched from a different thread unless that action is itself declared parallel-safe and placed
 * inside a {@code PARALLEL} branch. The first step that fails stops execution immediately; every
 * later step is recorded as {@link WorkflowStepStatus#NOT_RUN}. A failed action is never retried:
 * {@code WorkflowEngine} adds no retry layer on top of the action layer's own safe resolution
 * retries.
 *
 * <p><b>No workflow-wide timeout, no cancellation</b> outside a {@code PARALLEL} step's own
 * documented cooperative cancellation of its sibling branches: this phase relies entirely on the
 * timeout semantics of the underlying action/browser backend for any one step; there is no
 * dishonest Java-side deadline wrapped around an otherwise-unbounded call (see {@code
 * docs/workflow.md#limitations}).
 *
 * <p><b>Resource ownership:</b> {@code WorkflowEngine} owns nothing a caller supplied - no browser,
 * no page, no action backend - and never closes anything an {@link IWorkflowActionFactory}
 * captures. The bounded executor a {@code PARALLEL} step creates for its own branches is the one
 * resource this engine does own outright, and it is always shut down - with no orphaned task and no
 * leaked thread - before that step's own result is produced, whether it succeeds, fails, or is
 * interrupted.
 *
 * <p><b>Runtime failure contract:</b> a {@link RuntimeException} thrown from a supported extension
 * hook - {@link IWorkflowCondition#evaluate}, {@link IWorkflowCondition#describe}, an {@link
 * IWorkflowActionFactory}, or {@link io.webagent4j.action.IPreparedAction#execute()} - is caught
 * and converted into a structured {@link WorkflowResult}; it never escapes {@link #execute}. {@link
 * IWorkflowCondition#referencedVariables} is a separate, definition-time-only contract validated by
 * {@link Workflow.Builder#build()} - this engine never invokes it. A {@link Throwable} that is not
 * a {@code RuntimeException} (a JVM {@link Error}) is never caught. Programmer misuse at
 * definition-construction time (an invalid {@link Workflow.Builder#build()} call) still throws
 * {@link IllegalArgumentException} directly, since that is a build-time contract violation, not a
 * runtime execution outcome.
 *
 * <p><b>Condition-result finalization:</b> a <em>custom</em> condition's {@code describe()} is
 * called at most once, at evaluation time - it must be, since only the condition's own
 * implementation can render its text - and the resulting raw text is kept internally, unredacted
 * and unbounded, until the workflow terminates (see {@code PendingStepResult}/{@code
 * PendingConditionResult}), mirroring how {@link WorkflowOutputs}' safe previews are computed once
 * at final-result time. A <em>built-in</em> condition ({@link WorkflowConditions}) instead defers
 * its description's rendering itself to finalization time (see {@link
 * IDeferredConditionDescription}), so no unbounded intermediate text is ever retained for such a
 * condition - see {@code docs/workflow.md#resource-bounded-diagnostics}. Either way, a secret
 * revealed by a later successful step still retroactively masks an earlier step's already-recorded
 * {@code SKIPPED}/{@code SUCCEEDED} condition description before it is ever redacted or bounded,
 * exactly like it already retroactively masks an earlier public output. A terminal {@link
 * WorkflowFailure}'s own message is redacted and bounded immediately when it is constructed, not
 * deferred: once a step fails, no later step runs, so no later secret can ever be discovered -
 * except, for a step inside one kept {@link WorkflowStepType#PARALLEL} branch, a secret discovered
 * by an earlier-declared sibling branch that already completed, which is folded in before that
 * branch's own failure message is finalized (see {@code docs/workflow.md#parallel}).
 *
 * <p><b>Structured execution tree:</b> every {@link Session#run()} call builds, in the same single
 * pass that already produces {@link WorkflowResult#steps()}, a parallel hierarchical view - see
 * {@link #executeWithTree(Workflow, WorkflowInputs)} and {@code docs/workflow.md#execution-tree}.
 * Both views share the exact same {@link WorkflowStepResult} instances; the tree is never built by
 * a second interpretation of the flat list, and building or reading it never evaluates a condition,
 * invokes an action, or selects a branch again.
 */
public final class WorkflowEngine {

    /**
     * Maximum cumulative number of step-node execution attempts (every {@code ACTION}/{@code
     * ASSIGN}/{@code CONDITIONAL}/{@code LOOP}/{@code PARALLEL} step position reached, plus every
     * {@code LOOP_ITERATION} continuation check attempted and every {@code PARALLEL_BRANCH}
     * launched) a single {@link #execute} call may perform - added in 1.3.0. A workflow with no
     * {@link WorkflowStepType#LOOP} or {@link WorkflowStepType#PARALLEL} steps can never approach
     * this, since its total step count is already fixed by its definition; it exists specifically
     * to guard against a nested-loop, or loop-times-parallel-fan-out, structure that is locally
     * within every individual {@code maxIterations}/branch-count bound yet combinatorially
     * explosive once multiplied together (see {@code docs/workflow.md#bounded-loops}). Reaching it
     * fails closed with {@link WorkflowFailureType#EXECUTED_NODE_BUDGET_EXCEEDED} - never a silent
     * truncation. This single counter is shared, atomically, across every concurrently running
     * {@code PARALLEL} branch of the same execution, so the bound reflects the true cumulative
     * total regardless of how many branches are consuming it at once - only which exact node
     * attempt is the one that happens to observe the bound being reached can vary run to run under
     * real concurrency; the bound itself, and every other node's own outcome, never does.
     */
    static final int MAX_EXECUTED_WORKFLOW_NODES = 100_000;

    /** Creates a reusable, stateless engine. */
    public WorkflowEngine() {}

    /**
     * Executes {@code workflow} against {@code inputs} on the calling thread, returning a
     * structured result rather than throwing for any expected failure.
     */
    public WorkflowResult execute(Workflow workflow, WorkflowInputs inputs) {
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(inputs, "inputs");
        return new Session(workflow, inputs).run().result();
    }

    /**
     * Same single execution as {@link #execute(Workflow, WorkflowInputs)}, additionally returning
     * the structured {@link WorkflowExecutionTree} built from that identical pass - see the class
     * Javadoc. {@link WorkflowExecution#result()} is exactly what {@link #execute(Workflow,
     * WorkflowInputs)} returns for the same inputs.
     */
    public WorkflowExecution executeWithTree(Workflow workflow, WorkflowInputs inputs) {
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(inputs, "inputs");
        return new Session(workflow, inputs).run();
    }

    /** One execution's private, isolated mutable state - never shared across calls. */
    private static final class Session {

        private record VariableEntry(WorkflowVariable<?> variable, Object value) {}

        /**
         * One execution path's own variables, discovered secrets, and published outputs - added in
         * 1.3.0, extracted from what used to be {@code Session}'s own single set of mutable fields,
         * so a {@link WorkflowStepType#PARALLEL} branch can run against its own isolated {@link
         * #fork()} of the state known immediately before that step, rather than the live, singly-
         * owned state every other step reads and mutates directly. Outside a {@code PARALLEL} step,
         * exactly one {@code ExecutionState} instance exists for the whole execution, threaded
         * through every method exactly as the old direct field access did - so this refactor
         * changes nothing observable about non-parallel execution.
         */
        private static final class ExecutionState {
            private final Map<String, VariableEntry> variables;
            private final List<String> activeSecrets;
            private final WorkflowOutputs.Builder outputs;

            private ExecutionState(
                    Map<String, VariableEntry> variables,
                    List<String> activeSecrets,
                    WorkflowOutputs.Builder outputs) {
                this.variables = variables;
                this.activeSecrets = activeSecrets;
                this.outputs = outputs;
            }

            static ExecutionState fresh() {
                return new ExecutionState(
                        new LinkedHashMap<>(), new ArrayList<>(), new WorkflowOutputs.Builder());
            }

            /**
             * Returns an independent copy: a {@link WorkflowStepType#PARALLEL} branch mutates only
             * its own fork, never this state or any sibling branch's own fork - see {@code
             * docs/workflow.md#parallel}.
             */
            ExecutionState fork() {
                return new ExecutionState(
                        new LinkedHashMap<>(variables),
                        new ArrayList<>(activeSecrets),
                        outputs.copy());
            }

            /**
             * Folds {@code branchState} - a completed, <b>kept</b> {@link
             * WorkflowStepType#PARALLEL} branch's own fork - into this state, in place. Safe to
             * call for successive branches in definition order: {@code branchState} was forked from
             * this exact state before any branch ran, so it already contains this state's own prior
             * entries plus only that one branch's own new contributions, which {@link
             * Workflow.Builder#build()}'s parallel output-collision check has already proven cannot
             * name-collide with any other kept branch's own contributions.
             */
            void mergeFrom(ExecutionState branchState) {
                variables.putAll(branchState.variables);
                activeSecrets.addAll(branchState.activeSecrets);
                outputs.mergeFrom(branchState.outputs);
            }
        }

        /**
         * Result of calling a (possibly custom) condition's {@code describe()} once: crash-safe,
         * but deliberately not yet redacted or bounded - see {@link PendingConditionResult}.
         */
        private record RawDescription(String text, String failureMessage, String underlyingType) {
            static RawDescription of(String text) {
                return new RawDescription(text, null, null);
            }

            static RawDescription failed(String failureMessage, String underlyingType) {
                return new RawDescription(null, failureMessage, underlyingType);
            }

            boolean failed() {
                return text == null;
            }
        }

        /**
         * A condition's outcome captured at evaluation time. {@code finalizeDescription} produces
         * the final, safe description when applied to the workflow's complete final {@link
         * SecretRedactor} - see {@link #finalizeStepResult}. For a custom condition this closes
         * over already-computed, crash-safe raw text (unredacted, unbounded, exactly as retained
         * today); for a built-in {@link IDeferredConditionDescription} condition, it defers that
         * condition's own rendering to the moment it is applied, so no unbounded intermediate text
         * is ever retained in between (see {@code WF-MEM-001}).
         */
        private record PendingConditionResult(
                boolean outcome, Function<SecretRedactor, String> finalizeDescription) {}

        /**
         * Outcome of {@link Session#evaluateConditionSafely}: either a captured decision (ready to
         * become a {@link PendingConditionResult}) or a description of why the evaluation itself
         * failed - never both, mirroring {@link RawDescription}.
         */
        private record ConditionEvaluationResult(
                boolean outcome,
                PendingConditionResult pending,
                String failureMessage,
                String underlyingTypeName) {
            static ConditionEvaluationResult success(
                    boolean outcome, PendingConditionResult pending) {
                return new ConditionEvaluationResult(outcome, pending, null, null);
            }

            static ConditionEvaluationResult failed(
                    String failureMessage, String underlyingTypeName) {
                return new ConditionEvaluationResult(
                        false, null, failureMessage, underlyingTypeName);
            }

            boolean failed() {
                return pending == null;
            }
        }

        /**
         * A step's outcome captured during execution, before the workflow's complete secret set is
         * known. Mirrors {@link WorkflowStepResult} exactly except {@link #condition}, which
         * carries unredacted, unbounded text a later step's secret may still need to mask. {@link
         * WorkflowFailure} is never deferred: a step failure terminates execution immediately, so
         * no later secret can ever be discovered that would need to redact it.
         */
        private record PendingStepResult(
                WorkflowStepId stepId,
                WorkflowStepType stepType,
                WorkflowStepStatus status,
                Optional<PendingConditionResult> condition,
                Optional<String> outputVariableName,
                Optional<WorkflowFailure> failure,
                Optional<WorkflowActionSummary> actionSummary) {}

        /**
         * One execution-tree node captured during the same traversal that builds {@code
         * PendingStepResult}s, referencing the exact {@code PendingStepResult} this node will share
         * with the flat list once both are finalized together - see {@link #freezeNodes}.
         */
        private record PendingExecutionNode(
                PendingStepResult result,
                Optional<WorkflowBranchSelection> branchSelection,
                List<PendingExecutionNode> children) {}

        /**
         * {@link #finalizeStepResults}'s output: the flat, finalized list exactly as before, plus
         * the identity correlation from each {@code PendingStepResult} to its one finalized {@link
         * WorkflowStepResult}, used by {@link #freezeNodes} so the tree's nodes reference the exact
         * same instances the flat list does - never a second, independently finalized copy.
         */
        private record FinalizedSteps(
                List<WorkflowStepResult> steps,
                Map<PendingStepResult, WorkflowStepResult> byPending) {}

        /**
         * One {@link WorkflowStepType#PARALLEL} branch's own outcome, once its own {@link
         * #runSteps} call returns - added in 1.3.0. {@code branchIndex} identifies which declared
         * branch this is (its definition order), independent of the order branches actually
         * finished, which is how {@link #joinParallelBranches} recovers deterministic identity from
         * results that may arrive in any real completion order.
         */
        private record BranchOutcome(
                int branchIndex,
                ExecutionState state,
                List<PendingStepResult> pendingResults,
                List<PendingExecutionNode> pendingNodes,
                Optional<WorkflowFailure> failure) {}

        private final Workflow workflow;
        private final WorkflowInputs inputs;
        private final AtomicInteger executedNodeCount = new AtomicInteger();

        Session(Workflow workflow, WorkflowInputs inputs) {
            this.workflow = workflow;
            this.inputs = inputs;
        }

        WorkflowExecution run() {
            ExecutionState state = ExecutionState.fresh();
            WorkflowExecution inputFailure = validateAndSeedInputs(state);
            if (inputFailure != null) {
                return inputFailure;
            }

            List<PendingStepResult> pendingResults = new ArrayList<>();
            List<PendingExecutionNode> pendingNodes = new ArrayList<>();
            Optional<WorkflowFailure> overallFailure =
                    runSteps(workflow.steps(), "", state, pendingResults, pendingNodes);

            WorkflowStatus status =
                    overallFailure.isPresent() ? WorkflowStatus.FAILED : WorkflowStatus.COMPLETED;
            FinalizedSteps finalized = finalizeStepResults(pendingResults, state);
            WorkflowResult result =
                    new WorkflowResult(
                            workflow.id(),
                            status,
                            finalized.steps(),
                            state.outputs.build(state.activeSecrets),
                            overallFailure);
            WorkflowExecutionTree tree =
                    new WorkflowExecutionTree(
                            workflow.id(), freezeNodes(pendingNodes, finalized.byPending()));
            return new WorkflowExecution(result, tree);
        }

        /**
         * Executes {@code steps} in order, appending each one's {@link PendingStepResult} to {@code
         * accumulator} - and, for a {@link ConditionalWorkflowStep}, also the (possibly empty,
         * already-flattened) results of whichever single branch it selected, recursively. Stops at
         * the first failure encountered anywhere in {@code steps} - including one surfaced from
         * inside a conditional step's selected branch, a loop iteration's body, or a {@code
         * PARALLEL} step's own join - and marks every step remaining in {@code steps} from that
         * point on {@link WorkflowStepStatus#NOT_RUN}, exactly like the top-level fail-fast/short-
         * circuit behavior this engine has always had (see the class Javadoc); {@link #run()} calls
         * this once for the workflow's own top-level steps, and a conditional step's branch, a
         * loop's body, and a {@code PARALLEL} branch are each executed by recursing into this same
         * method (the latter on its own dedicated thread, against its own forked {@code state} -
         * see {@link #executeParallelStepInto}), so every level shares this one fail-fast mechanism
         * rather than independently-maintained copies of it.
         *
         * <p>This recursion is not separately depth-bounded here: {@link Workflow.Builder#build()}
         * is the only way to obtain a {@link Workflow}, and it already rejects a conditional/loop/
         * parallel nested deeper than {@link Workflow#MAX_CONDITIONAL_NESTING_DEPTH}/{@link
         * Workflow#MAX_CONTROL_FLOW_NESTING_DEPTH} before returning one (see those constants'
         * Javadoc), so every {@code workflow} this method can ever be called with is already
         * bounded - a second, independent runtime limit here would duplicate that single source of
         * truth rather than add real protection. {@code nodeAccumulator} mirrors {@code
         * accumulator} one-for-one - the same steps, same order, same NOT_RUN marking - so the
         * execution tree and the flat list can never diverge; see {@link #freezeNodes}.
         */
        private Optional<WorkflowFailure> runSteps(
                List<IWorkflowStep> steps,
                String idSuffix,
                ExecutionState state,
                List<PendingStepResult> accumulator,
                List<PendingExecutionNode> nodeAccumulator) {
            for (int i = 0; i < steps.size(); i++) {
                Optional<WorkflowFailure> failure =
                        executeStepInto(
                                steps.get(i), idSuffix, state, accumulator, nodeAccumulator);
                if (failure.isPresent()) {
                    for (int j = i + 1; j < steps.size(); j++) {
                        PendingStepResult notRunResult = notRun(steps.get(j), idSuffix);
                        accumulator.add(notRunResult);
                        nodeAccumulator.add(
                                new PendingExecutionNode(
                                        notRunResult, Optional.empty(), List.of()));
                    }
                    return failure;
                }
            }
            return Optional.empty();
        }

        /**
         * Executes exactly one step, appending its own {@link PendingStepResult} (and, if it is a
         * {@link ConditionalWorkflowStep}, its selected branch's results) to {@code accumulator} -
         * and its corresponding {@link PendingExecutionNode} to {@code nodeAccumulator}. Returns
         * the failure that should stop the enclosing {@link #runSteps} call, if any.
         */
        private Optional<WorkflowFailure> executeStepInto(
                IWorkflowStep step,
                String idSuffix,
                ExecutionState state,
                List<PendingStepResult> accumulator,
                List<PendingExecutionNode> nodeAccumulator) {
            // Safe: IWorkflowStep is sealed and permits only AWorkflowStep.
            AWorkflowStep concreteStep = (AWorkflowStep) step;
            Optional<PendingStepResult> budgetExceeded =
                    budgetExceededResult(qualify(step.id(), idSuffix), concreteStep.stepType());
            if (budgetExceeded.isPresent()) {
                PendingStepResult pending = budgetExceeded.get();
                accumulator.add(pending);
                nodeAccumulator.add(new PendingExecutionNode(pending, Optional.empty(), List.of()));
                return pending.failure();
            }
            if (concreteStep instanceof ConditionalWorkflowStep conditional) {
                return executeConditionalStepInto(
                        conditional, idSuffix, state, accumulator, nodeAccumulator);
            }
            if (concreteStep instanceof LoopWorkflowStep loop) {
                return executeLoopStepInto(loop, idSuffix, state, accumulator, nodeAccumulator);
            }
            if (concreteStep instanceof ParallelWorkflowStep parallel) {
                return executeParallelStepInto(
                        parallel, idSuffix, state, accumulator, nodeAccumulator);
            }
            PendingStepResult pending = executeStep(step, idSuffix, state);
            accumulator.add(pending);
            nodeAccumulator.add(new PendingExecutionNode(pending, Optional.empty(), List.of()));
            return pending.status() == WorkflowStepStatus.FAILED
                    ? pending.failure()
                    : Optional.empty();
        }

        /**
         * Returns {@code original} qualified by {@code idSuffix}, or {@code original} unchanged.
         */
        private static WorkflowStepId qualify(WorkflowStepId original, String idSuffix) {
            return idSuffix.isEmpty() ? original : new WorkflowStepId(original.value() + idSuffix);
        }

        /**
         * Consumes one unit of {@link #MAX_EXECUTED_WORKFLOW_NODES} and returns a ready-to-append
         * {@link PendingStepResult} failing with {@link
         * WorkflowFailureType#EXECUTED_NODE_BUDGET_EXCEEDED} if the budget is now exceeded, or
         * empty if execution may proceed. Backed by an {@link AtomicInteger} shared,
         * unconditionally, across every concurrently running {@link WorkflowStepType#PARALLEL}
         * branch of this same execution - added in 1.3.0 - so the bound this enforces is always the
         * true cumulative total across every branch, never merely one branch's own local count.
         */
        private Optional<PendingStepResult> budgetExceededResult(
                WorkflowStepId resultId, WorkflowStepType stepType) {
            int count = executedNodeCount.incrementAndGet();
            if (count <= MAX_EXECUTED_WORKFLOW_NODES) {
                return Optional.empty();
            }
            return Optional.of(
                    failedResult(
                            resultId,
                            stepType,
                            Optional.empty(),
                            WorkflowFailureType.EXECUTED_NODE_BUDGET_EXCEEDED,
                            "execution stopped: reached the maximum supported cumulative"
                                    + " executed-step-node budget of "
                                    + MAX_EXECUTED_WORKFLOW_NODES,
                            null,
                            null,
                            null,
                            null));
        }

        /**
         * Executes one {@link ConditionalWorkflowStep} following the fixed sequence this feature's
         * whole contract rests on: an interruption boundary, then the branch condition evaluated
         * <b>exactly once</b>, then a second interruption boundary, then exactly one of the two
         * branches (recursively, via {@link #runSteps}) - never both, never neither, and the
         * condition is never re-evaluated once a branch has started. Interruption is this engine's
         * only cancellation primitive: {@code WorkflowEngine} has no workflow-wide timeout of its
         * own (see the class Javadoc) and relies entirely on each contained action's own budget for
         * time-based limits, so a deadline that expires between the decision and the selected
         * branch's start is observed here the same way the action pipeline observes one at its own
         * post-verification boundary - as the executing thread's interrupt flag, checked with
         * {@link Thread#isInterrupted()} and left untouched either way, never cleared.
         */
        private Optional<WorkflowFailure> executeConditionalStepInto(
                ConditionalWorkflowStep step,
                String idSuffix,
                ExecutionState state,
                List<PendingStepResult> accumulator,
                List<PendingExecutionNode> nodeAccumulator) {
            WorkflowStepId resultId = qualify(step.id(), idSuffix);
            if (Thread.currentThread().isInterrupted()) {
                return addInterrupted(
                        resultId,
                        WorkflowStepType.CONDITIONAL,
                        WorkflowFailureType.CONDITIONAL_STEP_INTERRUPTED,
                        Optional.empty(),
                        Optional.empty(),
                        "the executing thread was interrupted before the branch condition could"
                                + " be evaluated",
                        state,
                        accumulator,
                        nodeAccumulator);
            }

            ConditionEvaluationResult evaluation =
                    evaluateConditionSafely(state, step.branchCondition());
            if (evaluation.failed()) {
                PendingStepResult pending =
                        failedResult(
                                resultId,
                                WorkflowStepType.CONDITIONAL,
                                Optional.empty(),
                                WorkflowFailureType.CONDITION_EVALUATION_FAILED,
                                evaluation.failureMessage(),
                                evaluation.underlyingTypeName(),
                                null,
                                null,
                                state);
                accumulator.add(pending);
                nodeAccumulator.add(new PendingExecutionNode(pending, Optional.empty(), List.of()));
                return pending.failure();
            }
            Optional<PendingConditionResult> conditionResult = Optional.of(evaluation.pending());
            WorkflowBranchSelection selection = branchSelectionFor(step, evaluation.outcome());

            if (Thread.currentThread().isInterrupted()) {
                return addInterrupted(
                        resultId,
                        WorkflowStepType.CONDITIONAL,
                        WorkflowFailureType.CONDITIONAL_STEP_INTERRUPTED,
                        conditionResult,
                        Optional.of(selection),
                        "the executing thread was interrupted after the branch decision was"
                                + " captured but before the selected branch could start",
                        state,
                        accumulator,
                        nodeAccumulator);
            }

            List<IWorkflowStep> selectedBranch =
                    evaluation.outcome() ? step.thenSteps() : step.elseSteps().orElse(List.of());
            PendingStepResult conditionalResult =
                    new PendingStepResult(
                            resultId,
                            WorkflowStepType.CONDITIONAL,
                            WorkflowStepStatus.SUCCEEDED,
                            conditionResult,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty());
            accumulator.add(conditionalResult);
            List<PendingExecutionNode> branchChildren = new ArrayList<>();
            Optional<WorkflowFailure> failure =
                    runSteps(selectedBranch, idSuffix, state, accumulator, branchChildren);
            nodeAccumulator.add(
                    new PendingExecutionNode(
                            conditionalResult,
                            Optional.of(selection),
                            List.copyOf(branchChildren)));
            return failure;
        }

        /**
         * Derives the backend-neutral {@link WorkflowBranchSelection} a captured decision implies:
         * {@code true} always selects {@code THEN}; {@code false} selects {@code ELSE} when {@code
         * elseSteps} was declared, or {@code NONE} for an {@code ifThen} step's no-op false
         * decision - never conflated with {@code ELSE}, which does not exist for that step (see
         * {@code docs/workflow.md#branching}).
         */
        private static WorkflowBranchSelection branchSelectionFor(
                ConditionalWorkflowStep step, boolean outcome) {
            if (outcome) {
                return WorkflowBranchSelection.THEN;
            }
            return step.elseSteps().isPresent()
                    ? WorkflowBranchSelection.ELSE
                    : WorkflowBranchSelection.NONE;
        }

        /**
         * Shared interruption-boundary handler for a {@link ConditionalWorkflowStep} ({@code
         * failureType} {@link WorkflowFailureType#CONDITIONAL_STEP_INTERRUPTED}, {@code stepType}
         * {@link WorkflowStepType#CONDITIONAL}), a {@link LoopWorkflowStep} iteration ({@code
         * failureType} {@link WorkflowFailureType#LOOP_STEP_INTERRUPTED}, {@code stepType} {@link
         * WorkflowStepType#LOOP_ITERATION}), and a {@link ParallelWorkflowStep}'s own pre-launch
         * boundary ({@code failureType} {@link WorkflowFailureType#PARALLEL_STEP_INTERRUPTED},
         * {@code stepType} {@link WorkflowStepType#PARALLEL}) - all observe the executing thread's
         * interrupt flag at their own evaluate/select boundaries (see {@link
         * #executeConditionalStepInto}, {@link #executeLoopStepInto}, and {@link
         * #executeParallelStepInto}).
         */
        private Optional<WorkflowFailure> addInterrupted(
                WorkflowStepId resultId,
                WorkflowStepType stepType,
                WorkflowFailureType failureType,
                Optional<PendingConditionResult> conditionResult,
                Optional<WorkflowBranchSelection> branchSelection,
                String message,
                ExecutionState state,
                List<PendingStepResult> accumulator,
                List<PendingExecutionNode> nodeAccumulator) {
            PendingStepResult pending =
                    failedResult(
                            resultId,
                            stepType,
                            conditionResult,
                            failureType,
                            message,
                            null,
                            null,
                            null,
                            state);
            accumulator.add(pending);
            nodeAccumulator.add(new PendingExecutionNode(pending, branchSelection, List.of()));
            return pending.failure();
        }

        /**
         * Executes one {@link LoopWorkflowStep}: repeatedly, up to {@code maxIterations} times,
         * evaluates {@link LoopWorkflowStep#continueCondition()} exactly once per iteration attempt
         * and - on {@code true} - runs exactly that one iteration's {@link LoopWorkflowStep#body()}
         * in full before ever re-evaluating the condition; a {@code false} result stops the loop as
         * a successful no-op for the iteration under consideration. Mirrors {@link
         * #executeConditionalStepInto}'s interruption boundaries (before the condition is
         * evaluated, and after the decision is captured but before the body starts) at every
         * iteration attempt, and shares its "never retry, never re-evaluate" contract: a body
         * failure stops the whole loop (and workflow) immediately, exactly like any other failure.
         *
         * <p>Reaching {@code maxIterations} while the condition still evaluates {@code true} fails
         * closed with {@link WorkflowFailureType#LOOP_ITERATION_LIMIT_EXCEEDED} - the {@code
         * (maxIterations + 1)}-th continuation check is where this is discovered, never a {@code
         * (maxIterations + 1)}-th body run.
         *
         * <p>Every flat {@link PendingStepResult} this produces - the loop's own wrapper entry and
         * each iteration's {@link WorkflowStepType#LOOP_ITERATION} decision (plus, for a started
         * iteration, its body's own entries) - carries an {@code idSuffix}-qualified {@link
         * WorkflowStepId} via {@link #qualify}: the wrapper reuses {@code idSuffix} unchanged, and
         * iteration {@code n}'s decision (and its body) additionally appends {@code "#n"} - so a
         * declared step ID reused across iterations, or across a nested loop's own iterations, is
         * always unique in the flat, globally-unique {@code WorkflowResult#steps()} list. The
         * wrapper's own result is always {@link WorkflowStepStatus#SUCCEEDED}: like a {@code
         * CONDITIONAL} step's own decision entry, it never itself reports a nested failure - the
         * iteration or body entry that actually failed does, exactly once, and that single failure
         * propagates up unchanged.
         */
        private Optional<WorkflowFailure> executeLoopStepInto(
                LoopWorkflowStep step,
                String idSuffix,
                ExecutionState state,
                List<PendingStepResult> accumulator,
                List<PendingExecutionNode> nodeAccumulator) {
            WorkflowStepId wrapperId = qualify(step.id(), idSuffix);
            PendingStepResult wrapperResult =
                    new PendingStepResult(
                            wrapperId,
                            WorkflowStepType.LOOP,
                            WorkflowStepStatus.SUCCEEDED,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty());
            accumulator.add(wrapperResult);

            List<PendingExecutionNode> iterationNodes = new ArrayList<>();
            Optional<WorkflowFailure> failure = Optional.empty();
            for (int iteration = 0; ; iteration++) {
                String iterationSuffix = idSuffix + "#" + iteration;
                WorkflowStepId decisionId = qualify(step.id(), iterationSuffix);

                if (Thread.currentThread().isInterrupted()) {
                    failure =
                            addInterrupted(
                                    decisionId,
                                    WorkflowStepType.LOOP_ITERATION,
                                    WorkflowFailureType.LOOP_STEP_INTERRUPTED,
                                    Optional.empty(),
                                    Optional.empty(),
                                    "the executing thread was interrupted before iteration "
                                            + iteration
                                            + "'s continuation condition could be evaluated",
                                    state,
                                    accumulator,
                                    iterationNodes);
                    break;
                }

                Optional<PendingStepResult> budgetExceeded =
                        budgetExceededResult(decisionId, WorkflowStepType.LOOP_ITERATION);
                if (budgetExceeded.isPresent()) {
                    PendingStepResult pending = budgetExceeded.get();
                    accumulator.add(pending);
                    iterationNodes.add(
                            new PendingExecutionNode(pending, Optional.empty(), List.of()));
                    failure = pending.failure();
                    break;
                }

                ConditionEvaluationResult evaluation =
                        evaluateConditionSafely(state, step.continueCondition());
                if (evaluation.failed()) {
                    PendingStepResult pending =
                            failedResult(
                                    decisionId,
                                    WorkflowStepType.LOOP_ITERATION,
                                    Optional.empty(),
                                    WorkflowFailureType.CONDITION_EVALUATION_FAILED,
                                    evaluation.failureMessage(),
                                    evaluation.underlyingTypeName(),
                                    null,
                                    null,
                                    state);
                    accumulator.add(pending);
                    iterationNodes.add(
                            new PendingExecutionNode(pending, Optional.empty(), List.of()));
                    failure = pending.failure();
                    break;
                }

                Optional<PendingConditionResult> conditionResult =
                        Optional.of(evaluation.pending());
                if (!evaluation.outcome()) {
                    PendingStepResult pending =
                            new PendingStepResult(
                                    decisionId,
                                    WorkflowStepType.LOOP_ITERATION,
                                    WorkflowStepStatus.SUCCEEDED,
                                    conditionResult,
                                    Optional.empty(),
                                    Optional.empty(),
                                    Optional.empty());
                    accumulator.add(pending);
                    iterationNodes.add(
                            new PendingExecutionNode(
                                    pending, Optional.of(WorkflowBranchSelection.NONE), List.of()));
                    break;
                }

                if (iteration >= step.maxIterations()) {
                    PendingStepResult pending =
                            failedResult(
                                    decisionId,
                                    WorkflowStepType.LOOP_ITERATION,
                                    conditionResult,
                                    WorkflowFailureType.LOOP_ITERATION_LIMIT_EXCEEDED,
                                    "loop '"
                                            + step.id()
                                            + "' reached its maxIterations bound of "
                                            + step.maxIterations()
                                            + " while its continuation condition still evaluated"
                                            + " true",
                                    null,
                                    null,
                                    null,
                                    state);
                    accumulator.add(pending);
                    // No branch selection: a true outcome refused at the bound is a distinct third
                    // state from THEN (body authorized) or NONE (a false outcome's normal stop) -
                    // see RecordingV2PlanTreeValidator#validateLoopIterationNode.
                    iterationNodes.add(
                            new PendingExecutionNode(pending, Optional.empty(), List.of()));
                    failure = pending.failure();
                    break;
                }

                if (Thread.currentThread().isInterrupted()) {
                    failure =
                            addInterrupted(
                                    decisionId,
                                    WorkflowStepType.LOOP_ITERATION,
                                    WorkflowFailureType.LOOP_STEP_INTERRUPTED,
                                    conditionResult,
                                    Optional.of(WorkflowBranchSelection.THEN),
                                    "the executing thread was interrupted after iteration "
                                            + iteration
                                            + "'s continuation decision was captured but before its"
                                            + " body could start",
                                    state,
                                    accumulator,
                                    iterationNodes);
                    break;
                }

                PendingStepResult decisionPending =
                        new PendingStepResult(
                                decisionId,
                                WorkflowStepType.LOOP_ITERATION,
                                WorkflowStepStatus.SUCCEEDED,
                                conditionResult,
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty());
                accumulator.add(decisionPending);
                List<PendingExecutionNode> bodyChildren = new ArrayList<>();
                Optional<WorkflowFailure> bodyFailure =
                        runSteps(step.body(), iterationSuffix, state, accumulator, bodyChildren);
                iterationNodes.add(
                        new PendingExecutionNode(
                                decisionPending,
                                Optional.of(WorkflowBranchSelection.THEN),
                                List.copyOf(bodyChildren)));
                if (bodyFailure.isPresent()) {
                    failure = bodyFailure;
                    break;
                }
            }

            nodeAccumulator.add(
                    new PendingExecutionNode(
                            wrapperResult, Optional.empty(), List.copyOf(iterationNodes)));
            return failure;
        }

        /**
         * Executes one {@link ParallelWorkflowStep} - added in 1.3.0: an interruption boundary,
         * then (since a {@code PARALLEL} step's condition slot is an ordinary optional guard, not a
         * mandatory selector) that guard is evaluated at most once, exactly like {@link
         * #executeStep}'s own guard handling; a {@code false} outcome is a normal {@link
         * WorkflowStepStatus#SKIPPED} with zero branches launched. Otherwise every declared branch
         * is launched concurrently (see {@link #runBranchesConcurrently}) and joined
         * deterministically (see {@link #joinParallelBranches}) - see {@code
         * docs/workflow.md#parallel} for the full cancellation, ordering, and secret-propagation
         * contract.
         */
        private Optional<WorkflowFailure> executeParallelStepInto(
                ParallelWorkflowStep step,
                String idSuffix,
                ExecutionState state,
                List<PendingStepResult> accumulator,
                List<PendingExecutionNode> nodeAccumulator) {
            WorkflowStepId resultId = qualify(step.id(), idSuffix);
            if (Thread.currentThread().isInterrupted()) {
                return addInterrupted(
                        resultId,
                        WorkflowStepType.PARALLEL,
                        WorkflowFailureType.PARALLEL_STEP_INTERRUPTED,
                        Optional.empty(),
                        Optional.empty(),
                        "the executing thread was interrupted before any PARALLEL branch could be"
                                + " launched",
                        state,
                        accumulator,
                        nodeAccumulator);
            }

            Optional<PendingConditionResult> conditionResult = Optional.empty();
            if (step.condition().isPresent()) {
                ConditionEvaluationResult evaluation =
                        evaluateConditionSafely(state, step.condition().get());
                if (evaluation.failed()) {
                    PendingStepResult pending =
                            failedResult(
                                    resultId,
                                    WorkflowStepType.PARALLEL,
                                    Optional.empty(),
                                    WorkflowFailureType.CONDITION_EVALUATION_FAILED,
                                    evaluation.failureMessage(),
                                    evaluation.underlyingTypeName(),
                                    null,
                                    null,
                                    state);
                    accumulator.add(pending);
                    nodeAccumulator.add(
                            new PendingExecutionNode(pending, Optional.empty(), List.of()));
                    return pending.failure();
                }
                conditionResult = Optional.of(evaluation.pending());
                if (!evaluation.outcome()) {
                    PendingStepResult pending =
                            new PendingStepResult(
                                    resultId,
                                    WorkflowStepType.PARALLEL,
                                    WorkflowStepStatus.SKIPPED,
                                    conditionResult,
                                    Optional.empty(),
                                    Optional.empty(),
                                    Optional.empty());
                    accumulator.add(pending);
                    nodeAccumulator.add(
                            new PendingExecutionNode(pending, Optional.empty(), List.of()));
                    return Optional.empty();
                }
            }

            List<List<IWorkflowStep>> branches = step.branches();
            List<BranchOutcome> outcomes =
                    runBranchesConcurrently(branches, step.id(), idSuffix, state);

            PendingStepResult wrapperResult =
                    new PendingStepResult(
                            resultId,
                            WorkflowStepType.PARALLEL,
                            WorkflowStepStatus.SUCCEEDED,
                            conditionResult,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty());
            accumulator.add(wrapperResult);

            List<PendingExecutionNode> branchNodes = new ArrayList<>(branches.size());
            Optional<WorkflowFailure> failure =
                    joinParallelBranches(
                            step.id(), idSuffix, state, outcomes, accumulator, branchNodes);
            nodeAccumulator.add(
                    new PendingExecutionNode(
                            wrapperResult, Optional.empty(), List.copyOf(branchNodes)));
            return failure;
        }

        /**
         * Deterministically folds every branch's {@link BranchOutcome} - already fully joined by
         * {@link #runBranchesConcurrently}, in an arbitrary real completion order - into {@code
         * accumulator}/{@code branchNodeAccumulator}, strictly in branch <b>definition</b> order,
         * added in 1.3.0.
         *
         * <p>The reported failure, if any, is whichever failed branch has the lowest definition
         * index - never whichever branch happened to fail first in wall-clock time. Every branch up
         * to and including that one is <b>kept</b>: its own genuine {@link ExecutionState} fork is
         * merged back into {@code state} (in order, so a later kept branch's condition/action can
         * never actually have observed an earlier one's contribution mid-flight - merging happens
         * only after every branch has already finished running), and its own real, already-computed
         * {@link PendingStepResult}s/{@link PendingExecutionNode}s become part of this execution's
         * permanent record. Every branch declared <em>after</em> the reported failure is instead
         * represented as a single {@link WorkflowStepType#PARALLEL_BRANCH} entry with status {@link
         * WorkflowStepStatus#NOT_RUN} and no children - whatever it may have actually computed in
         * the background is discarded unseen and never merged into {@code state}, never appears in
         * the flat result or the tree, and never contributes a secret or an output: a {@code
         * PARALLEL} branch is never permitted to perform an observable side effect in the first
         * place (see {@code docs/workflow.md#parallel}), so discarding a later branch's already-
         * computed read has no side effect to hide, and this is what lets {@link WorkflowResult}'s
         * own pre-existing "exactly one {@code FAILED} step, strictly ordered before/after"
         * invariant hold for a {@code PARALLEL} step exactly as it already does for every other
         * step type.
         */
        private Optional<WorkflowFailure> joinParallelBranches(
                WorkflowStepId parallelStepId,
                String idSuffix,
                ExecutionState state,
                List<BranchOutcome> outcomes,
                List<PendingStepResult> accumulator,
                List<PendingExecutionNode> branchNodeAccumulator) {
            int failedIndex = -1;
            for (BranchOutcome outcome : outcomes) {
                if (outcome.failure().isPresent()) {
                    failedIndex = outcome.branchIndex();
                    break;
                }
            }
            Optional<WorkflowFailure> reportedFailure = Optional.empty();
            for (BranchOutcome outcome : outcomes) {
                int i = outcome.branchIndex();
                String branchSuffix = idSuffix + "@" + i;
                WorkflowStepId branchWrapperId = qualify(parallelStepId, branchSuffix);
                boolean kept = failedIndex == -1 || i <= failedIndex;
                if (kept) {
                    state.mergeFrom(outcome.state());
                    List<PendingStepResult> branchResults = outcome.pendingResults();
                    List<PendingExecutionNode> branchNodes = outcome.pendingNodes();
                    if (i == failedIndex) {
                        // This branch's own failure message was redacted, eagerly, against only
                        // its own isolated fork's secrets (see ExecutionState#fork) - it could
                        // never have observed a secret an earlier-declared sibling branch (already
                        // merged into `state` on a prior loop iteration, above) discovered while
                        // running concurrently with it. Re-redacting against the now-fully-merged
                        // `state.activeSecrets` is safe and sufficient without retaining any raw
                        // text: redaction only ever removes matching substrings, so a second pass
                        // with a superset of known secrets can only mask more, never less, and
                        // never needs to "un-mask" anything the first pass already replaced.
                        PendingStepResult oldFailed = findFailed(branchResults);
                        PendingStepResult newFailed =
                                reRedactFailureMessage(
                                        oldFailed, SecretRedactor.of(state.activeSecrets));
                        branchResults = substituteResult(branchResults, oldFailed, newFailed);
                        branchNodes = substituteNode(branchNodes, oldFailed, newFailed);
                        reportedFailure = newFailed.failure();
                    }
                    PendingStepResult branchWrapper =
                            new PendingStepResult(
                                    branchWrapperId,
                                    WorkflowStepType.PARALLEL_BRANCH,
                                    WorkflowStepStatus.SUCCEEDED,
                                    Optional.empty(),
                                    Optional.empty(),
                                    Optional.empty(),
                                    Optional.empty());
                    accumulator.add(branchWrapper);
                    accumulator.addAll(branchResults);
                    branchNodeAccumulator.add(
                            new PendingExecutionNode(
                                    branchWrapper, Optional.empty(), List.copyOf(branchNodes)));
                } else {
                    PendingStepResult neverLaunched =
                            new PendingStepResult(
                                    branchWrapperId,
                                    WorkflowStepType.PARALLEL_BRANCH,
                                    WorkflowStepStatus.NOT_RUN,
                                    Optional.empty(),
                                    Optional.empty(),
                                    Optional.empty(),
                                    Optional.empty());
                    accumulator.add(neverLaunched);
                    branchNodeAccumulator.add(
                            new PendingExecutionNode(neverLaunched, Optional.empty(), List.of()));
                }
            }
            return reportedFailure;
        }

        /**
         * Returns the one {@link PendingStepResult} with {@link WorkflowStepStatus#FAILED} in
         * {@code branchResults} - guaranteed to exist and be unique whenever this is called, since
         * {@link #runSteps} never produces more than one {@code FAILED} entry for a single branch's
         * own execution (the same single-failure invariant {@link WorkflowResult} itself enforces
         * at the top level).
         */
        private static PendingStepResult findFailed(List<PendingStepResult> branchResults) {
            for (PendingStepResult result : branchResults) {
                if (result.status() == WorkflowStepStatus.FAILED) {
                    return result;
                }
            }
            throw new IllegalStateException(
                    "a PARALLEL branch reported a failure but none of its own pending results is"
                            + " FAILED");
        }

        /**
         * Returns a copy of {@code failed} with its {@link WorkflowFailure#safeMessage()} re-run
         * through {@code redactor} and re-bounded - see {@link #joinParallelBranches}'s own Javadoc
         * for why this is both necessary and safe for a {@link WorkflowStepType#PARALLEL} branch's
         * own failure specifically.
         */
        private static PendingStepResult reRedactFailureMessage(
                PendingStepResult failed, SecretRedactor redactor) {
            WorkflowFailure original = failed.failure().orElseThrow();
            WorkflowFailure reRedacted =
                    new WorkflowFailure(
                            original.type(),
                            SafeRendering.bounded(redactor.redact(original.safeMessage())),
                            original.stepId(),
                            original.underlyingTypeName(),
                            original.actionFailureType());
            return new PendingStepResult(
                    failed.stepId(),
                    failed.stepType(),
                    failed.status(),
                    failed.condition(),
                    failed.outputVariableName(),
                    Optional.of(reRedacted),
                    failed.actionSummary());
        }

        /** Replaces {@code oldResult} with {@code newResult} (by reference) in a flat list. */
        private static List<PendingStepResult> substituteResult(
                List<PendingStepResult> results,
                PendingStepResult oldResult,
                PendingStepResult newResult) {
            List<PendingStepResult> substituted = new ArrayList<>(results.size());
            for (PendingStepResult result : results) {
                substituted.add(result == oldResult ? newResult : result);
            }
            return substituted;
        }

        /**
         * Replaces {@code oldResult} with {@code newResult} (by reference) wherever it appears as a
         * node's own {@link PendingExecutionNode#result()}, at any depth within {@code nodes} - the
         * tree-shaped counterpart to {@link #substituteResult}, keeping a re-redacted failure's
         * flat and tree representations pointing at the exact same instance, as every other node
         * already does (see {@link #freezeNodes}).
         */
        private static List<PendingExecutionNode> substituteNode(
                List<PendingExecutionNode> nodes,
                PendingStepResult oldResult,
                PendingStepResult newResult) {
            List<PendingExecutionNode> substituted = new ArrayList<>(nodes.size());
            for (PendingExecutionNode node : nodes) {
                if (node.result() == oldResult) {
                    substituted.add(
                            new PendingExecutionNode(
                                    newResult, node.branchSelection(), node.children()));
                } else if (!node.children().isEmpty()) {
                    substituted.add(
                            new PendingExecutionNode(
                                    node.result(),
                                    node.branchSelection(),
                                    substituteNode(node.children(), oldResult, newResult)));
                } else {
                    substituted.add(node);
                }
            }
            return substituted;
        }

        /**
         * Launches every one of {@code branches} concurrently, each on its own dedicated worker
         * thread drawn from a small executor sized to exactly {@code branches.size()} and created
         * fresh for this one {@link WorkflowStepType#PARALLEL} step - never shared, never
         * unbounded, never reused across steps or executions - added in 1.3.0. Each branch runs
         * against its own isolated {@link ExecutionState#fork()} of {@code state}, seeded once,
         * immediately, before any branch starts: a branch never observes another branch's in-flight
         * variables, secrets, or outputs, regardless of real completion order.
         *
         * <p>As soon as a branch at index {@code f} reports a failure, every branch at an index
         * strictly greater than {@code f} - never {@code f} itself, and never a lower index - has
         * its {@link Future} cancelled ({@code mayInterruptIfRunning=true}): a best-effort,
         * cooperative request a branch already past its own last interruption checkpoint (or
         * containing none at all) may simply outlive, in which case whatever it computes is safely
         * discarded later (see {@link #joinParallelBranches}), never forcibly killed mid-step. A
         * branch at an index less than or equal to the lowest failed index observed so far is
         * <b>never</b> cancelled by this method, at any point: since the final reported failure is
         * always the lowest-index branch that failed among <em>all</em> of them, every branch at or
         * before whichever index has already been confirmed failed is certain to end up kept (see
         * {@link #joinParallelBranches}) no matter what any other branch does later - cancelling it
         * preemptively would fabricate a never-launched outcome for a branch this step's own
         * contract already promises to keep genuine. (An earlier revision of this method cancelled
         * every other branch on any failure, which could race a lower-index branch's own Future
         * into being cancelled before it ever started, discarding real content the recorded result
         * is supposed to retain - see {@code WorkflowParallelRecordingV2Test} for the regression
         * this fixes.) This method always waits for every branch's task to reach a terminal state -
         * normal completion or cancellation - and always shuts the executor down, with no orphaned
         * task and no leaked thread, before returning; an interruption of the calling thread while
         * waiting is tolerated (every branch strictly after the lowest failed index observed so far
         * is cancelled and the wait continues) and re-applied to the calling thread's own interrupt
         * flag before this method returns, never silently swallowed.
         *
         * <p>Returned outcomes are not necessarily in branch-definition order - see {@link
         * BranchOutcome#branchIndex()} and {@link #joinParallelBranches}, which restores that order
         * deterministically.
         */
        private List<BranchOutcome> runBranchesConcurrently(
                List<List<IWorkflowStep>> branches,
                WorkflowStepId parallelStepId,
                String idSuffix,
                ExecutionState state) {
            int branchCount = branches.size();
            List<ExecutionState> forks = new ArrayList<>(branchCount);
            for (int i = 0; i < branchCount; i++) {
                forks.add(state.fork());
            }

            ExecutorService executor =
                    Executors.newFixedThreadPool(
                            branchCount, new ParallelBranchThreadFactory(parallelStepId));
            boolean interruptedWhileWaiting = false;
            try {
                ExecutorCompletionService<BranchOutcome> completionService =
                        new ExecutorCompletionService<>(executor);
                List<Future<BranchOutcome>> futures = new ArrayList<>(branchCount);
                for (int i = 0; i < branchCount; i++) {
                    int branchIndex = i;
                    List<IWorkflowStep> branchSteps = branches.get(i);
                    ExecutionState branchState = forks.get(i);
                    futures.add(
                            completionService.submit(
                                    () ->
                                            runBranch(
                                                    branchIndex,
                                                    branchSteps,
                                                    idSuffix,
                                                    branchState)));
                }

                @SuppressWarnings("unchecked")
                BranchOutcome[] outcomes = new BranchOutcome[branchCount];
                int lowestFailedIndexSoFar = Integer.MAX_VALUE;
                for (int received = 0; received < branchCount; received++) {
                    Future<BranchOutcome> completed = null;
                    while (completed == null) {
                        try {
                            completed = completionService.take();
                        } catch (InterruptedException e) {
                            // The calling thread's own interrupt flag has no effect on any branch's
                            // own, independent worker thread - there is nothing of ours to cancel
                            // here; simply keep waiting for every branch's own genuine outcome, and
                            // restore the flag once every branch is actually done (see below).
                            interruptedWhileWaiting = true;
                        }
                    }
                    try {
                        BranchOutcome outcome = completed.get();
                        outcomes[outcome.branchIndex()] = outcome;
                        if (outcome.failure().isPresent()
                                && outcome.branchIndex() < lowestFailedIndexSoFar) {
                            lowestFailedIndexSoFar = outcome.branchIndex();
                            cancelStrictlyAfter(futures, lowestFailedIndexSoFar);
                        }
                    } catch (CancellationException e) {
                        // Left null; filled in below as a never-launched/discarded outcome.
                    } catch (InterruptedException e) {
                        // completed.get() on an already-done future never actually blocks, but
                        // remains declared - treat identically to the take() interruption above.
                        interruptedWhileWaiting = true;
                        received--;
                    } catch (ExecutionException e) {
                        throw new IllegalStateException(
                                "a PARALLEL branch task failed unexpectedly - runBranch must"
                                        + " itself convert every RuntimeException into a safe"
                                        + " PendingStepResult",
                                e.getCause());
                    }
                }
                for (int i = 0; i < branchCount; i++) {
                    if (outcomes[i] == null) {
                        outcomes[i] =
                                new BranchOutcome(
                                        i, forks.get(i), List.of(), List.of(), Optional.empty());
                    }
                }
                return List.of(outcomes);
            } finally {
                executor.shutdown();
                boolean terminated = false;
                while (!terminated) {
                    try {
                        terminated = executor.awaitTermination(1, TimeUnit.DAYS);
                    } catch (InterruptedException e) {
                        interruptedWhileWaiting = true;
                    }
                }
                if (interruptedWhileWaiting) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        /**
         * Cancels every future at an index strictly greater than {@code lowestFailedIndexSoFar} -
         * {@code futures} is indexed identically to branch definition order, since {@link
         * #runBranchesConcurrently} submits them in that exact order. Never cancels index {@code
         * lowestFailedIndexSoFar} itself or anything before it: see {@link
         * #runBranchesConcurrently}'s own Javadoc for why a branch at or before the lowest failed
         * index observed so far must never be cancelled by this engine's own logic.
         */
        private static void cancelStrictlyAfter(
                List<Future<BranchOutcome>> futures, int lowestFailedIndexSoFar) {
            for (int i = lowestFailedIndexSoFar + 1; i < futures.size(); i++) {
                futures.get(i).cancel(true);
            }
        }

        /** Names a {@link WorkflowStepType#PARALLEL} step's own dedicated branch worker threads. */
        private static final class ParallelBranchThreadFactory
                implements java.util.concurrent.ThreadFactory {
            private final WorkflowStepId parallelStepId;
            private final AtomicInteger nextBranchIndex = new AtomicInteger();

            ParallelBranchThreadFactory(WorkflowStepId parallelStepId) {
                this.parallelStepId = parallelStepId;
            }

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread =
                        new Thread(
                                runnable,
                                "webagent4j-parallel-"
                                        + parallelStepId.value()
                                        + "-"
                                        + nextBranchIndex.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        }

        /**
         * Runs one {@link WorkflowStepType#PARALLEL} branch's own declared steps, in full, on
         * whichever worker thread the caller submitted this to - added in 1.3.0. Simply delegates
         * to {@link #runSteps} against this branch's own forked {@code branchState} and its own
         * positionally-qualified {@code idSuffix + "@" + branchIndex}, exactly like a conditional
         * branch or a loop body delegates to the same method - the only difference is which thread
         * calls it and which {@link ExecutionState} it reads and mutates.
         */
        private BranchOutcome runBranch(
                int branchIndex,
                List<IWorkflowStep> branchSteps,
                String idSuffix,
                ExecutionState branchState) {
            List<PendingStepResult> localResults = new ArrayList<>();
            List<PendingExecutionNode> localNodes = new ArrayList<>();
            String branchSuffix = idSuffix + "@" + branchIndex;
            Optional<WorkflowFailure> failure =
                    runSteps(branchSteps, branchSuffix, branchState, localResults, localNodes);
            return new BranchOutcome(branchIndex, branchState, localResults, localNodes, failure);
        }

        /**
         * Converts every {@link PendingStepResult} into a final, safe {@link WorkflowStepResult},
         * redacting each retained condition description against the workflow's complete secret set
         * at termination - not the set known when that condition was evaluated - and only then
         * bounding it. Invokes no caller-supplied code: {@code condition.describe()} was already
         * called, at most once, back when the step executed.
         */
        private FinalizedSteps finalizeStepResults(
                List<PendingStepResult> pending, ExecutionState state) {
            SecretRedactor finalRedactor = SecretRedactor.of(state.activeSecrets);
            List<WorkflowStepResult> results = new ArrayList<>(pending.size());
            Map<PendingStepResult, WorkflowStepResult> byPending =
                    new IdentityHashMap<>(pending.size());
            for (PendingStepResult one : pending) {
                WorkflowStepResult finalized = finalizeStepResult(one, finalRedactor);
                results.add(finalized);
                byPending.put(one, finalized);
            }
            return new FinalizedSteps(List.copyOf(results), byPending);
        }

        /**
         * Converts {@code pendingNodes} into the immutable, public {@link WorkflowExecutionNode}
         * tree, replacing each {@link PendingStepResult} with the exact {@link WorkflowStepResult}
         * instance {@link #finalizeStepResults} already produced for it - see {@code byPending} -
         * rather than finalizing it a second time. Bounded by the same recursive structure {@link
         * #runSteps} built it with, which is itself bounded by {@link
         * Workflow#MAX_CONDITIONAL_NESTING_DEPTH} - no independent depth check is needed here for
         * the same reason none is needed in {@link #runSteps} (see that method's Javadoc).
         */
        private static List<WorkflowExecutionNode> freezeNodes(
                List<PendingExecutionNode> pendingNodes,
                Map<PendingStepResult, WorkflowStepResult> byPending) {
            List<WorkflowExecutionNode> frozen = new ArrayList<>(pendingNodes.size());
            for (PendingExecutionNode node : pendingNodes) {
                frozen.add(freezeNode(node, byPending));
            }
            return List.copyOf(frozen);
        }

        private static WorkflowExecutionNode freezeNode(
                PendingExecutionNode node, Map<PendingStepResult, WorkflowStepResult> byPending) {
            WorkflowStepResult result = byPending.get(node.result());
            List<WorkflowExecutionNode> children = freezeNodes(node.children(), byPending);
            return new WorkflowExecutionNode(result, node.branchSelection(), children);
        }

        private static WorkflowStepResult finalizeStepResult(
                PendingStepResult pending, SecretRedactor redactor) {
            Optional<WorkflowConditionResult> condition =
                    pending.condition()
                            .map(
                                    raw ->
                                            new WorkflowConditionResult(
                                                    raw.outcome(),
                                                    raw.finalizeDescription().apply(redactor)));
            return new WorkflowStepResult(
                    pending.stepId(),
                    pending.stepType(),
                    pending.status(),
                    condition,
                    pending.outputVariableName(),
                    pending.failure(),
                    pending.actionSummary());
        }

        /**
         * Validates every declared required/optional input and rejects any supplied input that is
         * not declared at all, seeding validated values as a side effect. Returns a terminal,
         * all-steps-{@code NOT_RUN} failure result if validation fails, or {@code null} if
         * execution may proceed to step 0.
         */
        private WorkflowExecution validateAndSeedInputs(ExecutionState state) {
            for (WorkflowVariable<?> required : workflow.requiredInputs()) {
                WorkflowInputs.Entry entry = inputs.entries().get(required.name());
                if (entry == null) {
                    return failBeforeExecution(
                            WorkflowFailureType.MISSING_REQUIRED_INPUT,
                            "required input '" + required.name() + "' was not supplied");
                }
                if (!entry.variable().equals(required)) {
                    return failBeforeExecution(
                            WorkflowFailureType.INPUT_TYPE_MISMATCH,
                            "input '"
                                    + required.name()
                                    + "' was supplied with a variable declaration that does not"
                                    + " match the workflow's required input (different type or"
                                    + " secret status)");
                }
                seedVariable(state, entry.variable(), entry.value());
            }
            for (WorkflowVariable<?> optional : workflow.optionalInputs()) {
                WorkflowInputs.Entry entry = inputs.entries().get(optional.name());
                if (entry == null) {
                    continue;
                }
                if (!entry.variable().equals(optional)) {
                    return failBeforeExecution(
                            WorkflowFailureType.INPUT_TYPE_MISMATCH,
                            "input '"
                                    + optional.name()
                                    + "' was supplied with a variable declaration that does not"
                                    + " match the workflow's optional input (different type or"
                                    + " secret status)");
                }
                seedVariable(state, entry.variable(), entry.value());
            }

            Set<String> declaredNames = new HashSet<>();
            workflow.requiredInputs().forEach(variable -> declaredNames.add(variable.name()));
            workflow.optionalInputs().forEach(variable -> declaredNames.add(variable.name()));
            for (String suppliedName : inputs.entries().keySet()) {
                if (!declaredNames.contains(suppliedName)) {
                    return failBeforeExecution(
                            WorkflowFailureType.UNDECLARED_INPUT,
                            "input '"
                                    + suppliedName
                                    + "' was supplied but is not a declared required or optional"
                                    + " input of this workflow");
                }
            }
            return null;
        }

        private WorkflowExecution failBeforeExecution(WorkflowFailureType type, String message) {
            WorkflowFailure failure =
                    new WorkflowFailure(
                            type,
                            SafeRendering.bounded(
                                    SecretRedactor.of(List.of())
                                            .redact(message == null ? "<no message>" : message)),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty());
            List<PendingStepResult> notRunSteps = new ArrayList<>();
            List<PendingExecutionNode> notRunNodes = new ArrayList<>();
            for (IWorkflowStep step : workflow.steps()) {
                PendingStepResult pending = notRun(step, "");
                notRunSteps.add(pending);
                notRunNodes.add(new PendingExecutionNode(pending, Optional.empty(), List.of()));
            }
            FinalizedSteps finalized = finalizeStepResults(notRunSteps, ExecutionState.fresh());
            WorkflowResult result =
                    new WorkflowResult(
                            workflow.id(),
                            WorkflowStatus.FAILED,
                            finalized.steps(),
                            WorkflowOutputs.empty(),
                            Optional.of(failure));
            WorkflowExecutionTree tree =
                    new WorkflowExecutionTree(
                            workflow.id(), freezeNodes(notRunNodes, finalized.byPending()));
            return new WorkflowExecution(result, tree);
        }

        private static PendingStepResult notRun(IWorkflowStep step, String idSuffix) {
            // Safe: IWorkflowStep is sealed and permits only AWorkflowStep.
            return new PendingStepResult(
                    qualify(step.id(), idSuffix),
                    ((AWorkflowStep) step).stepType(),
                    WorkflowStepStatus.NOT_RUN,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
        }

        private void seedVariable(
                ExecutionState state, WorkflowVariable<?> variable, Object value) {
            state.variables.put(variable.name(), new VariableEntry(variable, value));
            if (variable.secret() && value instanceof String secretValue) {
                state.activeSecrets.add(secretValue);
            }
        }

        private <T> void publishOutput(
                ExecutionState state, WorkflowVariable<T> variable, Object value) {
            T typed = variable.type().cast(value);
            seedVariable(state, variable, typed);
            state.outputs.put(variable, typed);
        }

        /** Redacts every currently-known secret, then bounds the result - never the other order. */
        private static String redact(ExecutionState state, String message) {
            String safe = message == null ? "<no message>" : message;
            return SafeRendering.bounded(SecretRedactor.of(state.activeSecrets).redact(safe));
        }

        /**
         * Returns an {@link IWorkflowVariables} view over {@code state} - a fresh, cheap, stateless
         * wrapper each time, since {@code state} itself may be a {@link WorkflowStepType#PARALLEL}
         * branch's own isolated fork rather than this execution's single shared state (see {@link
         * ExecutionState}, added in 1.3.0).
         */
        private static IWorkflowVariables viewOf(ExecutionState state) {
            return new IWorkflowVariables() {
                @Override
                public <T> T require(WorkflowVariable<T> variable) {
                    VariableEntry entry = state.variables.get(variable.name());
                    if (entry == null || !entry.variable().equals(variable)) {
                        throw new WorkflowVariableMissingException(variable);
                    }
                    return variable.type().cast(entry.value());
                }

                @Override
                public <T> Optional<T> find(WorkflowVariable<T> variable) {
                    VariableEntry entry = state.variables.get(variable.name());
                    if (entry == null || !entry.variable().equals(variable)) {
                        return Optional.empty();
                    }
                    return Optional.of(variable.type().cast(entry.value()));
                }

                @Override
                public boolean exists(WorkflowVariable<?> variable) {
                    VariableEntry entry = state.variables.get(variable.name());
                    return entry != null && entry.variable().equals(variable);
                }
            };
        }

        /**
         * Calls {@code condition.describe()} at most once, defensively: a {@code RuntimeException}
         * or a {@code null} return is reported as a safe failure rather than propagated or stored
         * raw. A successful description is returned as crash-safe raw text, deliberately not yet
         * redacted or bounded - see {@link PendingConditionResult} and {@link #finalizeStepResult}.
         */
        private RawDescription describeConditionRaw(IWorkflowCondition condition) {
            String raw;
            try {
                raw = condition.describe();
            } catch (RuntimeException e) {
                return RawDescription.failed(
                        "condition description failed with " + e.getClass().getSimpleName(),
                        e.getClass().getName());
            }
            if (raw == null) {
                return RawDescription.failed("condition description was null", null);
            }
            return RawDescription.of(raw);
        }

        /**
         * Evaluates {@code condition} defensively - the same contract {@link IWorkflowCondition}
         * has always had here: a thrown {@link RuntimeException} or a malformed {@code describe()}
         * never propagates, it is captured as a failure description instead. Shared by a step's own
         * optional guard ({@link #executeStep}) and a {@link ConditionalWorkflowStep}'s mandatory
         * branch selector ({@link #executeConditionalStepInto}), which handle a failed evaluation
         * differently (guard: {@code CONDITION_EVALUATION_FAILED} on the guarded step; conditional:
         * the same failure type, but on the conditional step itself, per {@code
         * docs/workflow.md#branching}) but otherwise share this identical evaluate-once,
         * defend-against-throw-or-null-describe machinery rather than duplicating it.
         */
        private ConditionEvaluationResult evaluateConditionSafely(
                ExecutionState state, IWorkflowCondition condition) {
            boolean outcome;
            try {
                outcome = condition.evaluate(viewOf(state));
            } catch (WorkflowVariableMissingException e) {
                return ConditionEvaluationResult.failed(e.getMessage(), null);
            } catch (RuntimeException e) {
                RawDescription description = describeConditionRaw(condition);
                String message =
                        description.failed()
                                ? "condition threw " + e.getClass().getSimpleName()
                                : "condition '"
                                        + description.text()
                                        + "' threw "
                                        + e.getClass().getSimpleName();
                return ConditionEvaluationResult.failed(message, e.getClass().getName());
            }

            if (condition instanceof IDeferredConditionDescription deferred) {
                // Structurally crash-safe (framework-owned rendering only - see
                // IDeferredConditionDescription's Javadoc), so no describe()-failure check is
                // needed here: unlike a custom condition, this can never throw or return null.
                // SafeRendering.bounded is applied here, once, to whatever describeFinal produces
                // - exactly like the custom-condition branch below - rather than inside
                // describeFinal itself, so a composite of many built-in conditions is bounded as a
                // whole and cannot grow unbounded even though each leaf is individually safe.
                PendingConditionResult pending =
                        new PendingConditionResult(
                                outcome,
                                redactor ->
                                        SafeRendering.bounded(deferred.describeFinal(redactor)));
                return ConditionEvaluationResult.success(outcome, pending);
            }
            RawDescription description = describeConditionRaw(condition);
            if (description.failed()) {
                return ConditionEvaluationResult.failed(
                        description.failureMessage(), description.underlyingType());
            }
            PendingConditionResult pending =
                    new PendingConditionResult(
                            outcome,
                            redactor -> SafeRendering.bounded(redactor.redact(description.text())));
            return ConditionEvaluationResult.success(outcome, pending);
        }

        private PendingStepResult executeStep(
                IWorkflowStep step, String idSuffix, ExecutionState state) {
            // Safe: IWorkflowStep is sealed and permits only AWorkflowStep.
            AWorkflowStep concreteStep = (AWorkflowStep) step;
            WorkflowStepId resultId = qualify(step.id(), idSuffix);
            Optional<PendingConditionResult> conditionResult = Optional.empty();

            if (step.condition().isPresent()) {
                ConditionEvaluationResult evaluation =
                        evaluateConditionSafely(state, step.condition().get());
                if (evaluation.failed()) {
                    return failedResult(
                            resultId,
                            concreteStep.stepType(),
                            Optional.empty(),
                            WorkflowFailureType.CONDITION_EVALUATION_FAILED,
                            evaluation.failureMessage(),
                            evaluation.underlyingTypeName(),
                            null,
                            null,
                            state);
                }
                conditionResult = Optional.of(evaluation.pending());
                if (!evaluation.outcome()) {
                    return new PendingStepResult(
                            resultId,
                            concreteStep.stepType(),
                            WorkflowStepStatus.SKIPPED,
                            conditionResult,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty());
                }
            }

            StepRunOutcome outcome;
            try {
                outcome = concreteStep.run(viewOf(state));
            } catch (RuntimeException e) {
                return failedResult(
                        resultId,
                        concreteStep.stepType(),
                        conditionResult,
                        WorkflowFailureType.STEP_EXCEPTION,
                        "step '" + step.id() + "' threw " + e.getClass().getSimpleName(),
                        e.getClass().getName(),
                        null,
                        null,
                        state);
            }

            if (!outcome.success()) {
                return failedResult(
                        resultId,
                        concreteStep.stepType(),
                        conditionResult,
                        outcome.failureType(),
                        outcome.safeMessage(),
                        outcome.underlyingTypeName().orElse(null),
                        outcome.actionFailureType().orElse(null),
                        outcome.actionSummary().orElse(null),
                        state);
            }

            Optional<String> outputName = Optional.empty();
            Optional<WorkflowVariable<?>> outputVariable = concreteStep.outputVariable();
            if (outputVariable.isPresent()) {
                WorkflowVariable<?> variable = outputVariable.get();
                publishOutput(state, variable, outcome.value());
                outputName = Optional.of(variable.name());
            }

            return new PendingStepResult(
                    resultId,
                    concreteStep.stepType(),
                    WorkflowStepStatus.SUCCEEDED,
                    conditionResult,
                    outputName,
                    Optional.empty(),
                    outcome.actionSummary());
        }

        private static PendingStepResult failedResult(
                WorkflowStepId resultId,
                WorkflowStepType stepType,
                Optional<PendingConditionResult> conditionResult,
                WorkflowFailureType type,
                String message,
                String underlyingTypeName,
                io.webagent4j.action.ActionFailureType actionFailureType,
                WorkflowActionSummary actionSummary,
                ExecutionState state) {
            WorkflowFailure failure =
                    new WorkflowFailure(
                            type,
                            redact(state, message),
                            Optional.of(resultId),
                            Optional.ofNullable(underlyingTypeName),
                            Optional.ofNullable(actionFailureType));
            return new PendingStepResult(
                    resultId,
                    stepType,
                    WorkflowStepStatus.FAILED,
                    conditionResult,
                    Optional.empty(),
                    Optional.of(failure),
                    Optional.ofNullable(actionSummary));
        }
    }
}
