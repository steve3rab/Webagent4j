package io.webagent4j.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Build-time validation for {@code ifElse}/{@code ifThen} steps - see {@link Workflow.Builder}. */
class WorkflowBranchingBuilderTest {

    private static final WorkflowVariable<Boolean> FLAG =
            WorkflowVariable.publicValue("flag", Boolean.class);
    private static final WorkflowVariable<String> PRODUCED =
            WorkflowVariable.publicValue("produced", String.class);

    private static IWorkflowStep action(String id, WorkflowVariable<String> output) {
        return WorkflowSteps.action(
                id,
                variables ->
                        new FakePreparedAction<>(ActionResults.success("v"), new AtomicInteger()),
                output);
    }

    @Test
    void emptyThenBranchRejected() {
        assertThatThrownBy(
                        () ->
                                WorkflowSteps.ifElse(
                                        "branch",
                                        WorkflowConditions.isTrue(FLAG),
                                        List.of(),
                                        List.of(WorkflowSteps.assign("e", PRODUCED, "x"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyElseBranchRejected() {
        assertThatThrownBy(
                        () ->
                                WorkflowSteps.ifElse(
                                        "branch",
                                        WorkflowConditions.isTrue(FLAG),
                                        List.of(WorkflowSteps.assign("t", PRODUCED, "x")),
                                        List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicateStepIdBetweenBranchesRejected() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        WorkflowConditions.isTrue(FLAG),
                                        List.of(WorkflowSteps.assign("dup", PRODUCED, "then")),
                                        List.of(WorkflowSteps.assign("dup", PRODUCED, "else"))));

        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicateStepIdBetweenNestedBranchAndTopLevelRejected() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(WorkflowSteps.assign("dup", PRODUCED, "top"))
                        .step(
                                WorkflowSteps.ifThen(
                                        "branch",
                                        WorkflowConditions.isTrue(FLAG),
                                        List.of(WorkflowSteps.assign("dup", PRODUCED, "then"))));

        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void branchConditionReferencingUndeclaredVariableRejected() {
        WorkflowVariable<Boolean> undeclared =
                WorkflowVariable.publicValue("undeclared", Boolean.class);
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.ifThen(
                                        "branch",
                                        WorkflowConditions.isTrue(undeclared),
                                        List.of(WorkflowSteps.assign("t", PRODUCED, "x"))));

        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eitherBranchsOutputIsAvailableToStepsAfterTheConditional() {
        // Only one branch ever runs, but a later step may reference either branch's declared
        // output - exactly like a single guarded step's output is already treated as available
        // regardless of whether its guard turns out true at runtime.
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        WorkflowConditions.isTrue(FLAG),
                                        List.of(action("then", PRODUCED)),
                                        List.of(action("else", PRODUCED))))
                        .step(
                                WorkflowSteps.action(
                                        "after",
                                        v -> {
                                            v.require(PRODUCED);
                                            return new FakePreparedAction<>(
                                                    ActionResults.success("ok"),
                                                    new AtomicInteger());
                                        }))
                        .build();

        assertThat(workflow).isNotNull();
    }

    @Test
    void conflictingOutputTypesBetweenBranchAndOuterScopeRejected() {
        WorkflowVariable<String> outerProduced =
                WorkflowVariable.publicValue("produced", String.class);
        WorkflowVariable<Boolean> conflictingProduced =
                WorkflowVariable.publicValue("produced", Boolean.class);
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(WorkflowSteps.assign("seed", outerProduced, "x"))
                        .step(
                                WorkflowSteps.ifThen(
                                        "branch",
                                        WorkflowConditions.isTrue(FLAG),
                                        List.of(
                                                WorkflowSteps.assign(
                                                        "then", conflictingProduced, true))));

        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void whenGuardIsNotSupportedOnAConditionalStep() {
        IWorkflowStep conditional =
                WorkflowSteps.ifThen(
                        "branch",
                        WorkflowConditions.isTrue(FLAG),
                        List.of(WorkflowSteps.assign("t", PRODUCED, "x")));

        assertThatThrownBy(() -> conditional.when(WorkflowConditions.isTrue(FLAG)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
