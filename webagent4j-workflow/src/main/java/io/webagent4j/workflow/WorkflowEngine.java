package io.webagent4j.workflow;

import io.webagent4j.workflow.internal.IExecutableWorkflowStep;
import io.webagent4j.workflow.internal.SecretRedactor;
import io.webagent4j.workflow.internal.StepRunOutcome;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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

        private final Workflow workflow;
        private final WorkflowInputs inputs;
        private final Map<String, VariableEntry> variables = new LinkedHashMap<>();
        private final List<String> activeSecrets = new ArrayList<>();
        private final WorkflowOutputs.Builder outputs = new WorkflowOutputs.Builder();
        private final List<WorkflowStepResult> stepResults = new ArrayList<>();

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
                if (entry != null && entry.variable().equals(optional)) {
                    seedVariable(entry.variable(), entry.value());
                }
            }

            boolean failed = false;
            WorkflowFailure overallFailure = null;
            for (IWorkflowStep step : workflow.steps()) {
                WorkflowStepResult result = executeStep(step);
                stepResults.add(result);
                if (result.status() == WorkflowStepStatus.FAILED) {
                    failed = true;
                    overallFailure = result.failure().orElseThrow();
                    break;
                }
            }

            if (failed) {
                List<IWorkflowStep> all = workflow.steps();
                for (int i = stepResults.size(); i < all.size(); i++) {
                    stepResults.add(notRun(all.get(i)));
                }
                return new WorkflowResult(
                        workflow.id(),
                        WorkflowStatus.FAILED,
                        stepResults,
                        outputs.build(),
                        Optional.of(overallFailure));
            }
            return new WorkflowResult(
                    workflow.id(),
                    WorkflowStatus.COMPLETED,
                    stepResults,
                    outputs.build(),
                    Optional.empty());
        }

        private WorkflowResult failBeforeExecution(WorkflowFailureType type, String message) {
            WorkflowFailure failure =
                    new WorkflowFailure(
                            type,
                            redact(message),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty());
            List<WorkflowStepResult> notRunSteps = new ArrayList<>();
            workflow.steps().forEach(step -> notRunSteps.add(notRun(step)));
            return new WorkflowResult(
                    workflow.id(),
                    WorkflowStatus.FAILED,
                    notRunSteps,
                    WorkflowOutputs.empty(),
                    Optional.of(failure));
        }

        private static WorkflowStepResult notRun(IWorkflowStep step) {
            return new WorkflowStepResult(
                    step.id(),
                    ((IExecutableWorkflowStep) step).stepType(),
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

        private String redact(String message) {
            return SecretRedactor.of(activeSecrets).redact(message);
        }

        private WorkflowStepResult executeStep(IWorkflowStep step) {
            IExecutableWorkflowStep executable = (IExecutableWorkflowStep) step;
            Optional<WorkflowConditionResult> conditionResult = Optional.empty();

            if (step.condition().isPresent()) {
                IWorkflowCondition condition = step.condition().get();
                boolean outcome;
                try {
                    outcome = condition.evaluate(variablesView);
                } catch (WorkflowVariableMissingException e) {
                    return failedResult(
                            step,
                            executable.stepType(),
                            Optional.empty(),
                            WorkflowFailureType.CONDITION_EVALUATION_FAILED,
                            e.getMessage(),
                            null,
                            null,
                            null);
                } catch (RuntimeException e) {
                    return failedResult(
                            step,
                            executable.stepType(),
                            Optional.empty(),
                            WorkflowFailureType.CONDITION_EVALUATION_FAILED,
                            "condition '"
                                    + condition.describe()
                                    + "' threw "
                                    + e.getClass().getSimpleName(),
                            e.getClass().getName(),
                            null,
                            null);
                }
                conditionResult =
                        Optional.of(new WorkflowConditionResult(outcome, condition.describe()));
                if (!outcome) {
                    return new WorkflowStepResult(
                            step.id(),
                            executable.stepType(),
                            WorkflowStepStatus.SKIPPED,
                            conditionResult,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty());
                }
            }

            StepRunOutcome outcome;
            try {
                outcome = executable.run(variablesView);
            } catch (RuntimeException e) {
                return failedResult(
                        step,
                        executable.stepType(),
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
                        executable.stepType(),
                        conditionResult,
                        outcome.failureType(),
                        outcome.safeMessage(),
                        outcome.underlyingTypeName().orElse(null),
                        outcome.actionFailureType().orElse(null),
                        outcome.actionSummary().orElse(null));
            }

            Optional<String> outputName = Optional.empty();
            Optional<WorkflowVariable<?>> outputVariable = executable.outputVariable();
            if (outputVariable.isPresent()) {
                WorkflowVariable<?> variable = outputVariable.get();
                publishOutput(variable, outcome.value());
                outputName = Optional.of(variable.name());
            }

            return new WorkflowStepResult(
                    step.id(),
                    executable.stepType(),
                    WorkflowStepStatus.SUCCEEDED,
                    conditionResult,
                    outputName,
                    Optional.empty(),
                    outcome.actionSummary());
        }

        private WorkflowStepResult failedResult(
                IWorkflowStep step,
                WorkflowStepType stepType,
                Optional<WorkflowConditionResult> conditionResult,
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
            return new WorkflowStepResult(
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
