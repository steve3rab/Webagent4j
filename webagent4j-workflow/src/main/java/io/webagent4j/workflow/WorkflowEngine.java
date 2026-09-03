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
import java.util.function.Function;

/**
 * Deterministic, single-lane executor for {@link Workflow} definitions.
 *
 * <p>{@code WorkflowEngine} itself is stateless and safe to reuse: {@link #execute} constructs a
 * private, per-call session holding all mutable execution state (variables, discovered secrets,
 * step results), mirroring how {@code BrowserCrawler} isolates one crawl's state per call. Two
 * {@code execute} calls against the same {@link Workflow} - even concurrently - never share
 * variable state, discovered secrets, or step results.
 *
 * <p><b>Execution model:</b> strictly sequential and fail-fast. Every step runs, in definition
 * order, on the exact thread that calls {@link #execute} - there is no {@code ExecutorService}, no
 * {@code CompletableFuture}, and no parallelism anywhere in this engine, so a caller-owned resource
 * an action factory closes over (an {@code IPage}, for example) is never touched from a different
 * thread. The first step that fails stops execution immediately; every later step is recorded as
 * {@link WorkflowStepStatus#NOT_RUN}. A failed action is never retried: {@code WorkflowEngine} adds
 * no retry layer on top of the action layer's own safe resolution retries.
 *
 * <p><b>No workflow-wide timeout, no cancellation:</b> this phase relies entirely on the timeout
 * semantics of the underlying action/browser backend for any one step; there is no dishonest
 * Java-side deadline wrapped around an otherwise-unbounded call (see {@code
 * docs/workflow.md#limitations}).
 *
 * <p><b>Resource ownership:</b> {@code WorkflowEngine} owns nothing a caller supplied - no browser,
 * no page, no action backend - and never closes anything an {@link IWorkflowActionFactory}
 * captures.
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
 * deferred: once a step fails, no later step runs, so no later secret can ever be discovered.
 *
 * <p><b>Structured execution tree:</b> every {@link Session#run()} call builds, in the same single
 * pass that already produces {@link WorkflowResult#steps()}, a parallel hierarchical view - see
 * {@link #executeWithTree(Workflow, WorkflowInputs)} and {@code docs/workflow.md#execution-tree}.
 * Both views share the exact same {@link WorkflowStepResult} instances; the tree is never built by
 * a second interpretation of the flat list, and building or reading it never evaluates a condition,
 * invokes an action, or selects a branch again.
 */
public final class WorkflowEngine {

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

        private final Workflow workflow;
        private final WorkflowInputs inputs;
        private final Map<String, VariableEntry> variables = new LinkedHashMap<>();
        private final List<String> activeSecrets = new ArrayList<>();
        private final WorkflowOutputs.Builder outputs = new WorkflowOutputs.Builder();

        private final IWorkflowVariables variablesView =
                new IWorkflowVariables() {
                    @Override
                    public <T> T require(WorkflowVariable<T> variable) {
                        VariableEntry entry = variables.get(variable.name());
                        if (entry == null || !entry.variable().equals(variable)) {
                            throw new WorkflowVariableMissingException(variable);
                        }
                        return variable.type().cast(entry.value());
                    }

                    @Override
                    public <T> Optional<T> find(WorkflowVariable<T> variable) {
                        VariableEntry entry = variables.get(variable.name());
                        if (entry == null || !entry.variable().equals(variable)) {
                            return Optional.empty();
                        }
                        return Optional.of(variable.type().cast(entry.value()));
                    }

                    @Override
                    public boolean exists(WorkflowVariable<?> variable) {
                        VariableEntry entry = variables.get(variable.name());
                        return entry != null && entry.variable().equals(variable);
                    }
                };

        Session(Workflow workflow, WorkflowInputs inputs) {
            this.workflow = workflow;
            this.inputs = inputs;
        }

