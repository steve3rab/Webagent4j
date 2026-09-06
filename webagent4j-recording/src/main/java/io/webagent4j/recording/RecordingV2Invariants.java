package io.webagent4j.recording;

import io.webagent4j.workflow.WorkflowFailureType;
import io.webagent4j.workflow.WorkflowStatus;
import io.webagent4j.workflow.WorkflowStepId;
import io.webagent4j.workflow.WorkflowStepStatus;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Global, cross-node invariants for {@link WorkflowRecordingV2}, factored out of its compact
 * constructor so the identical checks apply whichever way a recording is built - there is no
 * separate construction path that could bypass them.
 *
 * <p>{@code WorkflowEngine.Session#runSteps} appends a {@code CONDITIONAL} step's selected branch's
 * own results into the very same flat accumulator immediately after that step's own result,
 * recursively, before moving on to the next sibling - so pre-order traversal of a {@link
 * WorkflowRecordingV2}'s node tree (a node, then its children, then the next sibling) always
 * reconstructs the identical step order the engine's own flat {@code WorkflowResult#steps()} would
 * have produced for the same execution. This class flattens the tree that way and then applies the
 * same fail-fast/preflight-vs-runtime shape checks {@link RecordingInvariants} applies to Recording
 * V1's already-flat list - see that class's Javadoc for the full rationale, which is unchanged for
 * V2's tree-shaped representation.
 *
 * <p>One additional check has no V1 analog: a step that never started ({@link
 * WorkflowStepStatus#NOT_RUN}) can never have gone on to select and enter a branch, so its own
 * execution node can never carry a branch selection or children - {@code
 * WorkflowEngine.Session#failBeforeExecution} and the {@code runSteps} NOT_RUN tail-marking loop
 * both always pair a {@code NOT_RUN} step with an empty-children, no-selection node, and a
 * recording that claims otherwise could never result from a real execution.
 */
final class RecordingV2Invariants {

    private static final Set<WorkflowFailureType> PREFLIGHT_FAILURE_TYPES =
            EnumSet.of(
                    WorkflowFailureType.MISSING_REQUIRED_INPUT,
                    WorkflowFailureType.INPUT_TYPE_MISMATCH,
                    WorkflowFailureType.UNDECLARED_INPUT);

    private RecordingV2Invariants() {}

    static void validate(
            WorkflowStatus status,
            List<RecordedExecutionNodeV2> nodes,
            Optional<RecordedFailure> failure) {
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("a recording must contain at least one node");
        }
        requireNotRunNodesCarryNoSelection(nodes);
        List<RecordedWorkflowStepV2> flattened = flatten(nodes);
        requireUniqueStepIds(flattened);
        if (status == WorkflowStatus.COMPLETED) {
            requireOnlySucceededOrSkipped(flattened);
        } else {
            requireValidFailedTrace(flattened, failure.orElseThrow());
        }
    }

    private static List<RecordedWorkflowStepV2> flatten(List<RecordedExecutionNodeV2> nodes) {
        List<RecordedWorkflowStepV2> flat = new ArrayList<>();
        flattenInto(nodes, flat);
        return flat;
    }

    private static void flattenInto(
            List<RecordedExecutionNodeV2> nodes, List<RecordedWorkflowStepV2> flat) {
        for (RecordedExecutionNodeV2 node : nodes) {
            flat.add(node.step());
            flattenInto(node.children(), flat);
        }
    }

    private static void requireNotRunNodesCarryNoSelection(List<RecordedExecutionNodeV2> nodes) {
        for (RecordedExecutionNodeV2 node : nodes) {
            if (node.step().status() == WorkflowStepStatus.NOT_RUN
                    && (node.branchSelection().isPresent() || !node.children().isEmpty())) {
                throw new IllegalArgumentException(
                        "a NOT_RUN step's execution node cannot carry a branch selection or"
                                + " children");
            }
            requireNotRunNodesCarryNoSelection(node.children());
        }
    }

    private static void requireUniqueStepIds(List<RecordedWorkflowStepV2> steps) {
        Set<WorkflowStepId> seen = new HashSet<>();
        for (RecordedWorkflowStepV2 step : steps) {
            if (!seen.add(step.stepId())) {
                throw new IllegalArgumentException("a recording cannot contain duplicate step IDs");
            }
        }
    }

    private static void requireOnlySucceededOrSkipped(List<RecordedWorkflowStepV2> steps) {
        for (RecordedWorkflowStepV2 step : steps) {
            if (step.status() != WorkflowStepStatus.SUCCEEDED
                    && step.status() != WorkflowStepStatus.SKIPPED) {
                throw new IllegalArgumentException(
                        "a COMPLETED recording's steps must all be SUCCEEDED or SKIPPED");
            }
        }
    }

    private static void requireValidFailedTrace(
            List<RecordedWorkflowStepV2> steps, RecordedFailure failure) {
        if (PREFLIGHT_FAILURE_TYPES.contains(failure.type())) {
            requirePreflightShape(steps, failure);
        } else {
            requireRuntimeShape(steps, failure);
        }
    }

    private static void requirePreflightShape(
            List<RecordedWorkflowStepV2> steps, RecordedFailure failure) {
        if (failure.stepId().isPresent()) {
            throw new IllegalArgumentException("a preflight failure cannot carry a stepId");
        }
        if (failure.underlyingTypeName().isPresent()) {
            throw new IllegalArgumentException(
                    "a preflight failure cannot carry an underlying exception type name");
        }
        if (failure.actionFailureType().isPresent()) {
            throw new IllegalArgumentException(
                    "a preflight failure cannot carry an ActionFailureType");
        }
        requireAllNotRun(steps);
    }

    private static void requireRuntimeShape(
            List<RecordedWorkflowStepV2> steps, RecordedFailure failure) {
        if (failure.stepId().isEmpty()) {
            throw new IllegalArgumentException(
                    "a non-preflight failure type must carry the failing step's stepId");
        }
        int failedIndex = requireExactlyOneFailedStep(steps);
        RecordedWorkflowStepV2 failedStep = steps.get(failedIndex);
        if (!failedStep.stepId().equals(failure.stepId().get())) {
            throw new IllegalArgumentException(
                    "the overall failure's stepId must match the FAILED step's stepId");
        }
        if (!failedStep.failure().orElseThrow().equals(failure)) {
            throw new IllegalArgumentException(
                    "the overall failure must be identical to the FAILED step's own failure");
        }
        for (int i = 0; i < failedIndex; i++) {
            WorkflowStepStatus s = steps.get(i).status();
            if (s != WorkflowStepStatus.SUCCEEDED && s != WorkflowStepStatus.SKIPPED) {
                throw new IllegalArgumentException(
                        "every step before the FAILED step must be SUCCEEDED or SKIPPED");
            }
        }
        for (int i = failedIndex + 1; i < steps.size(); i++) {
            if (steps.get(i).status() != WorkflowStepStatus.NOT_RUN) {
                throw new IllegalArgumentException(
                        "every step after the FAILED step must be NOT_RUN");
            }
        }
    }

    private static void requireAllNotRun(List<RecordedWorkflowStepV2> steps) {
        for (RecordedWorkflowStepV2 step : steps) {
            if (step.status() != WorkflowStepStatus.NOT_RUN) {
                throw new IllegalArgumentException(
                        "a preflight-failure recording must have every step NOT_RUN");
            }
        }
    }

    private static int requireExactlyOneFailedStep(List<RecordedWorkflowStepV2> steps) {
        int failedIndex = -1;
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).status() == WorkflowStepStatus.FAILED) {
                if (failedIndex != -1) {
                    throw new IllegalArgumentException(
                            "a FAILED recording with a step-associated failure must have exactly"
                                    + " one FAILED step");
                }
                failedIndex = i;
            }
        }
        if (failedIndex == -1) {
            throw new IllegalArgumentException(
                    "a FAILED recording whose overall failure has a stepId must have a matching"
                            + " FAILED step");
        }
        return failedIndex;
    }
}
