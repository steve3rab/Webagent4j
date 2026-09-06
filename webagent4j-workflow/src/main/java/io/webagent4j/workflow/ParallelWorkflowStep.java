package io.webagent4j.workflow;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic bounded-parallelism workflow step - not public API. Produced only by {@link
 * WorkflowSteps#parallel}.
 *
 * <p>Unlike {@link ConditionalWorkflowStep} and {@link LoopWorkflowStep}, the condition carried
 * here (in the inherited {@code condition} slot) is an ordinary <em>optional</em> {@link
 * IWorkflowStep#when} skip-guard - exactly like {@link ActionWorkflowStep} and {@link
 * AssignWorkflowStep} - never a mandatory branch selector or continuation check: a {@code PARALLEL}
 * step has no decision of its own to make, so {@link #withCondition} is fully supported here, and a
 * {@code false} guard produces {@link WorkflowStepStatus#SKIPPED} with zero branches launched,
 * exactly like a guarded {@code ACTION}/{@code ASSIGN} step (see {@code
 * docs/workflow.md#parallel}).
 *
 * <p>{@link #branches()} always holds at least {@link Workflow#MIN_PARALLEL_BRANCHES} and at most
 * {@link Workflow#MAX_PARALLEL_BRANCHES} branches - checked by {@link Workflow.Builder#build()},
 * not by this class itself, which stores whatever {@link WorkflowSteps#parallel} was given
 * verbatim. Every branch may itself contain further {@code ifElse}/{@code ifThen}/{@code
 * loop}/{@code parallel} steps, up to {@link Workflow#MAX_CONTROL_FLOW_NESTING_DEPTH} combined
 * levels of nesting - measured and enforced exactly like conditional/loop nesting.
 */
final class ParallelWorkflowStep extends AWorkflowStep {

    private final List<List<IWorkflowStep>> branches;

    ParallelWorkflowStep(WorkflowStepId id, List<List<IWorkflowStep>> branches) {
        this(id, branches, null);
    }

    private ParallelWorkflowStep(
            WorkflowStepId id, List<List<IWorkflowStep>> branches, IWorkflowCondition condition) {
        super(id, condition);
        this.branches = List.copyOf(Objects.requireNonNull(branches, "branches"));
    }

    /** Returns this step's declared branches, in definition order - never empty, never null. */
    List<List<IWorkflowStep>> branches() {
        return branches;
    }

    @Override
    WorkflowStepType stepType() {
        return WorkflowStepType.PARALLEL;
    }

    @Override
    Optional<WorkflowVariable<?>> outputVariable() {
        return Optional.empty();
    }

    @Override
    AWorkflowStep withCondition(IWorkflowCondition condition) {
        return new ParallelWorkflowStep(id(), branches, condition);
    }

    @Override
    StepRunOutcome run(IWorkflowVariables variables) {
        throw new IllegalStateException(
                "a parallel step is executed by WorkflowEngine's own bounded-concurrency logic,"
                        + " never through AWorkflowStep#run");
    }
}
