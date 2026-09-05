package io.webagent4j.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Bounded Workflow Loop build-time invariants: {@link Workflow.Builder#build()}/{@link
 * Workflow.Builder#validate()} maxIterations bounds, the generalized control-flow nesting depth
 * shared with {@link ConditionalWorkflowStep}, and guard-aware definite assignment for a loop
 * body's outputs. See {@code docs/workflow.md#bounded-loops}.
 */
class WorkflowLoopBuilderTest {

    private static final WorkflowVariable<String> BODY_OUTPUT =
            WorkflowVariable.publicValue("bodyOutput", String.class);

    private static final class AlwaysTrueCondition implements IWorkflowCondition {
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
    }

    private static final class ReadsCondition implements IWorkflowCondition {
        private final WorkflowVariable<?> referenced;

        ReadsCondition(WorkflowVariable<?> referenced) {
            this.referenced = referenced;
        }

        @Override
        public boolean evaluate(IWorkflowVariables variables) {
            return variables.exists(referenced);
        }

        @Override
        public String describe() {
            return "reads(" + referenced.name() + ")";
        }

        @Override
        public Set<WorkflowVariable<?>> referencedVariables() {
            return Set.of(referenced);
        }
    }

    private static IWorkflowStep noOutputLeaf(String id) {
        return WorkflowSteps.action(
                id,
                variables ->
                        new FakePreparedAction<>(ActionResults.success("v"), new AtomicInteger()));
    }

    private static IWorkflowStep producesBodyOutput(String id) {
        return WorkflowSteps.action(
                id,
                variables ->
                        new FakePreparedAction<>(ActionResults.success("v"), new AtomicInteger()),
                BODY_OUTPUT);
    }

    // --- Validation report: invalid maxIterations is a stable diagnostic, never a throw ----

    @Test
    void validateReportsInvalidMaxIterationsWithoutThrowing() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.loop(
                                        "loop",
                                        new AlwaysTrueCondition(),
                                        0,
                                        List.of(noOutputLeaf("body"))));

        WorkflowValidationReport report = builder.validate();

        assertThat(report.valid()).isFalse();
        assertThat(report.diagnostics())
                .extracting(WorkflowValidationDiagnostic::code)
                .contains(WorkflowValidationCode.LOOP_INVALID_MAX_ITERATIONS);
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    // --- Validation report: excessive maxIterations is a stable diagnostic -----------------

    @Test
    void validateReportsMaxIterationsAboveFrameworkLimit() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.loop(
                                        "loop",
                                        new AlwaysTrueCondition(),
                                        Workflow.MAX_LOOP_ITERATIONS + 1,
                                        List.of(noOutputLeaf("body"))));

        WorkflowValidationReport report = builder.validate();

        assertThat(report.valid()).isFalse();
        assertThat(report.diagnostics())
                .extracting(WorkflowValidationDiagnostic::code)
                .contains(WorkflowValidationCode.LOOP_INVALID_MAX_ITERATIONS);
    }

    // --- Definite assignment: a loop body's output is never definite outside the loop ------

    @Test
    void loopBodyOutputIsNeverDefiniteAfterTheLoop() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.loop(
                                        "loop",
                                        new AlwaysTrueCondition(),
                                        3,
                                        List.of(producesBodyOutput("produce"))))
                        .step(
                                WorkflowSteps.ifThen(
                                        "checks-after",
                                        new ReadsCondition(BODY_OUTPUT),
                                        List.of(noOutputLeaf("after"))));

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a declared input or an earlier step's");

        WorkflowValidationReport report = builder.validate();
        assertThat(report.valid()).isFalse();
        assertThat(report.diagnostics())
                .extracting(WorkflowValidationDiagnostic::code)
                .contains(WorkflowValidationCode.OUTPUT_NOT_DEFINITELY_AVAILABLE);
        // But the output IS structurally declared - findable via a runtime WorkflowOutputs lookup
        // after execution, just never statically guaranteed to a later step's own condition.
        assertThat(report.outputs())
                .anySatisfy(
                        output -> {
                            assertThat(output.variable()).isEqualTo(BODY_OUTPUT);
                            assertThat(output.definitelyAvailable()).isFalse();
                        });
    }

    // --- Output collision: a loop body's output still collides with an existing producer ---

    @Test
    void loopBodyOutputStillCollidesWithAnExistingProducer() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(WorkflowSteps.assign("seed", BODY_OUTPUT, "seeded"))
                        .step(
                                WorkflowSteps.loop(
                                        "loop",
                                        new AlwaysTrueCondition(),
                                        3,
                                        List.of(producesBodyOutput("produce"))));

        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    // --- Nesting depth: combined CONDITIONAL+LOOP nesting shares one bound ------------------

    @Test
    void loopNestedInsideConditionalCountsTowardTheSharedDepthBound() {
        int max = Workflow.MAX_CONTROL_FLOW_NESTING_DEPTH;
        // A chain alternating ifThen/loop, max levels deep, must be accepted at exactly max.
        IWorkflowStep current = noOutputLeaf("leaf");
        for (int level = max; level >= 1; level--) {
            current =
                    level % 2 == 0
                            ? WorkflowSteps.ifThen(
                                    "cond-" + level, new AlwaysTrueCondition(), List.of(current))
                            : WorkflowSteps.loop(
                                    "loop-" + level,
                                    new AlwaysTrueCondition(),
                                    1,
                                    List.of(current));
        }
        Workflow workflow = Workflow.builder("wf").step(current).build();
        assertThat(workflow).isNotNull();
    }

    @Test
    void loopNestedInsideConditionalOneMoreThanSharedDepthIsRejected() {
        int max = Workflow.MAX_CONTROL_FLOW_NESTING_DEPTH;
        IWorkflowStep current = noOutputLeaf("leaf");
        for (int level = max + 1; level >= 1; level--) {
            current =
                    level % 2 == 0
                            ? WorkflowSteps.ifThen(
                                    "cond-" + level, new AlwaysTrueCondition(), List.of(current))
                            : WorkflowSteps.loop(
                                    "loop-" + level,
                                    new AlwaysTrueCondition(),
                                    1,
                                    List.of(current));
        }
        Workflow.Builder builder = Workflow.builder("wf").step(current);

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(StackOverflowError.class);

        WorkflowValidationReport report = builder.validate();
        assertThat(report.diagnostics())
                .extracting(WorkflowValidationDiagnostic::code)
                .contains(WorkflowValidationCode.LOOP_NESTING_DEPTH_EXCEEDED);
    }

    // --- The loop's own continuation condition must reference only outer-scope variables ----

    @Test
    void loopConditionReferencingAnUndeclaredVariableIsRejected() {
        WorkflowVariable<String> undeclared =
                WorkflowVariable.publicValue("undeclared", String.class);
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.loop(
                                        "loop",
                                        new ReadsCondition(undeclared),
                                        3,
                                        List.of(noOutputLeaf("body"))));

        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    // --- A loop's condition MAY reference a definite pre-loop variable ----------------------

    @Test
    void loopConditionReferencingADefinitePreLoopVariableIsAccepted() {
        WorkflowVariable<String> seeded = WorkflowVariable.publicValue("seeded", String.class);
        Workflow workflow =
                Workflow.builder("wf")
                        .step(WorkflowSteps.assign("seed", seeded, "v"))
                        .step(
                                WorkflowSteps.loop(
                                        "loop",
                                        new ReadsCondition(seeded),
                                        3,
                                        List.of(noOutputLeaf("body"))))
                        .build();

        assertThat(workflow).isNotNull();
    }
}
