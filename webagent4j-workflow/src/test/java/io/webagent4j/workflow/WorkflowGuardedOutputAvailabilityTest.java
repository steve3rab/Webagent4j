package io.webagent4j.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Guard-aware definite assignment (VAR-GUARD-001..007): a step output is definitely available to a
 * later step's condition (or a conditional step's own branch selector) only when the producing step
 * is structurally guaranteed to execute its production path - never merely because it might. See
 * {@code docs/workflow.md#conditions} and {@code docs/workflow.md#branching}.
 */
class WorkflowGuardedOutputAvailabilityTest {

    private static final WorkflowVariable<Boolean> FLAG =
            WorkflowVariable.publicValue("flag", Boolean.class);
    private static final WorkflowVariable<String> PRODUCED =
            WorkflowVariable.publicValue("produced", String.class);
    private static final WorkflowVariable<String> SECRET_PRODUCED =
            WorkflowVariable.secret("secretProduced");
    private static final String SECRET_SENTINEL = "WA4J_GUARD_SECRET_770311";

    private final WorkflowEngine engine = new WorkflowEngine();

    private static IWorkflowStep produces(String id, WorkflowVariable<String> output) {
        return WorkflowSteps.action(
                id,
                variables ->
                        new FakePreparedAction<>(ActionResults.success("v"), new AtomicInteger()),
                output);
    }

    private static IWorkflowStep consumesViaCondition(String id, WorkflowVariable<?> required) {
        return WorkflowSteps.assign(
                        id, WorkflowVariable.publicValue(id + "-marker", Boolean.class), true)
                .when(WorkflowConditions.exists(required));
    }

    // --- VAR-GUARD-001: guarded producer, later condition statically references it ------------

    @Test
    void varGuard001GuardedProducerReferencedByLaterConditionRejected() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(produces("producer", PRODUCED).when(WorkflowConditions.isTrue(FLAG)))
                        .step(consumesViaCondition("consumer", PRODUCED));

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("produced");
    }

    // --- VAR-GUARD-002: unguarded producer, later condition references it - control case -----

    @Test
    void varGuard002UnguardedProducerReferencedByLaterConditionAccepted() {
        Workflow workflow =
                Workflow.builder("wf")
                        .step(produces("producer", PRODUCED))
                        .step(consumesViaCondition("consumer", PRODUCED))
                        .build();

        assertThat(workflow).isNotNull();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());
        assertThat(result.completed()).isTrue();
        assertThat(result.output(PRODUCED)).contains("v");
        assertThat(result.steps().get(1).status()).isEqualTo(WorkflowStepStatus.SUCCEEDED);
    }

    // --- VAR-GUARD-003: ifElse, only THEN's producer is guarded -------------------------------

    @Test
    void varGuard003OnlyThenProducerGuardedRejected() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        WorkflowConditions.isTrue(FLAG),
                                        List.of(
                                                produces("then", PRODUCED)
                                                        .when(WorkflowConditions.isTrue(FLAG))),
                                        List.of(produces("else", PRODUCED))))
                        .step(consumesViaCondition("consumer", PRODUCED));

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("produced");
    }

    // --- VAR-GUARD-004: ifElse, both branches' producers are guarded --------------------------

    @Test
    void varGuard004BothBranchProducersGuardedRejected() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        WorkflowConditions.isTrue(FLAG),
                                        List.of(
                                                produces("then", PRODUCED)
                                                        .when(WorkflowConditions.isTrue(FLAG))),
                                        List.of(
                                                produces("else", PRODUCED)
                                                        .when(WorkflowConditions.isTrue(FLAG)))))
                        .step(consumesViaCondition("consumer", PRODUCED));

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("produced");
    }

    // --- VAR-GUARD-005: ifElse, neither branch's producer is guarded - definite assignment ----

    @Test
    void varGuard005NeitherBranchProducerGuardedAccepted() {
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        WorkflowConditions.isTrue(FLAG),
                                        List.of(produces("then", PRODUCED)),
                                        List.of(produces("else", PRODUCED))))
                        .step(consumesViaCondition("consumer", PRODUCED))
                        .build();

        assertThat(workflow).isNotNull();
    }

    // --- VAR-GUARD-006: guard true at runtime - normal publication is preserved ---------------

    @Test
    void varGuard006GuardTrueAtRuntimePublishesNormally() {
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(produces("producer", PRODUCED).when(WorkflowConditions.isTrue(FLAG)))
                        .build();
        WorkflowInputs inputs = WorkflowInputs.builder().put(FLAG, true).build();

        WorkflowResult result = engine.execute(workflow, inputs);

        assertThat(result.completed()).isTrue();
        assertThat(result.steps().get(0).status()).isEqualTo(WorkflowStepStatus.SUCCEEDED);
        assertThat(result.output(PRODUCED)).contains("v");
    }

    // --- VAR-GUARD-007: guard false at runtime - no publication, no side effect ---------------

    @Test
    void varGuard007GuardFalseAtRuntimePublishesNothing() {
        AtomicInteger executions = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                WorkflowSteps.action(
                                                "producer",
                                                variables ->
                                                        new FakePreparedAction<>(
                                                                ActionResults.success("v"),
                                                                executions),
                                                PRODUCED)
                                        .when(WorkflowConditions.isTrue(FLAG)))
                        .build();
        WorkflowInputs inputs = WorkflowInputs.builder().put(FLAG, false).build();

        WorkflowResult result = engine.execute(workflow, inputs);

        assertThat(result.completed()).isTrue();
        assertThat(executions).hasValue(0);
        assertThat(result.steps().get(0).status()).isEqualTo(WorkflowStepStatus.SKIPPED);
        assertThat(result.output(PRODUCED)).isEmpty();
    }

    // --- Structural collision: a guarded producer can never "free up" its output name --------

    @Test
    void guardedProducerFollowedByUnguardedProducerOfTheSameNameStillRejected() {
        // A guard being false at runtime is not statically provable, so a second, unconditional
        // producer of the same variable must still be rejected: at runtime the guard may evaluate
        // true, and both would then publish the same variable in the same execution.
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                produces("guarded-producer", PRODUCED)
                                        .when(WorkflowConditions.isTrue(FLAG)))
                        .step(produces("unguarded-producer", PRODUCED));

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("produced");
    }

    // --- Secret semantics: guarded secret producer --------------------------------------------

    @Test
    void guardedSecretProducerPublishesOnlyWhenGuardTrueAndNeverLeaksRaw() {
        IWorkflowStep secretProducer =
                WorkflowSteps.action(
                                "secret-producer",
                                variables ->
                                        new FakePreparedAction<>(
                                                ActionResults.success(SECRET_SENTINEL),
                                                new AtomicInteger()),
                                SECRET_PRODUCED)
                        .when(WorkflowConditions.isTrue(FLAG));
        Workflow workflow = Workflow.builder("wf").requiredInput(FLAG).step(secretProducer).build();

        WorkflowResult falseResult =
                engine.execute(workflow, WorkflowInputs.builder().put(FLAG, false).build());
        assertThat(falseResult.completed()).isTrue();
        assertThat(falseResult.output(SECRET_PRODUCED)).isEmpty();
        assertThat(falseResult.steps().get(0).status()).isEqualTo(WorkflowStepStatus.SKIPPED);
        assertThat(falseResult.toString()).doesNotContain(SECRET_SENTINEL);

        WorkflowResult trueResult =
                engine.execute(workflow, WorkflowInputs.builder().put(FLAG, true).build());
        assertThat(trueResult.completed()).isTrue();
        assertThat(trueResult.output(SECRET_PRODUCED)).contains(SECRET_SENTINEL);
        assertThat(trueResult.steps().get(0).status()).isEqualTo(WorkflowStepStatus.SUCCEEDED);
        assertThat(trueResult.toString()).doesNotContain(SECRET_SENTINEL).contains("***");
    }

    // --- Nested branching propagation: a guarded inner producer taints the outer branch -------

    @Test
    void nestedGuardedProducerPropagatesNonDefiniteToOuterBranch() {
        // IF A { IF B { THEN produces X when(C); ELSE produces X } } ELSE { produces X }
        // AFTER consume X - A's THEN branch no longer unconditionally guarantees X, since B's own
        // THEN producer is guarded, so the whole conditional cannot guarantee X either.
        IWorkflowStep innerConditional =
                WorkflowSteps.ifElse(
                        "inner",
                        WorkflowConditions.isTrue(FLAG),
                        List.of(
                                produces("inner-then", PRODUCED)
                                        .when(WorkflowConditions.isTrue(FLAG))),
                        List.of(produces("inner-else", PRODUCED)));
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                WorkflowSteps.ifElse(
                                        "outer",
                                        WorkflowConditions.isTrue(FLAG),
                                        List.of(innerConditional),
                                        List.of(produces("outer-else", PRODUCED))))
                        .step(consumesViaCondition("consumer", PRODUCED));

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("produced");
    }

    @Test
    void nestedUnguardedProducersOnEveryReachablePathAreDefinite() {
        // Same shape, but every producer on every reachable path is unguarded - X is definite.
        IWorkflowStep innerConditional =
                WorkflowSteps.ifElse(
                        "inner",
                        WorkflowConditions.isTrue(FLAG),
                        List.of(produces("inner-then", PRODUCED)),
                        List.of(produces("inner-else", PRODUCED)));
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                WorkflowSteps.ifElse(
                                        "outer",
                                        WorkflowConditions.isTrue(FLAG),
                                        List.of(innerConditional),
                                        List.of(produces("outer-else", PRODUCED))))
                        .step(consumesViaCondition("consumer", PRODUCED))
                        .build();

        assertThat(workflow).isNotNull();
    }
}
