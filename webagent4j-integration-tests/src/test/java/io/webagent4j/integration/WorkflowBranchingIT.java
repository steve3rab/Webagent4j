package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionFailureType;
import io.webagent4j.browser.IPage;
import io.webagent4j.policy.PolicyDecision;
import io.webagent4j.workflow.IWorkflowCondition;
import io.webagent4j.workflow.IWorkflowVariables;
import io.webagent4j.workflow.Workflow;
import io.webagent4j.workflow.WorkflowEngine;
import io.webagent4j.workflow.WorkflowFailureType;
import io.webagent4j.workflow.WorkflowInputs;
import io.webagent4j.workflow.WorkflowResult;
import io.webagent4j.workflow.WorkflowStepStatus;
import io.webagent4j.workflow.WorkflowSteps;
import io.webagent4j.workflow.WorkflowVariable;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Real-Playwright integration coverage for deterministic workflow branching: proves through the
 * real action pipeline - never a fake {@code IPreparedAction} - that a branch decision selects
 * exactly one of two real, independently-observable side effects, and that a target-identity
 * failure inside the selected branch (Governed Actions V2's own TOCTOU protection) never falls back
 * to the other branch. Independent proof comes from the fixture's own server-side click counters
 * ({@link Phase4TestSupport#clickCount}), never from the library's own success/failure verdict
 * alone.
 */
class WorkflowBranchingIT {

    private static final WorkflowVariable<IPage> PAGE =
            WorkflowVariable.publicValue("page", IPage.class);

    private final WorkflowEngine engine = new WorkflowEngine();

    /** A condition that counts every {@code evaluate()} call and returns a fixed outcome. */
    private static final class CountingCondition implements IWorkflowCondition {
        private final boolean outcome;
        private final AtomicInteger evaluations;

        CountingCondition(boolean outcome, AtomicInteger evaluations) {
            this.outcome = outcome;
            this.evaluations = evaluations;
        }

        @Override
        public boolean evaluate(IWorkflowVariables variables) {
            evaluations.incrementAndGet();
            return outcome;
        }

        @Override
        public String describe() {
            return "counting(" + outcome + ")";
        }

        @Override
        public Set<WorkflowVariable<?>> referencedVariables() {
            return Set.of();
        }
    }

    @Test
    void trueDecisionRunsThenBranchClickingConfirmOnlyThroughTheRealActionPipeline()
            throws Exception {
        AtomicInteger evaluations = new AtomicInteger();
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/workflow-branch-ready")) {
            Workflow workflow =
                    Workflow.builder("branch-true")
                            .requiredInput(PAGE)
                            .step(
                                    WorkflowSteps.ifElse(
                                            "branch",
                                            new CountingCondition(true, evaluations),
                                            List.of(confirmStep()),
                                            List.of(cancelStep())))
                            .build();

            WorkflowResult result =
                    engine.execute(workflow, WorkflowInputs.builder().put(PAGE, page).build());

            assertThat(result.completed()).isTrue();
            assertThat(evaluations).hasValue(1);
            support.awaitClickCount("confirm", 1);
            assertThat(support.clickCount("cancel")).isZero();
        }
    }

    @Test
    void falseDecisionRunsElseBranchClickingCancelOnlyThroughTheRealActionPipeline()
            throws Exception {
        AtomicInteger evaluations = new AtomicInteger();
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/workflow-branch-ready")) {
            Workflow workflow =
                    Workflow.builder("branch-false")
                            .requiredInput(PAGE)
                            .step(
                                    WorkflowSteps.ifElse(
                                            "branch",
                                            new CountingCondition(false, evaluations),
                                            List.of(confirmStep()),
                                            List.of(cancelStep())))
                            .build();

            WorkflowResult result =
                    engine.execute(workflow, WorkflowInputs.builder().put(PAGE, page).build());

            assertThat(result.completed()).isTrue();
            assertThat(evaluations).hasValue(1);
            support.awaitClickCount("cancel", 1);
            assertThat(support.clickCount("confirm")).isZero();
        }
    }

    /**
     * Adversarial: the branch decision selects THEN, but immediately before the backend click the
     * physical target is replaced by a different element satisfying the identical semantic locator
     * - Governed Actions V2's own TOCTOU protection (see {@link ActionPolicyTargetIdentityIT})
     * still fails this specific action closed with {@code TARGET_CHANGED}. This must never be
     * reinterpreted as "the branch condition should be re-tried" or "fall back to ELSE": the branch
     * selection made before the action ever started is final, and the failure is reported as an
     * ordinary {@code ACTION_FAILED} step failure like any other action failure inside a branch.
     */
    @Test
    void targetChangedInsideTheSelectedBranchFailsClosedAndNeverFallsBackToTheOtherBranch()
            throws Exception {
        AtomicInteger evaluations = new AtomicInteger();
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/workflow-branch-target-changed")) {
            Workflow workflow =
                    Workflow.builder("branch-target-changed")
                            .requiredInput(PAGE)
                            .step(
                                    WorkflowSteps.ifElse(
                                            "branch",
                                            new CountingCondition(true, evaluations),
                                            List.of(confirmStepWithTargetReplacedByPolicy(page)),
                                            List.of(cancelStep())))
                            .build();

            WorkflowResult result =
                    engine.execute(workflow, WorkflowInputs.builder().put(PAGE, page).build());

            assertThat(result.completed()).isFalse();
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(WorkflowFailureType.ACTION_FAILED);
            assertThat(result.failure().orElseThrow().actionFailureType())
                    .contains(ActionFailureType.TARGET_CHANGED);
            assertThat(result.steps().get(0).status()).isEqualTo(WorkflowStepStatus.SUCCEEDED);
            assertThat(result.steps().get(1).status()).isEqualTo(WorkflowStepStatus.FAILED);

            assertThat(evaluations)
                    .as("the branch condition must not be re-evaluated after the failure")
                    .hasValue(1);
            // Independent proof neither the original target nor its mid-flight replacement was
            // ever actually clicked, and ELSE never ran as a fallback.
            assertThat(support.clickCount("original")).isZero();
            assertThat(support.clickCount("replacement")).isZero();
            assertThat(support.clickCount("cancel")).isZero();
        }
    }

    private static io.webagent4j.workflow.IWorkflowStep confirmStep() {
        return WorkflowSteps.action(
                "confirm",
                vars -> {
                    IPage page = vars.require(PAGE);
                    var confirm = page.find().button().named("Confirm").single();
                    return page.action().click(confirm);
                });
    }

    private static io.webagent4j.workflow.IWorkflowStep cancelStep() {
        return WorkflowSteps.action(
                "cancel",
                vars -> {
                    IPage page = vars.require(PAGE);
                    var cancel = page.find().button().named("Cancel").single();
                    return page.action().click(cancel);
                });
    }

    private static io.webagent4j.workflow.IWorkflowStep confirmStepWithTargetReplacedByPolicy(
            IPage page) {
        return WorkflowSteps.action(
                "confirm",
                vars -> {
                    var confirm = page.find().button().named("Confirm").first();
                    return page.action()
                            .click(confirm)
                            .policy(
                                    ctx -> {
                                        page.evaluate(
                                                "replaceConfirmButtonWithFreshNodeSameLocator()");
                                        return PolicyDecision.allow(
                                                "test.workflow.branch.target-changed");
                                    });
                });
    }
}
