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
    void bothBranchesProducingTheSameCompatibleOutputIsDefinitelyAvailableAfterTheConditional() {
        // Only one branch ever runs, but since BOTH branches guarantee a compatible declaration of
        // PRODUCED, it is definitely available afterward regardless of which branch executed -
        // this is definite assignment: an intersection of what both branches guarantee, never a
        // union of what either branch merely might have produced (see VAR-006/VAR-007 below).
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

    // --- VAR-006: only one branch (ifElse) producing an output is NOT definitely available -----

    @Test
    void onlyThenBranchProducingOutputIsNotAvailableAfterTheConditionalIfElse() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        WorkflowConditions.isTrue(FLAG),
                                        List.of(WorkflowSteps.assign("then", PRODUCED, "then")),
                                        List.of(
                                                WorkflowSteps.assign(
                                                        "else",
                                                        WorkflowVariable.publicValue(
                                                                "unrelated", String.class),
                                                        "else"))))
                        .step(
                                WorkflowSteps.assign(
                                                "after",
                                                WorkflowVariable.publicValue(
                                                        "marker", Boolean.class),
                                                true)
                                        .when(WorkflowConditions.exists(PRODUCED)));

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("produced");
    }

    // --- VAR-007: an ifThen's thenSteps-only output is never available afterward --------------

    @Test
    void ifThenOutputIsNotAvailableAfterTheConditional() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                WorkflowSteps.ifThen(
                                        "branch",
                                        WorkflowConditions.isTrue(FLAG),
                                        List.of(WorkflowSteps.assign("then", PRODUCED, "then"))))
                        .step(
                                WorkflowSteps.assign(
                                                "after",
                                                WorkflowVariable.publicValue(
                                                        "marker", Boolean.class),
                                                true)
                                        .when(WorkflowConditions.exists(PRODUCED)));

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("produced");
    }

    // --- VAR-008: same-named output declared with a different type between branches -----------

    @Test
    void branchOutputTypeMismatchBetweenThenAndElseRejected() {
        WorkflowVariable<Integer> intProduced =
                WorkflowVariable.publicValue("produced", Integer.class);
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        WorkflowConditions.isTrue(FLAG),
                                        List.of(WorkflowSteps.assign("then", PRODUCED, "x")),
                                        List.of(WorkflowSteps.assign("else", intProduced, 1))));

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("produced");
    }

    // --- VAR-009: same-named output declared with a different secret status between branches --

    @Test
    void branchOutputSecretMismatchBetweenThenAndElseRejected() {
        WorkflowVariable<String> secretProduced = WorkflowVariable.secret("secretProduced");
        WorkflowVariable<String> publicProduced =
                WorkflowVariable.publicValue("secretProduced", String.class);
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        WorkflowConditions.isTrue(FLAG),
                                        List.of(
                                                WorkflowSteps.action(
                                                        "then",
                                                        v ->
                                                                new FakePreparedAction<>(
                                                                        ActionResults.success("s"),
                                                                        new AtomicInteger()),
                                                        secretProduced)),
                                        List.of(
                                                WorkflowSteps.assign(
                                                        "else", publicProduced, "p"))));

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secretProduced");
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
