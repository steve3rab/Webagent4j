package io.webagent4j.workflow;

import static org.assertj.core.api.Assertions.assertThat;

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
 * later branch's already-computed work once an earlier one fails, secret redaction inside a branch,
 * and the {@code flatten(tree) == result.steps()} invariant. See {@code docs/workflow.md#parallel}.
 *
 * <p>Since a Workflow {@code ACTION} step is never permitted inside a {@code PARALLEL} branch (see
 * {@link WorkflowParallelActionSafetyTest}), every branch here is built from a caller-supplied
 * {@link IWorkflowCondition} (a trusted extension point that remains allowed - see {@code
 * docs/workflow.md#parallel}) driving an {@code ifThen}, with a deterministic {@code ASSIGN} as the
 * body: the condition's own {@code evaluate()} can sleep, record execution order, count
 * invocations, or throw to simulate a branch failure, exactly like the former test-only ACTION
 * fixtures did, without depending on the now-forbidden step type. See {@link
 * WorkflowParallelInterruptionTest} for the caller-interruption-during-join matrix (P1-1).
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
     * A branch step whose condition sleeps {@code delay}, records its own execution, and either
     * authorizes an {@code ASSIGN} that publishes {@code output} (on success) or throws (on
     * failure) - the allowed-step-type equivalent of the former test-only parallel-safe ACTION
     * fixture.
     */
    private static IWorkflowStep timedStep(
            String id,
            Duration delay,
            boolean succeed,
            List<String> executionOrder,
            WorkflowVariable<String> output) {
        IWorkflowCondition condition =
                new IWorkflowCondition() {
                    @Override
                    public boolean evaluate(IWorkflowVariables variables) {
                        sleep(delay);
                        executionOrder.add(id);
                        if (!succeed) {
                            throw new RuntimeException(id + " failed");
                        }
                        return true;
                    }

                    @Override
                    public String describe() {
                        return id;
                    }

                    @Override
                    public Set<WorkflowVariable<?>> referencedVariables() {
                        return Set.of();
                    }
                };
        List<IWorkflowStep> thenSteps =
                output == null
                        ? List.of(
                                WorkflowSteps.assign(
                                        id + "-assign",
                                        WorkflowVariable.publicValue(id + "NoopVar", String.class),
                                        "x"))
                        : List.of(WorkflowSteps.assign(id + "-assign", output, id + "-value"));
        return WorkflowSteps.ifThen(id, condition, thenSteps);
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
                                                        timedStep(
                                                                "a",
                                                                Duration.ZERO,
                                                                true,
                                                                order,
                                                                output("outA"))),
                                                List.of(
                                                        timedStep(
                                                                "b",
                                                                Duration.ZERO,
                                                                true,
                                                                order,
                                                                output("outB"))))))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isTrue();
        assertThat(result.steps())
                .extracting(r -> r.stepId().value())
                .containsExactly("par", "par@0", "a@0", "a-assign@0", "par@1", "b@1", "b-assign@1");
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
                timedStep("keep0", Duration.ofMillis(20), true, order, output("out0"));
        IWorkflowStep branch1Fails = timedStep("fail1", Duration.ofMillis(300), false, order, null);
        IWorkflowCondition fast2Condition =
                new IWorkflowCondition() {
                    @Override
                    public boolean evaluate(IWorkflowVariables variables) {
                        order.add("fast2");
                        branch2Executions.incrementAndGet();
                        return true;
                    }

                    @Override
                    public String describe() {
                        return "fast2";
                    }

                    @Override
                    public Set<WorkflowVariable<?>> referencedVariables() {
                        return Set.of();
                    }
                };
        IWorkflowStep branch2Fast =
                WorkflowSteps.ifThen(
                        "fast2",
                        fast2Condition,
                        List.of(
                                WorkflowSteps.assign(
                                        "fast2-assign", output("out2"), "fast2-value")));

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
                                                        timedStep(
                                                                "a",
                                                                Duration.ZERO,
                                                                true,
                                                                order,
                                                                output("outA"))),
                                                List.of(
                                                        timedStep(
                                                                "b",
                                                                Duration.ofMillis(10),
                                                                false,
                                                                order,
                                                                null)),
                                                List.of(
                                                        timedStep(
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
                                        List.of(countingStep("a", executions)),
                                        List.of(countingStep("b", executions))))
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
                                                List.of(countingStep("a", executions)),
                                                List.of(countingStep("b", executions)))))
                        .build();

        Thread.currentThread().interrupt();
        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.PARALLEL_STEP_INTERRUPTED);
        assertThat(executions).hasValue(0);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    private static IWorkflowStep countingStep(String id, AtomicInteger executions) {
        IWorkflowCondition condition =
                new IWorkflowCondition() {
                    @Override
                    public boolean evaluate(IWorkflowVariables variables) {
                        executions.incrementAndGet();
                        return true;
                    }

                    @Override
                    public String describe() {
                        return id;
                    }

                    @Override
                    public Set<WorkflowVariable<?>> referencedVariables() {
                        return Set.of();
                    }
                };
        return WorkflowSteps.ifThen(
                id,
                condition,
                List.of(
                        WorkflowSteps.assign(
                                id + "-assign",
                                WorkflowVariable.publicValue(id + "Var", String.class),
                                "v")));
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
                                                                        timedStep(
                                                                                "body",
                                                                                Duration.ZERO,
                                                                                true,
                                                                                order,
                                                                                output(
                                                                                        "bodyOut"))))),
                                                List.of(
                                                        timedStep(
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

    // --- PAR-ENG-007: a secret input is correctly redacted inside a failing branch's own -----
    // --- message. Cross-branch secret *discovery* propagation (a branch minting a brand-new --
    // --- secret at runtime and a sibling's failure message being retroactively masked) is no --
    // --- longer constructible through the public API now that ACTION - the only step type ----
    // --- able to publish a secret value - is forbidden inside a PARALLEL branch (see P1-2); --
    // --- the re-redaction/secret-merge machinery itself is still exercised by ---------------
    // --- WorkflowParallelInterruptionTest for the caller-interruption path. -----------------

    @Test
    void parEng007SecretInputIsRedactedInsideAFailingBranch() {
        WorkflowVariable<String> secretInput = WorkflowVariable.secret("secretIn");
        String secretValue = "sekrit-token-value";
        List<String> order = new CopyOnWriteArrayList<>();

        IWorkflowCondition failsEmbeddingSecret =
                new IWorkflowCondition() {
                    @Override
                    public boolean evaluate(IWorkflowVariables variables) {
                        order.add("leak");
                        throw new RuntimeException("boom");
                    }

                    @Override
                    public String describe() {
                        return "unexpected page text: " + secretValue;
                    }

                    @Override
                    public Set<WorkflowVariable<?>> referencedVariables() {
                        return Set.of();
                    }
                };
        IWorkflowStep branch0 = timedStep("keep0", Duration.ZERO, true, order, output("out0"));
        IWorkflowStep branch1Fails =
                WorkflowSteps.ifThen(
                        "leak",
                        failsEmbeddingSecret,
                        List.of(
                                WorkflowSteps.assign(
                                        "leak-assign",
                                        WorkflowVariable.publicValue("leakVar", String.class),
                                        "x")));

        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(secretInput)
                        .step(
                                WorkflowSteps.parallel(
                                        "par", List.of(List.of(branch0), List.of(branch1Fails))))
                        .build();

        WorkflowResult result =
                engine.execute(
                        workflow, WorkflowInputs.builder().put(secretInput, secretValue).build());

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
