package io.webagent4j.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Fail-closed enforcement matrix for the P1-2 fix: a Workflow {@code ACTION} step is never
 * permitted inside a {@link WorkflowStepType#PARALLEL} branch, unconditionally - there is no
 * caller-declarable escape hatch (the former {@code IWorkflowActionFactory#isParallelSafe()} was
 * removed). See {@code docs/workflow.md#parallel} and {@code
 * WorkflowValidationCode#PARALLEL_BRANCH_UNSAFE_STEP}.
 */
class WorkflowParallelActionSafetyTest {

    private static final WorkflowVariable<String> OUT_A =
            WorkflowVariable.publicValue("outA", String.class);
    private static final WorkflowVariable<String> OUT_B =
            WorkflowVariable.publicValue("outB", String.class);

    private static IWorkflowStep anyAction(String id) {
        return WorkflowSteps.action(
                id,
                variables ->
                        new FakePreparedAction<>(ActionResults.success("v"), new AtomicInteger()));
    }

    private static IWorkflowStep publish(String id, WorkflowVariable<String> output) {
        return WorkflowSteps.assign(id, output, id + "-value");
    }

    private static IWorkflowCondition alwaysTrue() {
        return new IWorkflowCondition() {
            @Override
            public boolean evaluate(IWorkflowVariables variables) {
                return true;
            }

            @Override
            public String describe() {
                return "alwaysTrue";
            }

            @Override
            public Set<WorkflowVariable<?>> referencedVariables() {
                return Set.of();
            }
        };
    }

    private static void assertRejectedAsUnsafe(Workflow.Builder builder) {
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
        assertThat(builder.validate().diagnostics())
                .anySatisfy(
                        d ->
                                assertThat(d.code())
                                        .isEqualTo(
                                                WorkflowValidationCode
                                                        .PARALLEL_BRANCH_UNSAFE_STEP));
    }

    // --- PAR-SAFE-001: an ACTION step directly inside a PARALLEL branch is rejected --------

    @Test
    void parSafe001ActionDirectlyInsideParallelRejected() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(
                                                List.of(anyAction("a")),
                                                List.of(publish("b", OUT_B)))));
        assertRejectedAsUnsafe(builder);
    }

    // --- PAR-SAFE-002: an ACTION step nested under ifElse inside a branch is rejected -------

    @Test
    void parSafe002ActionNestedUnderIfElseRejected() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(
                                                List.of(
                                                        WorkflowSteps.ifElse(
                                                                "inner",
                                                                alwaysTrue(),
                                                                List.of(anyAction("a")),
                                                                List.of(publish("aElse", OUT_A)))),
                                                List.of(publish("b", OUT_B)))));
        assertRejectedAsUnsafe(builder);
    }

    // --- PAR-SAFE-003: an ACTION step nested under ifThen inside a branch is rejected -------

    @Test
    void parSafe003ActionNestedUnderIfThenRejected() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(
                                                List.of(
                                                        WorkflowSteps.ifThen(
                                                                "inner",
                                                                alwaysTrue(),
                                                                List.of(anyAction("a")))),
                                                List.of(publish("b", OUT_B)))));
        assertRejectedAsUnsafe(builder);
    }

    // --- PAR-SAFE-004: an ACTION step nested inside a loop inside a branch is rejected ------

    @Test
    void parSafe004ActionNestedInsideLoopRejected() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(
                                                List.of(
                                                        WorkflowSteps.loop(
                                                                "innerLoop",
                                                                alwaysFalse(),
                                                                3,
                                                                List.of(anyAction("a")))),
                                                List.of(publish("b", OUT_B)))));
        assertRejectedAsUnsafe(builder);
    }

    // --- PAR-SAFE-005: an ACTION step nested inside a nested PARALLEL is rejected -----------

    @Test
    void parSafe005ActionNestedInsideNestedParallelRejected() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.parallel(
                                        "outer",
                                        List.of(
                                                List.of(
                                                        WorkflowSteps.parallel(
                                                                "inner",
                                                                List.of(
                                                                        List.of(anyAction("a")),
                                                                        List.of(
                                                                                publish(
                                                                                        "innerB",
                                                                                        OUT_A))))),
                                                List.of(publish("b", OUT_B)))));
        assertRejectedAsUnsafe(builder);
    }

    // --- PAR-SAFE-006: an action factory that would have returned true under the old --------
    // --- isParallelSafe() contract is still rejected - that API no longer exists -----------

    @Test
    void parSafe006FormerlyDeclaredSafeFactoryStillRejected() {
        // No isParallelSafe() override is possible any more - IWorkflowActionFactory has only
        // prepare(). Any ACTION factory, however it would have self-declared under the removed
        // contract, is unconditionally rejected inside a PARALLEL branch.
        IWorkflowActionFactory<String> formerlySafeFactory =
                variables ->
                        new FakePreparedAction<>(ActionResults.success("v"), new AtomicInteger());
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(
                                                List.of(
                                                        WorkflowSteps.action(
                                                                "a", formerlySafeFactory)),
                                                List.of(publish("b", OUT_B)))));
        assertRejectedAsUnsafe(builder);
    }

    // --- PAR-SAFE-007: the same ACTION step outside PARALLEL is unaffected -----------------

    @Test
    void parSafe007SameActionOutsideParallelUnaffected() {
        Workflow workflow = Workflow.builder("wf").step(anyAction("a")).build();
        assertThat(workflow.steps()).hasSize(1);
    }

    // --- PAR-SAFE-008: an allowed non-ACTION branch (ASSIGN) is accepted -------------------

    @Test
    void parSafe008AllowedNonActionBranchAccepted() {
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(
                                                List.of(publish("a", OUT_A)),
                                                List.of(publish("b", OUT_B)))))
                        .build();
        assertThat(workflow.steps()).hasSize(1);
    }

    // --- PAR-SAFE-009: builder.validate() returns the deterministic diagnostic code --------

    @Test
    void parSafe009ValidateReturnsDeterministicDiagnosticCode() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(
                                                List.of(anyAction("a")),
                                                List.of(publish("b", OUT_B)))));
        WorkflowValidationReport first = builder.validate();
        WorkflowValidationReport second = builder.validate();
        assertThat(first.valid()).isFalse();
        assertThat(first.diagnostics())
                .extracting(WorkflowValidationDiagnostic::code)
                .containsExactlyElementsOf(
                        second.diagnostics().stream()
                                .map(WorkflowValidationDiagnostic::code)
                                .toList());
        assertThat(first.diagnostics())
                .anySatisfy(
                        d ->
                                assertThat(d.code())
                                        .isEqualTo(
                                                WorkflowValidationCode
                                                        .PARALLEL_BRANCH_UNSAFE_STEP));
    }

    // --- PAR-SAFE-010: build() fails closed on the exact same unsafe structure -------------

    @Test
    void parSafe010BuildFailsClosedOnSameUnsafeStructure() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(
                                                List.of(anyAction("a")),
                                                List.of(publish("b", OUT_B)))));
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    private static IWorkflowCondition alwaysFalse() {
        return new IWorkflowCondition() {
            @Override
            public boolean evaluate(IWorkflowVariables variables) {
                return false;
            }

            @Override
            public String describe() {
                return "alwaysFalse";
            }

            @Override
            public Set<WorkflowVariable<?>> referencedVariables() {
                return Set.of();
            }
        };
    }
}
