package io.webagent4j.workflow;

import java.util.ArrayList;
import java.util.HashSet;
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

        WorkflowResult run() {
            WorkflowResult inputFailure = validateAndSeedInputs();
            if (inputFailure != null) {
                return inputFailure;
            }

            List<PendingStepResult> pendingResults = new ArrayList<>();
            boolean failed = false;
            WorkflowFailure overallFailure = null;
            for (IWorkflowStep step : workflow.steps()) {
                PendingStepResult pending = executeStep(step);
                pendingResults.add(pending);
                if (pending.status() == WorkflowStepStatus.FAILED) {
                    failed = true;
                    overallFailure = pending.failure().orElseThrow();
                    break;
                }
            }

            if (failed) {
                List<IWorkflowStep> all = workflow.steps();
                for (int i = pendingResults.size(); i < all.size(); i++) {
                    pendingResults.add(notRun(all.get(i)));
                }
                return new WorkflowResult(
                        workflow.id(),
                        WorkflowStatus.FAILED,
                        finalizeStepResults(pendingResults),
                        outputs.build(activeSecrets),
                        Optional.of(overallFailure));
            }
            return new WorkflowResult(
                    workflow.id(),
                    WorkflowStatus.COMPLETED,
                    finalizeStepResults(pendingResults),
                    outputs.build(activeSecrets),
                    Optional.empty());
        }

        /**
         * Converts every {@link PendingStepResult} into a final, safe {@link WorkflowStepResult},
         * redacting each retained condition description against the workflow's complete secret set
         * at termination - not the set known when that condition was evaluated - and only then
         * bounding it. Invokes no caller-supplied code: {@code condition.describe()} was already
         * called, at most once, back when the step executed.
         */
        private List<WorkflowStepResult> finalizeStepResults(List<PendingStepResult> pending) {
            SecretRedactor finalRedactor = SecretRedactor.of(activeSecrets);
            List<WorkflowStepResult> results = new ArrayList<>(pending.size());
            for (PendingStepResult one : pending) {
                results.add(finalizeStepResult(one, finalRedactor));
            }
            return results;
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
        private WorkflowResult validateAndSeedInputs() {
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

        private WorkflowResult failBeforeExecution(WorkflowFailureType type, String message) {
            WorkflowFailure failure =
                    new WorkflowFailure(
                            type,
                            redact(message),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty());
            List<PendingStepResult> notRunSteps = new ArrayList<>();
            workflow.steps().forEach(step -> notRunSteps.add(notRun(step)));
            return new WorkflowResult(
                    workflow.id(),
                    WorkflowStatus.FAILED,
                    finalizeStepResults(notRunSteps),
                    WorkflowOutputs.empty(),
                    Optional.of(failure));
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

        private PendingStepResult executeStep(IWorkflowStep step) {
            // Safe: IWorkflowStep is sealed and permits only AWorkflowStep.
            AWorkflowStep concreteStep = (AWorkflowStep) step;
            Optional<PendingConditionResult> conditionResult = Optional.empty();

            if (step.condition().isPresent()) {
                IWorkflowCondition condition = step.condition().get();
                boolean outcome;
                try {
                    outcome = condition.evaluate(variablesView);
                } catch (WorkflowVariableMissingException e) {
                    return failedResult(
                            step,
                            concreteStep.stepType(),
                            Optional.empty(),
                            WorkflowFailureType.CONDITION_EVALUATION_FAILED,
                            e.getMessage(),
                            null,
                            null,
                            null);
                } catch (RuntimeException e) {
                    RawDescription description = describeConditionRaw(condition);
                    String message =
                            description.failed()
                                    ? "condition threw " + e.getClass().getSimpleName()
                                    : "condition '"
                                            + description.text()
                                            + "' threw "
                                            + e.getClass().getSimpleName();
                    return failedResult(
                            step,
                            concreteStep.stepType(),
                            Optional.empty(),
                            WorkflowFailureType.CONDITION_EVALUATION_FAILED,
                            message,
                            e.getClass().getName(),
                            null,
                            null);
                }

                if (condition instanceof IDeferredConditionDescription deferred) {
                    // Structurally crash-safe (framework-owned rendering only - see
                    // IDeferredConditionDescription's Javadoc), so no describe()-failure check is
                    // needed here: unlike a custom condition, this can never throw or return null.
                    // SafeRendering.bounded is applied here, once, to whatever describeFinal
                    // produces - exactly like the custom-condition branch below - rather than
                    // inside
                    // describeFinal itself, so a composite of many built-in conditions is bounded
                    // as
                    // a whole and cannot grow unbounded even though each leaf is individually safe.
                    conditionResult =
                            Optional.of(
                                    new PendingConditionResult(
                                            outcome,
                                            redactor ->
                                                    SafeRendering.bounded(
                                                            deferred.describeFinal(redactor))));
                } else {
                    RawDescription description = describeConditionRaw(condition);
                    if (description.failed()) {
                        return failedResult(
                                step,
                                concreteStep.stepType(),
                                Optional.empty(),
                                WorkflowFailureType.CONDITION_EVALUATION_FAILED,
                                description.failureMessage(),
                                description.underlyingType(),
                                null,
                                null);
                    }
                    conditionResult =
                            Optional.of(
                                    new PendingConditionResult(
                                            outcome,
                                            redactor ->
                                                    SafeRendering.bounded(
                                                            redactor.redact(description.text()))));
                }
                if (!outcome) {
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