        WorkflowExecution run() {
            WorkflowExecution inputFailure = validateAndSeedInputs();
            if (inputFailure != null) {
                return inputFailure;
            }

            List<PendingStepResult> pendingResults = new ArrayList<>();
            List<PendingExecutionNode> pendingNodes = new ArrayList<>();
            Optional<WorkflowFailure> overallFailure =
                    runSteps(workflow.steps(), pendingResults, pendingNodes);

            WorkflowStatus status =
                    overallFailure.isPresent() ? WorkflowStatus.FAILED : WorkflowStatus.COMPLETED;
            FinalizedSteps finalized = finalizeStepResults(pendingResults);
            WorkflowResult result =
                    new WorkflowResult(
                            workflow.id(),
                            status,
                            finalized.steps(),
                            outputs.build(activeSecrets),
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
         * inside a conditional step's selected branch - and marks every step remaining in {@code
         * steps} from that point on {@link WorkflowStepStatus#NOT_RUN}, exactly like the top-level
         * fail-fast/short-circuit behavior this engine has always had (see the class Javadoc);
         * {@link #run()} calls this once for the workflow's own top-level steps, and a conditional
         * step's branch is executed by recursing into this same method, so both levels share one
         * fail-fast mechanism rather than two independently-maintained copies of it.
         *
         * <p>This recursion is not separately depth-bounded here: {@link Workflow.Builder#build()}
         * is the only way to obtain a {@link Workflow}, and it already rejects a conditional nested
         * deeper than {@link Workflow#MAX_CONDITIONAL_NESTING_DEPTH} before returning one (see that
         * constant's Javadoc), so every {@code workflow} this method can ever be called with is
         * already bounded - a second, independent runtime limit here would duplicate that single
         * source of truth rather than add real protection. {@code nodeAccumulator} mirrors {@code
         * accumulator} one-for-one - the same steps, same order, same NOT_RUN marking - so the
         * execution tree and the flat list can never diverge; see {@link #freezeNodes}.
         */
        private Optional<WorkflowFailure> runSteps(
                List<IWorkflowStep> steps,
                List<PendingStepResult> accumulator,
                List<PendingExecutionNode> nodeAccumulator) {
            for (int i = 0; i < steps.size(); i++) {
                Optional<WorkflowFailure> failure =
                        executeStepInto(steps.get(i), accumulator, nodeAccumulator);
                if (failure.isPresent()) {
                    for (int j = i + 1; j < steps.size(); j++) {
                        PendingStepResult notRunResult = notRun(steps.get(j));
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
                List<PendingStepResult> accumulator,
                List<PendingExecutionNode> nodeAccumulator) {
            // Safe: IWorkflowStep is sealed and permits only AWorkflowStep.
            AWorkflowStep concreteStep = (AWorkflowStep) step;
            if (concreteStep instanceof ConditionalWorkflowStep conditional) {
                return executeConditionalStepInto(conditional, accumulator, nodeAccumulator);
            }
            PendingStepResult pending = executeStep(step);
            accumulator.add(pending);
            nodeAccumulator.add(new PendingExecutionNode(pending, Optional.empty(), List.of()));
            return pending.status() == WorkflowStepStatus.FAILED
                    ? pending.failure()
                    : Optional.empty();
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
                List<PendingStepResult> accumulator,
                List<PendingExecutionNode> nodeAccumulator) {
            if (Thread.currentThread().isInterrupted()) {
                return addInterrupted(
                        step,
                        Optional.empty(),
                        Optional.empty(),
                        "the executing thread was interrupted before the branch condition could"
                                + " be evaluated",
                        accumulator,
                        nodeAccumulator);
            }

            ConditionEvaluationResult evaluation = evaluateConditionSafely(step.branchCondition());
            if (evaluation.failed()) {
                PendingStepResult pending =
                        failedResult(
                                step,
                                WorkflowStepType.CONDITIONAL,
                                Optional.empty(),
                                WorkflowFailureType.CONDITION_EVALUATION_FAILED,
                                evaluation.failureMessage(),
                                evaluation.underlyingTypeName(),
                                null,
                                null);
                accumulator.add(pending);
                nodeAccumulator.add(new PendingExecutionNode(pending, Optional.empty(), List.of()));
                return pending.failure();
            }
            Optional<PendingConditionResult> conditionResult = Optional.of(evaluation.pending());
            WorkflowBranchSelection selection = branchSelectionFor(step, evaluation.outcome());

            if (Thread.currentThread().isInterrupted()) {
                return addInterrupted(
                        step,
                        conditionResult,
                        Optional.of(selection),
                        "the executing thread was interrupted after the branch decision was"
                                + " captured but before the selected branch could start",
                        accumulator,
                        nodeAccumulator);
            }

            List<IWorkflowStep> selectedBranch =
                    evaluation.outcome() ? step.thenSteps() : step.elseSteps().orElse(List.of());
            PendingStepResult conditionalResult =
                    new PendingStepResult(
                            step.id(),
                            WorkflowStepType.CONDITIONAL,
                            WorkflowStepStatus.SUCCEEDED,
                            conditionResult,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty());
            accumulator.add(conditionalResult);
            List<PendingExecutionNode> branchChildren = new ArrayList<>();
            Optional<WorkflowFailure> failure =
                    runSteps(selectedBranch, accumulator, branchChildren);
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

        private Optional<WorkflowFailure> addInterrupted(
                ConditionalWorkflowStep step,
                Optional<PendingConditionResult> conditionResult,
                Optional<WorkflowBranchSelection> branchSelection,
                String message,
                List<PendingStepResult> accumulator,
                List<PendingExecutionNode> nodeAccumulator) {
            PendingStepResult pending =
                    failedResult(
                            step,
                            WorkflowStepType.CONDITIONAL,
                            conditionResult,
                            WorkflowFailureType.CONDITIONAL_STEP_INTERRUPTED,
                            message,
                            null,
                            null,
                            null);
            accumulator.add(pending);
            nodeAccumulator.add(new PendingExecutionNode(pending, branchSelection, List.of()));
            return pending.failure();
        }

        /**
         * Converts every {@link PendingStepResult} into a final, safe {@link WorkflowStepResult},
         * redacting each retained condition description against the workflow's complete secret set
         * at termination - not the set known when that condition was evaluated - and only then
         * bounding it. Invokes no caller-supplied code: {@code condition.describe()} was already
         * called, at most once, back when the step executed.
         */
        private FinalizedSteps finalizeStepResults(List<PendingStepResult> pending) {
            SecretRedactor finalRedactor = SecretRedactor.of(activeSecrets);
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
        private WorkflowExecution validateAndSeedInputs() {
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
                seedVariable(entry.variable(), entry.value());
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
                seedVariable(entry.variable(), entry.value());
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
                            redact(message),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty());
            List<PendingStepResult> notRunSteps = new ArrayList<>();
            List<PendingExecutionNode> notRunNodes = new ArrayList<>();
            for (IWorkflowStep step : workflow.steps()) {
                PendingStepResult pending = notRun(step);
                notRunSteps.add(pending);
                notRunNodes.add(new PendingExecutionNode(pending, Optional.empty(), List.of()));
            }
            FinalizedSteps finalized = finalizeStepResults(notRunSteps);
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

        private static PendingStepResult notRun(IWorkflowStep step) {
            // Safe: IWorkflowStep is sealed and permits only AWorkflowStep.
            return new PendingStepResult(
                    step.id(),
                    ((AWorkflowStep) step).stepType(),
                    WorkflowStepStatus.NOT_RUN,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
        }

        private void seedVariable(WorkflowVariable<?> variable, Object value) {
            variables.put(variable.name(), new VariableEntry(variable, value));
            if (variable.secret() && value instanceof String secretValue) {
                activeSecrets.add(secretValue);
            }
        }

        private <T> void publishOutput(WorkflowVariable<T> variable, Object value) {
            T typed = variable.type().cast(value);
            seedVariable(variable, typed);
            outputs.put(variable, typed);
        }

        /** Redacts every currently-known secret, then bounds the result - never the other order. */
        private String redact(String message) {
            String safe = message == null ? "<no message>" : message;
            return SafeRendering.bounded(SecretRedactor.of(activeSecrets).redact(safe));
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
        private ConditionEvaluationResult evaluateConditionSafely(IWorkflowCondition condition) {
            boolean outcome;
            try {
                outcome = condition.evaluate(variablesView);
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

        private PendingStepResult executeStep(IWorkflowStep step) {
            // Safe: IWorkflowStep is sealed and permits only AWorkflowStep.
            AWorkflowStep concreteStep = (AWorkflowStep) step;
            Optional<PendingConditionResult> conditionResult = Optional.empty();

            if (step.condition().isPresent()) {
                ConditionEvaluationResult evaluation =
                        evaluateConditionSafely(step.condition().get());
                if (evaluation.failed()) {
                    return failedResult(
                            step,
                            concreteStep.stepType(),
                            Optional.empty(),
                            WorkflowFailureType.CONDITION_EVALUATION_FAILED,
                            evaluation.failureMessage(),
                            evaluation.underlyingTypeName(),
                            null,
                            null);
                }
                conditionResult = Optional.of(evaluation.pending());
                if (!evaluation.outcome()) {
                    return new PendingStepResult(
                            step.id(),
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
                outcome = concreteStep.run(variablesView);
            } catch (RuntimeException e) {
                return failedResult(
                        step,
                        concreteStep.stepType(),
                        conditionResult,
                        WorkflowFailureType.STEP_EXCEPTION,
                        "step '" + step.id() + "' threw " + e.getClass().getSimpleName(),
                        e.getClass().getName(),
                        null,
                        null);
            }

            if (!outcome.success()) {
                return failedResult(
                        step,
                        concreteStep.stepType(),
                        conditionResult,
                        outcome.failureType(),
                        outcome.safeMessage(),
                        outcome.underlyingTypeName().orElse(null),
                        outcome.actionFailureType().orElse(null),
                        outcome.actionSummary().orElse(null));
            }

            Optional<String> outputName = Optional.empty();
            Optional<WorkflowVariable<?>> outputVariable = concreteStep.outputVariable();
            if (outputVariable.isPresent()) {
                WorkflowVariable<?> variable = outputVariable.get();
                publishOutput(variable, outcome.value());
                outputName = Optional.of(variable.name());
            }

            return new PendingStepResult(
                    step.id(),
                    concreteStep.stepType(),
                    WorkflowStepStatus.SUCCEEDED,
                    conditionResult,
                    outputName,
                    Optional.empty(),
                    outcome.actionSummary());
        }

        private PendingStepResult failedResult(
                IWorkflowStep step,
                WorkflowStepType stepType,
                Optional<PendingConditionResult> conditionResult,
                WorkflowFailureType type,
                String message,
                String underlyingTypeName,
                io.webagent4j.action.ActionFailureType actionFailureType,
                WorkflowActionSummary actionSummary) {
            WorkflowFailure failure =
                    new WorkflowFailure(
                            type,
                            redact(message),
                            Optional.of(step.id()),
                            Optional.ofNullable(underlyingTypeName),
                            Optional.ofNullable(actionFailureType));
            return new PendingStepResult(
                    step.id(),
                    stepType,
                    WorkflowStepStatus.FAILED,
                    conditionResult,
                    Optional.empty(),
                    Optional.of(failure),
                    Optional.ofNullable(actionSummary));
        }
    }
}
