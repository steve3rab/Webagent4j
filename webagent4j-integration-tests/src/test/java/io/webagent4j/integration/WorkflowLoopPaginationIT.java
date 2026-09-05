package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.IActionPlan;
import io.webagent4j.action.IPreparedAction;
import io.webagent4j.action.ObservationCapturePolicy;
import io.webagent4j.browser.IPage;
import io.webagent4j.common.RetryPolicy;
import io.webagent4j.dom.IElement;
import io.webagent4j.extraction.api.ExtractionRequest;
import io.webagent4j.extraction.api.ExtractionResult;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.LocatorDefinition;
import io.webagent4j.policy.PolicyDecision;
import io.webagent4j.recording.RecordedExecutionNodeV2;
import io.webagent4j.recording.RecordingId;
import io.webagent4j.recording.WorkflowRecorderV2;
import io.webagent4j.recording.WorkflowRecordingV2;
import io.webagent4j.recording.replay.IReplayOutcome;
import io.webagent4j.recording.replay.ReplayValidationFailure;
import io.webagent4j.recording.replay.ReplayValidator;
import io.webagent4j.recording.replay.ReplayedWorkflow;
import io.webagent4j.recording.replay.WorkflowReplayer;
import io.webagent4j.verification.IVerification;
import io.webagent4j.workflow.IWorkflowCondition;
import io.webagent4j.workflow.IWorkflowStep;
import io.webagent4j.workflow.IWorkflowVariables;
import io.webagent4j.workflow.Workflow;
import io.webagent4j.workflow.WorkflowBranchSelection;
import io.webagent4j.workflow.WorkflowEngine;
import io.webagent4j.workflow.WorkflowExecution;
import io.webagent4j.workflow.WorkflowExecutionNode;
import io.webagent4j.workflow.WorkflowExecutionPlan;
import io.webagent4j.workflow.WorkflowFailureType;
import io.webagent4j.workflow.WorkflowInputs;
import io.webagent4j.workflow.WorkflowPlanner;
import io.webagent4j.workflow.WorkflowResult;
import io.webagent4j.workflow.WorkflowStepResult;
import io.webagent4j.workflow.WorkflowStepStatus;
import io.webagent4j.workflow.WorkflowStepType;
import io.webagent4j.workflow.WorkflowSteps;
import io.webagent4j.workflow.WorkflowVariable;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Real-Playwright, local-fixture integration coverage for a Bounded Workflow Loop performing
 * deterministic paginated-list navigation: a 3-page fixture, a bounded loop of 5, a continuation
 * condition reading the page's own live state, and a governed "Next" click - proving through the
 * real action pipeline (never a fake {@code IPreparedAction} for the click itself) that the loop
 * clicks exactly as many times as pages remain, never more, and that Recording V2 / Deterministic
 * Replay integrate correctly with it. See {@code docs/workflow.md#bounded-loops} and {@code
 * docs/recording.md#bounded-loops}.
 */
class WorkflowLoopPaginationIT {

    private static final WorkflowVariable<IPage> PAGE =
            WorkflowVariable.publicValue("page", IPage.class);
    private static final WorkflowVariable<String> CURRENT_PAGE =
            WorkflowVariable.publicValue("currentPage", String.class);

    private final WorkflowEngine engine = new WorkflowEngine();

    private static Workflow paginationWorkflow() {
        return Workflow.builder("paginate")
                .requiredInput(PAGE)
                .step(
                        WorkflowSteps.loop(
                                "paginate-loop",
                                pageIndicatorNotAtLastPage(),
                                5,
                                List.of(clickNextStep(), readCurrentPageStep())))
                .build();
    }

    /**
     * The loop's continuation condition reads the page's own live state directly through {@code
     * PAGE} (already a required input, so definitely available before the loop) rather than a
     * separately-seeded workflow variable: {@code CURRENT_PAGE} is declared exactly once, by {@link
     * #readCurrentPageStep()} inside the loop body, which is the framework's ordinary single-writer
     * rule (see {@code docs/workflow.md#bounded-loops}) - a step before the loop and a step inside
     * the body can never legally publish the same variable, since both would run on the same
     * execution path.
     */
    private static IWorkflowCondition pageIndicatorNotAtLastPage() {
        return new IWorkflowCondition() {
            @Override
            public boolean evaluate(IWorkflowVariables variables) {
                return !"3".equals(readPageIndicatorText(variables.require(PAGE)));
            }

            @Override
            public String describe() {
                return "pageIndicatorNotAtLastPage()";
            }

            @Override
            public Set<WorkflowVariable<?>> referencedVariables() {
                return Set.of(PAGE);
            }
        };
    }

    private static String readPageIndicatorText(IPage page) {
        ExtractionResult<String> extracted =
                page.extract(
                        ExtractionRequest.text(
                                LocatorDefinition.forRole(ElementRole.STATUS)
                                        .named("Current page")));
        return extracted.value();
    }

    private static IWorkflowStep clickNextStep() {
        return WorkflowSteps.action(
                "click-next",
                vars -> {
                    IPage page = vars.require(PAGE);
                    var next = page.find().button().named("Next").single();
                    return page.action().click(next);
                });
    }

    private static IWorkflowStep readCurrentPageStep() {
        return WorkflowSteps.action(
                "read-current-page",
                vars -> {
                    IPage page = vars.require(PAGE);
                    return new PageIndicatorReadAction(page);
                },
                CURRENT_PAGE);
    }

    /**
     * A real, minimal {@link IPreparedAction} for one synchronous extraction read - mirroring
     * {@code WorkflowLoginIT.TextReadAction}'s identical justification: extraction is a
     * deliberately separate, ungoverned subsystem (see {@code docs/limitations.md#observation}), so
     * this wraps a real {@link IPage#extract} call as the step's own action outcome rather than
     * inventing a fake one.
     */
    private static final class PageIndicatorReadAction implements IPreparedAction<String> {

        private final IPage page;

        PageIndicatorReadAction(IPage page) {
            this.page = page;
        }

        @Override
        public IPreparedAction<String> precondition(Predicate<IElement> predicate) {
            return this;
        }

        @Override
        public IPreparedAction<String> require(IVerification verification) {
            return this;
        }

        @Override
        public IPreparedAction<String> expect(IVerification verification) {
            return this;
        }

        @Override
        public IPreparedAction<String> expectUrlContains(String expectedFragment) {
            return this;
        }

        @Override
        public IPreparedAction<String> timeout(Duration timeout) {
            return this;
        }

        @Override
        public IPreparedAction<String> retry(RetryPolicy retryPolicy) {
            return this;
        }

        @Override
        public IPreparedAction<String> captureObservations(ObservationCapturePolicy policy) {
            return this;
        }

        @Override
        public ActionResult<String> execute() {
            return new ActionResult<>(
                    true,
                    readPageIndicatorText(page),
                    Duration.ZERO,
                    List.of(),
                    Optional.empty(),
                    ActionExecutionMode.REAL);
        }

        @Override
        public IPreparedAction<String> dryRun() {
            return this;
        }

        @Override
        public IActionPlan<String> plan() {
            throw new UnsupportedOperationException("plan() is not used by this workflow step");
        }
    }

    @Test
    void boundedLoopClicksNextExactlyTwiceAndStopsAtPageThree() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/workflow-loop-pagination")) {
            Workflow workflow = paginationWorkflow();

            WorkflowResult result =
                    engine.execute(workflow, WorkflowInputs.builder().put(PAGE, page).build());

            assertThat(result.completed()).isTrue();
            assertThat(result.output(CURRENT_PAGE)).contains("3");
            // Exactly 2 clicks: page 1->2, page 2->3 - never a 3rd/4th click once page 3 is
            // reached, verified against the fixture's own independent server-side counter.
            support.awaitClickCount("next", 2);
            assertThat(support.clickCount("next")).isEqualTo(2);
        }
    }

    @Test
    void executionTreeShowsExactlyTwoAuthorizedIterationsPlusTheFinalStop() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/workflow-loop-pagination")) {
            Workflow workflow = paginationWorkflow();

            WorkflowExecution execution =
                    engine.executeWithTree(
                            workflow, WorkflowInputs.builder().put(PAGE, page).build());

            assertThat(execution.result().completed()).isTrue();
            // steps: the loop wrapper is the workflow's only top-level step.
            WorkflowExecutionNode loopNode = execution.tree().nodes().get(0);
            assertThat(loopNode.result().stepType()).isEqualTo(WorkflowStepType.LOOP);
            assertThat(loopNode.branchSelection()).isEmpty();
            assertThat(loopNode.children()).hasSize(3); // 2 THEN iterations + 1 NONE stop
            for (int i = 0; i < 2; i++) {
                WorkflowExecutionNode iteration = loopNode.children().get(i);
                assertThat(iteration.result().stepType())
                        .isEqualTo(WorkflowStepType.LOOP_ITERATION);
                assertThat(iteration.branchSelection()).contains(WorkflowBranchSelection.THEN);
                assertThat(iteration.children()).hasSize(2); // click-next, read-current-page
            }
            WorkflowExecutionNode stop = loopNode.children().get(2);
            assertThat(stop.branchSelection()).contains(WorkflowBranchSelection.NONE);
            assertThat(stop.children()).isEmpty();
        }
    }

    /**
     * A target-identity failure inside the loop body (Governed Actions V2's own TOCTOU protection)
     * still fails closed exactly like it would outside a loop: no retry of the click, no further
     * iteration, and the loop's own wrapper stays SUCCEEDED (the failure is the body step's own,
     * exactly mirroring how a conditional's own decision node never itself reports a branch
     * failure).
     */
    @Test
    void targetChangedInsideTheLoopBodyFailsClosedWithNoRetryAndNoFurtherIteration()
            throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/workflow-loop-pagination")) {
            Workflow workflow =
                    Workflow.builder("paginate-target-changed")
                            .requiredInput(PAGE)
                            .step(
                                    WorkflowSteps.loop(
                                            "paginate-loop",
                                            pageIndicatorNotAtLastPage(),
                                            5,
                                            List.of(
                                                    clickNextWithTargetReplaced(page),
                                                    readCurrentPageStep())))
                            .build();

            WorkflowResult result =
                    engine.execute(workflow, WorkflowInputs.builder().put(PAGE, page).build());

            assertThat(result.completed()).isFalse();
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(WorkflowFailureType.ACTION_FAILED);
            assertThat(result.failure().orElseThrow().actionFailureType())
                    .contains(ActionFailureType.TARGET_CHANGED);
            // Nothing after the failing click ever ran: the read step is still present in the
            // flat steps list (WorkflowEngine records a NOT_RUN placeholder for every declared
            // step that follows a failure, exactly like it does outside a loop), but its status
            // proves it was never actually executed - no read, no further iteration.
            WorkflowStepResult readStep =
                    result.steps().stream()
                            .filter(s -> s.stepId().value().startsWith("read-current-page"))
                            .findFirst()
                            .orElseThrow();
            assertThat(readStep.status()).isEqualTo(WorkflowStepStatus.NOT_RUN);
            // Two independently-counted oracles, mirroring ActionPolicyTargetIdentityIT's own
            // rationale: the original "next" button and its replacement fire to distinct counter
            // names, so a click that lands on either one is separately observable - never
            // conflated the way a single shared counter/id would (which is also why the
            // replacement below is a freshly created element, not a clone of the original).
            assertThat(support.clickCount("next")).isZero();
            assertThat(support.clickCount("next-replacement")).isZero();
        }
    }

    /**
     * The replacement is a freshly created element with its own id and its own click counter -
     * never a {@code cloneNode} of the original - so a click landing on either one is independently
     * observable, exactly mirroring {@code ActionPolicyTargetIdentityIT}'s own {@code
     * replaceFirstWithReplacementSameLocator}-style fixtures. A clone sharing the original's id and
     * {@code onclick} would make the two indistinguishable to any oracle keyed by that shared
     * identity.
     */
    private static IWorkflowStep clickNextWithTargetReplaced(IPage page) {
        return WorkflowSteps.action(
                "click-next",
                vars -> {
                    var next = page.find().button().named("Next").first();
                    return page.action()
                            .click(next)
                            .policy(
                                    ctx -> {
                                        page.evaluate(
                                                "var old = document.getElementById('next');"
                                                        + " var replacement ="
                                                        + " document.createElement('button');"
                                                        + " replacement.id = 'next-replacement';"
                                                        + " replacement.setAttribute('aria-label',"
                                                        + " 'Next');"
                                                        + " replacement.textContent = 'Next';"
                                                        + " replacement.onclick = function() {"
                                                        + " fetch('/count-click/next-replacement'); };"
                                                        + " old.replaceWith(replacement);");
                                        return PolicyDecision.allow(
                                                "test.workflow.loop.target-changed");
                                    });
                });
    }

    @Test
    void recordingCapturesOnlyTheExecutedIterationsAndReplayNeverClicks() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/workflow-loop-pagination")) {
            Workflow workflow = paginationWorkflow();
            WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);

            WorkflowExecution execution =
                    engine.executeWithTree(
                            workflow, WorkflowInputs.builder().put(PAGE, page).build());
            assertThat(execution.result().completed()).isTrue();
            support.awaitClickCount("next", 2);

            WorkflowRecorderV2 recorder = new WorkflowRecorderV2();
            WorkflowRecordingV2 recording =
                    recorder.record(
                            new RecordingId("pagination-run-1"), Instant.now(), plan, execution);

            RecordedExecutionNodeV2 loopNode = recording.nodes().get(0);
            assertThat(loopNode.children()).hasSize(3);

            int clicksBeforeReplay = support.clickCount("next");

            Optional<ReplayValidationFailure> validation =
                    ReplayValidator.validate(recording, workflow);
            assertThat(validation).isEmpty();

            IReplayOutcome outcome = WorkflowReplayer.replay(recording, workflow);
            assertThat(outcome).isInstanceOf(IReplayOutcome.Replayed.class);
            ReplayedWorkflow replayed = ((IReplayOutcome.Replayed) outcome).workflow();
            // loop wrapper + (decision + click-next + read-current-page) * 2 iterations +
            // the final (false) stop decision = 1 + 3*2 + 1 = 8.
            assertThat(replayed.steps()).hasSize(8);

            // Structural/decision replay performs zero browser side effects: the server's own
            // independent click counter is unchanged after replay.
            assertThat(support.clickCount("next")).isEqualTo(clicksBeforeReplay);
        }
    }
}
