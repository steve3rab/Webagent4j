package io.webagent4j.recording;

import io.webagent4j.workflow.WorkflowActionSummary;
import io.webagent4j.workflow.WorkflowConditionResult;
import io.webagent4j.workflow.WorkflowExecution;
import io.webagent4j.workflow.WorkflowExecutionNode;
import io.webagent4j.workflow.WorkflowExecutionPlan;
import io.webagent4j.workflow.WorkflowFailure;
import io.webagent4j.workflow.WorkflowPlanBranch;
import io.webagent4j.workflow.WorkflowPlanNode;
import io.webagent4j.workflow.WorkflowPlanOutput;
import io.webagent4j.workflow.WorkflowStepId;
import io.webagent4j.workflow.WorkflowStepResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Captures a {@code WorkflowExecution} (a {@code WorkflowResult} plus its {@code
 * WorkflowExecutionTree}, as returned by {@code WorkflowEngine#executeWithTree}) into an immutable
 * {@link WorkflowRecordingV2}, alongside the same execution's {@link WorkflowExecutionPlan} - the
 * Recording V2 counterpart of {@link WorkflowRecorder}.
 *
 * <p><b>{@code plan} must describe the exact workflow that produced {@code execution}:</b> both are
 * ordinary caller-supplied arguments, obtained independently (typically {@code
 * WorkflowPlanner.plan(workflow)} and {@code engine.executeWithTree(workflow, inputs)} against the
 * very same {@code workflow} instance), and this recorder does not itself execute anything or
 * derive one from the other. A step whose result reports a published output but whose plan carries
 * no corresponding declared output is rejected outright (see {@link #recordStep}) rather than
 * silently recording no output, since silently doing so could misrepresent whether - and under what
 * secret classification - a value was actually published.
 *
 * <p><b>Secret-safety boundary:</b> identical in scope to {@link WorkflowRecorder}'s - this
 * recorder never reads {@code WorkflowInputs}, raw {@code WorkflowOutputs}, {@code
 * WorkflowResult#output(WorkflowVariable)}, {@code ActionResult.value}, action observations or
 * diagnostics, raw {@code Throwable} data, or the workflow secret registry. A published output's
 * {@link WorkflowPlanOutput#secret()} classification is recorded - never the value itself, which
 * this recorder never touches - taken from {@code plan}'s own already-static, already-safe
 * structural description rather than from anything execution-observed. Condition descriptions and
 * failure messages copied from {@code WorkflowResult}/{@code WorkflowExecutionTree} have already
 * been redacted by {@code WorkflowEngine}.
 */
public final class WorkflowRecorderV2 {

    /** Creates a recorder. Stateless: a single instance may record any number of executions. */
    public WorkflowRecorderV2() {}

    /**
     * Records {@code execution} (captured against {@code plan}'s own workflow) as an immutable
     * {@link WorkflowRecordingV2}.
     *
     * @param recordingId the non-sensitive caller-supplied identifier, persisted verbatim
     * @param capturedAt the caller-supplied capture time
     * @param plan the executed workflow's own structural plan
     * @param execution the workflow execution outcome to record
     * @throws IllegalArgumentException if a step in {@code execution} published an output {@code
     *     plan} does not declare, or if {@code plan}/{@code execution} otherwise cannot form a
     *     valid {@link WorkflowRecordingV2} together (see {@link RecordingV2Invariants})
     */
    public WorkflowRecordingV2 record(
            RecordingId recordingId,
            Instant capturedAt,
            WorkflowExecutionPlan plan,
            WorkflowExecution execution) {
        Objects.requireNonNull(recordingId, "recordingId");
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(execution, "execution");
        Map<WorkflowStepId, WorkflowPlanOutput> declaredOutputs =
                indexDeclaredOutputs(plan.nodes());
        List<RecordedExecutionNodeV2> nodes =
                recordNodes(execution.tree().nodes(), declaredOutputs);
        return new WorkflowRecordingV2(
                RecordingSchemaVersionV2.V2,
                recordingId,
                capturedAt,
                execution.result().workflowId(),
                execution.result().status(),
                plan,
                nodes,
                execution.result().failure().map(WorkflowRecorderV2::recordFailure));
    }

    private static Map<WorkflowStepId, WorkflowPlanOutput> indexDeclaredOutputs(
            List<WorkflowPlanNode> nodes) {
        Map<WorkflowStepId, WorkflowPlanOutput> index = new HashMap<>();
        indexDeclaredOutputsInto(nodes, index);
        return index;
    }

    private static void indexDeclaredOutputsInto(
            List<WorkflowPlanNode> nodes, Map<WorkflowStepId, WorkflowPlanOutput> index) {
        for (WorkflowPlanNode node : nodes) {
            node.declaredOutput().ifPresent(output -> index.put(node.stepId(), output));
            for (WorkflowPlanBranch branch : node.branches()) {
                indexDeclaredOutputsInto(branch.nodes(), index);
            }
        }
    }

    private static List<RecordedExecutionNodeV2> recordNodes(
            List<WorkflowExecutionNode> nodes,
            Map<WorkflowStepId, WorkflowPlanOutput> declaredOutputs) {
        List<RecordedExecutionNodeV2> recorded = new ArrayList<>(nodes.size());
        for (WorkflowExecutionNode node : nodes) {
            recorded.add(recordNode(node, declaredOutputs));
        }
        return recorded;
    }

    private static RecordedExecutionNodeV2 recordNode(
            WorkflowExecutionNode node, Map<WorkflowStepId, WorkflowPlanOutput> declaredOutputs) {
        RecordedWorkflowStepV2 step = recordStep(node.result(), declaredOutputs);
        List<RecordedExecutionNodeV2> children = recordNodes(node.children(), declaredOutputs);
        return new RecordedExecutionNodeV2(step, node.branchSelection(), children);
    }

    private static RecordedWorkflowStepV2 recordStep(
            WorkflowStepResult step, Map<WorkflowStepId, WorkflowPlanOutput> declaredOutputs) {
        Optional<WorkflowPlanOutput> output = Optional.empty();
        if (step.outputVariableName().isPresent()) {
            WorkflowPlanOutput declared = declaredOutputs.get(step.stepId());
            if (declared == null) {
                throw new IllegalArgumentException(
                        "plan does not declare an output for a step the execution published one"
                                + " for - plan and execution must describe the same workflow");
            }
            output = Optional.of(declared);
        }
        return new RecordedWorkflowStepV2(
                step.stepId(),
                step.stepType(),
                step.status(),
                step.condition().map(WorkflowRecorderV2::recordCondition),
                output,
                step.failure().map(WorkflowRecorderV2::recordFailure),
                step.actionSummary().map(WorkflowRecorderV2::recordAction));
    }

    private static RecordedCondition recordCondition(WorkflowConditionResult condition) {
        return new RecordedCondition(condition.outcome(), condition.description());
    }

    private static RecordedAction recordAction(WorkflowActionSummary action) {
        return new RecordedAction(
                action.actionId(), action.actionType(), action.status(), action.executionMode());
    }

    private static RecordedFailure recordFailure(WorkflowFailure failure) {
        return new RecordedFailure(
                failure.type(),
                failure.safeMessage(),
                failure.stepId(),
                failure.underlyingTypeName(),
                failure.actionFailureType());
    }
}
