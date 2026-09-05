package io.webagent4j.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionFailureType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Deterministic Bounded Workflow Parallelism engine invariant matrix: branch-definition-order
 * joining regardless of real completion order, fail-closed cancellation of siblings, discarding a
 * later branch's already-computed work once an earlier one fails, secret propagation across
 * branches, and the {@code flatten(tree) == result.steps()} invariant. See {@code
 * docs/workflow.md#parallel}.
 */
class WorkflowParallelEngineTest {

    private final WorkflowEngine engine = new WorkflowEngine();

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    private static WorkflowVariable<String> output(String name) {
        return WorkflowVariable.publicValue(name, String.class);
    }

    /**
     * A parallel-safe action that sleeps {@code delay}, then records its own execution and result.
     */
    private static IWorkflowStep timedAction(
            String id,
            Duration delay,
            boolean succeed,
            List<String> executionOrder,
            WorkflowVariable<String> output) {
        ParallelSafeActionFactory<String> factory =
                new ParallelSafeActionFactory<>(
                        variables -> {
                            sleep(delay);
                            executionOrder.add(id);
                            if (succeed) {
                                return new FakePreparedAction<>(
                                        ActionResults.success(id + "-value"), new AtomicInteger());
                            }
                            return new FakePreparedAction<>(
                                    ActionResults.failure(
                                            ActionFailureType.TARGET_NOT_FOUND, id + " not found"),
                                    new AtomicInteger());
                        });
        return output == null
                ? WorkflowSteps.action(id, factory)
                : WorkflowSteps.action(id, factory, output);
    }

    private static void sleep(Duration duration) {
        if (duration.isZero()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- PAR-ENG-001: two branches both succeed, joined in definition order -----------------

    @Test
    void parEng001TwoSuccessfulBranchesJoinInDefinitionOrder() {
        List<String> order = new CopyOnWriteArrayList<>();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(
                                                List.of(
                                                        timedAction(
                                                                "a",
                                                                Duration.ZERO,
                                                                true,
                                                                order,
                                                                output("outA"))),
                                                List.of(
                                                        timedAction(
                                                                "b",
                                                                Duration.ZERO,
                                                                true,
                                                                order,
                                                                output("outB"))))))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isTrue();
        assertThat(result.steps()).hasSize(5); // PARALLEL + BRANCH(0) + a + BRANCH(1) + b
        assertThat(result.steps().get(0).stepType()).isEqualTo(WorkflowStepType.PARALLEL);
        assertThat(result.steps().get(1).stepType()).isEqualTo(WorkflowStepType.PARALLEL_BRANCH);
        assertThat(result.steps().get(1).stepId().value()).isEqualTo("par@0");
        assertThat(result.steps().get(2).stepId().value()).isEqualTo("a@0");
        assertThat(result.steps().get(3).stepType()).isEqualTo(WorkflowStepType.PARALLEL_BRANCH);
        assertThat(result.steps().get(3).stepId().value()).isEqualTo("par@1");
        assertThat(result.steps().get(4).stepId().value()).isEqualTo("b@1");
        assertThat(result.output(output("outA"))).contains("a-value");
        assertThat(result.output(output("outB"))).contains("b-value");
    }

    // --- PAR-ENG-002: a faster, later-declared branch's real success is discarded when an ---
    // --- earlier-declared, slower branch fails - proving definition order, not completion ---
    // --- order, decides what survives (inverted delays). ------------------------------------

    @Test
    void parEng002LaterFasterBranchIsDiscardedWhenEarlierSlowerBranchFails() {
        List<String> order = new CopyOnWriteArrayList<>();
        AtomicInteger branch2Executions = new AtomicInteger();
        IWorkflowStep branch0 =
                timedAction("keep0", Duration.ofMillis(20), true, order, output("out0"));
        IWorkflowStep branch1Fails =
                timedAction("fail1", Duration.ofMillis(300), false, order, null);
        IWorkflowStep branch2Fast =
                WorkflowSteps.action(
                        "fast2",
                        new ParallelSafeActionFactory<String>(
                                variables -> {
                                    order.add("fast2");
                                    branch2Executions.incrementAndGet();
                                    return new FakePreparedAction<>(
                                            ActionResults.success("fast2-value"),
                                            new AtomicInteger());
                                }),
                        output("out2"));

        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(
                                                List.of(branch0),
                                                List.of(branch1Fails),
                                                List.of(branch2Fast))))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        // Branch 2 (index 2, no delay) genuinely ran to completion - proven by the counter and by
        // its own id appearing in the real execution-order log - well before branch 1 (index 1,
        // 300ms delay) ever failed.
        assertThat(branch2Executions).hasValue(1);
        assertThat(order).contains("fast2");

        assertThat(result.completed()).isFalse();
        assertThat(result.failure()).isPresent();
        assertThat(result.failure().orElseThrow().stepId().orElseThrow().value())
                .isEqualTo("fail1@1");

        // Branch 0 (kept: index 0 < failedIndex 1) shows its genuine SUCCEEDED outcome.
        WorkflowStepResult branch0Wrapper =
                result.steps().stream()
                        .filter(s -> s.stepId().value().equals("par@0"))
                        .findFirst()
                        .orElseThrow();
        assertThat(branch0Wrapper.status()).isEqualTo(WorkflowStepStatus.SUCCEEDED);
        assertThat(result.output(output("out0"))).contains("keep0-value");

        // Branch 1 (the reported failure) shows its genuine FAILED outcome.
        WorkflowStepResult branch1Wrapper =
                result.steps().stream()
                        .filter(s -> s.stepId().value().equals("par@1"))
                        .findFirst()
                        .orElseThrow();
        assertThat(branch1Wrapper.status()).isEqualTo(WorkflowStepStatus.SUCCEEDED);
        WorkflowStepResult fail1Result =
                result.steps().stream()
                        .filter(s -> s.stepId().value().equals("fail1@1"))
                        .findFirst()
                        .orElseThrow();
        assertThat(fail1Result.status()).isEqualTo(WorkflowStepStatus.FAILED);

        // Branch 2 (index 2 > failedIndex 1) is represented as NOT_RUN with no children, even
        // though it genuinely, successfully completed in the background - its real work is
        // discarded, and its own step never appears in the flat result at all.
        WorkflowStepResult branch2Wrapper =
                result.steps().stream()
                        .filter(s -> s.stepId().value().equals("par@2"))
                        .findFirst()
                        .orElseThrow();
        assertThat(branch2Wrapper.status()).isEqualTo(WorkflowStepStatus.NOT_RUN);
        assertThat(result.steps().stream().noneMatch(s -> s.stepId().value().equals("fast2@2")))
                .isTrue();
        assertThat(result.output(output("out2"))).isEmpty();
    }

    // --- PAR-ENG-003: flatten(tree) == result.steps() holds for a mixed-outcome parallel step -

    @Test
    void parEng003FlattenInvariantHoldsForParallel() {
        List<String> order = new CopyOnWriteArrayList<>();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(
                                                List.of(
                                                        timedAction(
                                                                "a",
                                                                Duration.ZERO,
                                                                true,
                                                                order,
                                                                output("outA"))),
                                                List.of(
                                                        timedAction(
                                                                "b",
                                                                Duration.ofMillis(10),
                                                                false,
                                                                order,
                                                                null)),
                                                List.of(
                                                        timedAction(
                                                                "c",
                                                                Duration.ZERO,
                                                                true,
                                                                order,
                                                                output("outC"))))))
                        .build();

        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());

        assertThat(flatten(execution.tree())).isEqualTo(execution.result().steps());
    }

    // --- PAR-ENG-004: a false guard skips the whole step - zero branches launched -----------

    @Test
    void parEng004FalseGuardSkipsAllBranches() {
        AtomicInteger executions = new AtomicInteger();
        IWorkflowStep guardedParallel =
                WorkflowSteps.parallel(
                                "par",
                                List.of(
                                        List.of(
                                                WorkflowSteps.action(
                                                        "a",
                                                        new ParallelSafeActionFactory<String>(
                                                                variables -> {
                                                                    executions.incrementAndGet();
                                                                    return new FakePreparedAction<>(
                                                                            ActionResults.success(
                                                                                    "v"),
                                                                            new AtomicInteger());
                                                                }))),
                                        List.of(
                                                WorkflowSteps.action(
                                                        "b",
                                                        new ParallelSafeActionFactory<String>(
                                                                variables -> {
                                                                    executions.incrementAndGet();
                                                                    return new FakePreparedAction<>(
                                                                            ActionResults.success(
                                                                                    "v"),
                                                                            new AtomicInteger());
                                                                })))))
                        .when(falseCondition());
        Workflow workflow = Workflow.builder("wf").step(guardedParallel).build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isTrue();
        assertThat(executions).hasValue(0);
        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().get(0).status()).isEqualTo(WorkflowStepStatus.SKIPPED);
    }

    // --- PAR-ENG-005: interruption before launch fails closed and preserves the flag --------

    @Test
    void parEng005InterruptionBeforeLaunchFailsClosed() {
        AtomicInteger executions = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(
                                                List.of(
                                                        WorkflowSteps.action(
                                                                "a",
                                                                new ParallelSafeActionFactory<
                                                                        String>(
                                                                        variables -> {
                                                                            executions
                                                                                    .incrementAndGet();
                                                                            return new FakePreparedAction<>(
                                                                                    ActionResults
                                                                                            .success(
                                                                                                    "v"),
                                                                                    new AtomicInteger());
                                                                        }))),
                                                List.of(
                                                        WorkflowSteps.action(
                                                                "b",
                                                                new ParallelSafeActionFactory<
                                                                        String>(
                                                                        variables -> {
                                                                            executions
                                                                                    .incrementAndGet();
                                                                            return new FakePreparedAction<>(
                                                                                    ActionResults
                                                                                            .success(
                                                                                                    "v"),
                                                                                    new AtomicInteger());
                                                                        }))))))
                        .build();

        Thread.currentThread().interrupt();
        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.PARALLEL_STEP_INTERRUPTED);
        assertThat(executions).hasValue(0);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    // --- PAR-ENG-006: a loop nested inside a parallel branch composes qualified step IDs ----

    @Test
    void parEng006LoopNestedInsideBranchComposesQualifiedIds() {
        AtomicInteger evaluations = new AtomicInteger();
        IWorkflowCondition trueOnceThenFalse =
                new IWorkflowCondition() {
                    @Override
                    public boolean evaluate(IWorkflowVariables variables) {
                        return evaluations.getAndIncrement() < 1;
                    }

                    @Override
                    public String describe() {
                        return "onceThenFalse";
                    }

                    @Override
                    public Set<WorkflowVariable<?>> referencedVariables() {
                        return Set.of();
                    }
                };
        List<String> order = new CopyOnWriteArrayList<>();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(
                                                List.of(
                                                        WorkflowSteps.loop(
                                                                "innerLoop",
                                                                trueOnceThenFalse,
                                                                3,
                                                                List.of(
                                                                        timedAction(
                                                                                "body",
                                                                                Duration.ZERO,
                                                                                true,
                                                                                order,
                                                                                output(
                                                                                        "bodyOut"))))),
                                                List.of(
                                                        timedAction(
                                                                "b",
                                                                Duration.ZERO,
                                                                true,
                                                                order,
                                                                output("outB"))))))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isTrue();
        assertThat(result.steps().stream().anyMatch(s -> s.stepId().value().equals("body@0#0")))
                .isTrue();
    }

    // --- PAR-ENG-007: a secret discovered by an earlier-declared, kept sibling branch masks a -
    // --- later-declared, failing branch's own already-redacted failure message, even though ---
    // --- that branch's own isolated fork never saw the secret directly (see --------------------
    // --- ExecutionState#fork and joinParallelBranches's re-redaction pass). --------------------

    @Test
    void parEng007SecretFromEarlierKeptBranchMasksLaterFailingBranchMessage() {
        WorkflowVariable<String> secretOut = WorkflowVariable.secret("secretOut");
        String secretValue = "sekrit-token-value";

        IWorkflowStep branch0DiscoversSecret =
                WorkflowSteps.action(
                        "discover",
                        new ParallelSafeActionFactory<String>(
                                variables ->
                                        new FakePreparedAction<>(
                                                ActionResults.success(secretValue),
                                                new AtomicInteger())),
                        secretOut);
        // branch1's own message independently embeds the exact same underlying text - simulating
        // two concurrent, isolated branches each observing the same sensitive value from their own
        // vantage point - without branch1 ever reading branch0's variable (isolation is preserved;
        // this is deliberately a literal, not a variable read, so the test does not depend on any
        // cross-branch visibility that would itself be a bug).
        IWorkflowStep branch1FailsWithSameText =
                WorkflowSteps.action(
                        "leak",
                        new ParallelSafeActionFactory<String>(
                                variables ->
                                        new FakePreparedAction<>(
                                                ActionResults.failure(
                                                        ActionFailureType.TARGET_NOT_FOUND,
                                                        "unexpected page text: " + secretValue),
                                                new AtomicInteger())));

        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(
                                                List.of(branch0DiscoversSecret),
                                                List.of(branch1FailsWithSameText))))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isFalse();
        String message = result.failure().orElseThrow().safeMessage();
        assertThat(message).doesNotContain(secretValue);
        assertThat(message).contains("***");
    }

    private static IWorkflowCondition falseCondition() {
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

    private static List<WorkflowStepResult> flatten(WorkflowExecutionTree tree) {
        List<WorkflowStepResult> flat = new ArrayList<>();
        flattenInto(tree.nodes(), flat);
        return flat;
    }

    private static void flattenInto(
            List<WorkflowExecutionNode> nodes, List<WorkflowStepResult> flat) {
        for (WorkflowExecutionNode node : nodes) {
            flat.add(node.result());
            flattenInto(node.children(), flat);
        }
    }
}
