package io.webagent4j.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * P1-1 fix matrix: the calling thread's own interruption while joining an already-launched {@link
 * WorkflowStepType#PARALLEL} step's branches is a bounded, terminal signal - never an unbounded
 * wait, never a silently-swallowed no-op - see {@code
 * WorkflowEngine.Session#runBranchesConcurrently} and {@code docs/workflow.md#parallel}.
 *
 * <p>Every race here is driven by a {@link CountDownLatch}, never a bare {@code sleep} used as the
 * primary proof: a branch under test blocks on a latch this test controls explicitly, and a bounded
 * {@link Thread#join(long)} - generous relative to {@code
 * WorkflowEngine#PARALLEL_INTERRUPT_SHUTDOWN_GRACE_SECONDS}'s own internal bound - is the proof
 * that {@link WorkflowEngine#execute} actually returns rather than hanging.
 */
class WorkflowParallelInterruptionTest {

    private static final Duration JOIN_BOUND = Duration.ofSeconds(15);

    private final WorkflowEngine engine = new WorkflowEngine();

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    private static IWorkflowCondition condition(
            String id, java.util.function.BooleanSupplier body) {
        return new IWorkflowCondition() {
            @Override
            public boolean evaluate(IWorkflowVariables variables) {
                return body.getAsBoolean();
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
    }

    private static IWorkflowStep publishingBranch(
            String id, java.util.function.BooleanSupplier gate, WorkflowVariable<String> output) {
        return WorkflowSteps.ifThen(
                id,
                condition(id, gate),
                List.of(WorkflowSteps.assign(id + "-assign", output, "v")));
    }

    private static Thread runInBackground(
            Workflow workflow, WorkflowInputs inputs, AtomicReference<WorkflowResult> resultBox) {
        WorkflowEngine engine = new WorkflowEngine();
        Thread thread = new Thread(() -> resultBox.set(engine.execute(workflow, inputs)));
        thread.setDaemon(true);
        return thread;
    }

    // --- PAR-INT-001: caller pre-interrupted before launch - zero branches start -------------

    @Test
    void parInt001CallerPreInterruptedBeforeLaunchStartsZeroBranches() {
        AtomicInteger executions = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(
                                                List.of(
                                                        publishingBranch(
                                                                "a",
                                                                () -> {
                                                                    executions.incrementAndGet();
                                                                    return true;
                                                                },
                                                                WorkflowVariable.publicValue(
                                                                        "outA", String.class))),
                                                List.of(
                                                        publishingBranch(
                                                                "b",
                                                                () -> {
                                                                    executions.incrementAndGet();
                                                                    return true;
                                                                },
                                                                WorkflowVariable.publicValue(
                                                                        "outB", String.class))))))
                        .build();

        Thread.currentThread().interrupt();
        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.PARALLEL_STEP_INTERRUPTED);
        assertThat(executions).hasValue(0);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    // --- PAR-INT-002: caller interrupted while every branch is blocked at a deterministic ----
    // --- latch - all active branches are cancelled, execute() returns in bounded time, the ---
    // --- interrupt flag is preserved, and nothing is merged. ---------------------------------

    @Test
    void parInt002CallerInterruptedWhileAllBranchesBlockedReturnsBoundedWithNoMerge()
            throws InterruptedException {
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger aInvocations = new AtomicInteger();
        AtomicInteger bInvocations = new AtomicInteger();

        IWorkflowStep branchA =
                publishingBranch(
                        "a",
                        () -> {
                            aInvocations.incrementAndGet();
                            bothStarted.countDown();
                            return awaitCooperatively(release);
                        },
                        WorkflowVariable.publicValue("outA", String.class));
        IWorkflowStep branchB =
                publishingBranch(
                        "b",
                        () -> {
                            bInvocations.incrementAndGet();
                            bothStarted.countDown();
                            return awaitCooperatively(release);
                        },
                        WorkflowVariable.publicValue("outB", String.class));
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.parallel(
                                        "par", List.of(List.of(branchA), List.of(branchB))))
                        .build();

        AtomicReference<WorkflowResult> resultBox = new AtomicReference<>();
        Thread executing = runInBackground(workflow, WorkflowInputs.empty(), resultBox);
        executing.start();
        bothStarted.await();
        executing.interrupt();

        executing.join(JOIN_BOUND.toMillis());
        assertThat(executing.isAlive())
                .as("execute() must return within the bounded grace period")
                .isFalse();
        assertThat(executing.isInterrupted()).isTrue();

        release.countDown(); // let the (now-cancelled, cooperative) branch threads unwind promptly

        WorkflowResult result = resultBox.get();
        assertThat(result).isNotNull();
        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.PARALLEL_STEP_INTERRUPTED);
        assertThat(result.output(WorkflowVariable.publicValue("outA", String.class))).isEmpty();
        assertThat(result.output(WorkflowVariable.publicValue("outB", String.class))).isEmpty();
        assertThat(aInvocations).hasValue(1);
        assertThat(bInvocations).hasValue(1);
    }

    /** Awaits {@code latch} cooperatively: restores the flag and stops waiting once interrupted. */
    private static boolean awaitCooperatively(CountDownLatch latch) {
        try {
            latch.await();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    // --- PAR-INT-003: one branch already completed, one still blocked, caller interrupted ----
    // --- - regardless of whether the completed branch's own outcome had already been --------
    // --- received by the join loop (an inherent, benign scheduling race - see below), its ----
    // --- output is never merged once this step's own outcome is the interruption itself, and -
    // --- the still-blocked branch is always NOT_RUN. ------------------------------------------

    @Test
    void parInt003OneCompletedOneBlockedBranchNeitherContributesOnInterruption()
            throws InterruptedException {
        CountDownLatch fastStarted = new CountDownLatch(1);
        CountDownLatch blockedStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger fastInvocations = new AtomicInteger();

        IWorkflowStep fastBranch =
                publishingBranch(
                        "fast",
                        () -> {
                            fastInvocations.incrementAndGet();
                            fastStarted.countDown();
                            return true;
                        },
                        WorkflowVariable.publicValue("outFast", String.class));
        IWorkflowStep blockedBranch =
                publishingBranch(
                        "blocked",
                        () -> {
                            blockedStarted.countDown();
                            return awaitCooperatively(release);
                        },
                        WorkflowVariable.publicValue("outBlocked", String.class));
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(List.of(fastBranch), List.of(blockedBranch))))
                        .build();

        AtomicReference<WorkflowResult> resultBox = new AtomicReference<>();
        Thread executing = runInBackground(workflow, WorkflowInputs.empty(), resultBox);
        executing.start();
        // Both branches must have genuinely started - fastBranch's own countdown proves its task
        // was actually scheduled and ran (Future.cancel(true) on a not-yet-started task simply
        // prevents it from ever running at all, which would defeat this test's own "already
        // completed" premise), and blockedBranch's own countdown proves it is now parked. Only the
        // producer/consumer gap between fastBranch's task completing and the actively-spinning
        // join loop actually dequeuing it remains a residual, benign race - which is exactly why
        // this test asserts the invariant that holds regardless of that race (no output ever
        // merged), rather than which exact shape fastBranch's own recorded entry takes.
        fastStarted.await();
        blockedStarted.await();
        executing.interrupt();

        executing.join(JOIN_BOUND.toMillis());
        assertThat(executing.isAlive()).isFalse();
        release.countDown();

        WorkflowResult result = resultBox.get();
        assertThat(result).isNotNull();
        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.PARALLEL_STEP_INTERRUPTED);
        assertThat(fastInvocations).hasValue(1);
        assertThat(result.output(WorkflowVariable.publicValue("outFast", String.class))).isEmpty();
        assertThat(result.output(WorkflowVariable.publicValue("outBlocked", String.class)))
                .isEmpty();
        WorkflowStepResult blockedWrapper =
                result.steps().stream()
                        .filter(s -> s.stepId().value().equals("par@1"))
                        .findFirst()
                        .orElseThrow();
        assertThat(blockedWrapper.status()).isEqualTo(WorkflowStepStatus.NOT_RUN);
    }

    // --- PAR-INT-004: a branch that deliberately ignores its own interruption still lets -----
    // --- execute() return within the bounded grace period - its eventual result is never -----
    // --- merged. ------------------------------------------------------------------------------

    @Test
    void parInt004HostileBranchIgnoringInterruptionStillReturnsBounded()
            throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean released = new AtomicBoolean(false);
        AtomicInteger invocations = new AtomicInteger();

        IWorkflowStep hostileBranch =
                publishingBranch(
                        "hostile",
                        () -> {
                            invocations.incrementAndGet();
                            started.countDown();
                            while (!released.get()) {
                                try {
                                    Thread.sleep(5);
                                } catch (InterruptedException e) {
                                    // Deliberately swallowed: this branch ignores interruption
                                    // entirely, never restoring the flag or breaking the loop.
                                }
                            }
                            return true;
                        },
                        WorkflowVariable.publicValue("outHostile", String.class));
        IWorkflowStep otherBranch =
                publishingBranch(
                        "other",
                        () -> {
                            while (!released.get()) {
                                try {
                                    Thread.sleep(5);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return true;
                                }
                            }
                            return true;
                        },
                        WorkflowVariable.publicValue("outOther", String.class));
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(List.of(hostileBranch), List.of(otherBranch))))
                        .build();

        AtomicReference<WorkflowResult> resultBox = new AtomicReference<>();
        Thread executing = runInBackground(workflow, WorkflowInputs.empty(), resultBox);
        executing.start();
        started.await();
        executing.interrupt();

        executing.join(JOIN_BOUND.toMillis());
        assertThat(executing.isAlive())
                .as(
                        "execute() must return in bounded time even though the hostile branch never"
                                + " honors its own interruption")
                .isFalse();

        WorkflowResult result = resultBox.get();
        assertThat(result).isNotNull();
        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.PARALLEL_STEP_INTERRUPTED);
        assertThat(result.output(WorkflowVariable.publicValue("outHostile", String.class)))
                .isEmpty();
        assertThat(invocations).hasValue(1);

        released.set(
                true); // release the still-running daemon thread so it does not outlive the test
    }

    // --- PAR-INT-005: caller interruption races with an already-failed, lowest-index branch --
    // --- - the already-decided branch failure wins, since no lower-index branch remains ------
    // --- unresolved (see WorkflowEngine.Session#isJoinIrreversiblyDecided). ------------------

    @Test
    void parInt005AlreadyDecidedLowestIndexFailureWinsOverLaterInterruption()
            throws InterruptedException {
        CountDownLatch branch1Started = new CountDownLatch(1);
        AtomicBoolean released = new AtomicBoolean(false);

        // branch1 (index 1) must already be running - not merely submitted - before branch0 is
        // allowed to fail: if branch0 failed first, cancelStrictlyAfter could cancel branch1's own
        // Future before its task ever started, which simply prevents it from running at all rather
        // than interrupting an in-flight wait - a completely different, no-longer-racing scenario
        // this test does not intend to exercise. branch1 also deliberately ignores its own
        // cancellation-interrupt (ignores it exactly like PAR-INT-004's hostile branch), so it
        // cannot resolve itself and race away this test's own interruption window.
        IWorkflowStep branch0Fails =
                WorkflowSteps.ifThen(
                        "fail0",
                        condition(
                                "fail0",
                                () -> {
                                    try {
                                        branch1Started.await();
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    throw new RuntimeException("fail0");
                                }),
                        List.of(
                                WorkflowSteps.assign(
                                        "fail0-assign",
                                        WorkflowVariable.publicValue("out0", String.class),
                                        "v")));
        IWorkflowStep branch1Blocks =
                publishingBranch(
                        "blocked1",
                        () -> {
                            branch1Started.countDown();
                            while (!released.get()) {
                                try {
                                    Thread.sleep(5);
                                } catch (InterruptedException e) {
                                    // Deliberately swallowed - see the comment above.
                                }
                            }
                            return true;
                        },
                        WorkflowVariable.publicValue("out1", String.class));
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(List.of(branch0Fails), List.of(branch1Blocks))))
                        .build();

        AtomicReference<WorkflowResult> resultBox = new AtomicReference<>();
        Thread executing = runInBackground(workflow, WorkflowInputs.empty(), resultBox);
        executing.start();
        branch1Started.await();
        // branch0's own condition only proceeds to fail after this same latch - give it a brief,
        // bounded window to actually be received (fail, and be recorded) before the caller is
        // interrupted, so the interruption arrives once the failure is already irreversible.
        Thread.sleep(200);
        executing.interrupt();

        executing.join(JOIN_BOUND.toMillis());
        assertThat(executing.isAlive()).isFalse();
        released.set(true);

        WorkflowResult result = resultBox.get();
        assertThat(result).isNotNull();
        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().stepId().orElseThrow().value())
                .isEqualTo("fail0@0");
    }

    // --- PAR-INT-006: the interrupt flag is restored after execute() returns, whether or not -
    // --- this step's own reported failure ends up being the interruption itself. ------------

    @Test
    void parInt006InterruptFlagRestoredAfterReturn() throws InterruptedException {
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        IWorkflowStep branchA =
                publishingBranch(
                        "a",
                        () -> {
                            bothStarted.countDown();
                            return awaitCooperatively(release);
                        },
                        WorkflowVariable.publicValue("outA", String.class));
        IWorkflowStep branchB =
                publishingBranch(
                        "b",
                        () -> {
                            bothStarted.countDown();
                            return awaitCooperatively(release);
                        },
                        WorkflowVariable.publicValue("outB", String.class));
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.parallel(
                                        "par", List.of(List.of(branchA), List.of(branchB))))
                        .build();

        AtomicReference<WorkflowResult> resultBox = new AtomicReference<>();
        Thread executing = runInBackground(workflow, WorkflowInputs.empty(), resultBox);
        executing.start();
        bothStarted.await();
        executing.interrupt();
        executing.join(JOIN_BOUND.toMillis());
        release.countDown();

        assertThat(executing.isAlive()).isFalse();
        assertThat(executing.isInterrupted())
                .as("the executing thread's own interrupt flag must be restored, not swallowed")
                .isTrue();
    }

    // --- PAR-INT-007: no hidden retry - a cancelled branch's own step is invoked exactly once -

    @Test
    void parInt007NoHiddenRetryAfterCancellation() throws InterruptedException {
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger aInvocations = new AtomicInteger();
        AtomicInteger bInvocations = new AtomicInteger();
        IWorkflowStep branchA =
                publishingBranch(
                        "a",
                        () -> {
                            aInvocations.incrementAndGet();
                            bothStarted.countDown();
                            return awaitCooperatively(release);
                        },
                        WorkflowVariable.publicValue("outA", String.class));
        IWorkflowStep branchB =
                publishingBranch(
                        "b",
                        () -> {
                            bInvocations.incrementAndGet();
                            bothStarted.countDown();
                            return awaitCooperatively(release);
                        },
                        WorkflowVariable.publicValue("outB", String.class));
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.parallel(
                                        "par", List.of(List.of(branchA), List.of(branchB))))
                        .build();

        AtomicReference<WorkflowResult> resultBox = new AtomicReference<>();
        Thread executing = runInBackground(workflow, WorkflowInputs.empty(), resultBox);
        executing.start();
        bothStarted.await();
        executing.interrupt();
        executing.join(JOIN_BOUND.toMillis());
        release.countDown();

        assertThat(executing.isAlive()).isFalse();
        assertThat(aInvocations).hasValue(1);
        assertThat(bInvocations).hasValue(1);
    }
}
