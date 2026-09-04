package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.browser.IPage;
import io.webagent4j.workflow.IWorkflowCondition;
import io.webagent4j.workflow.IWorkflowVariables;
import io.webagent4j.workflow.Workflow;
import io.webagent4j.workflow.WorkflowBranchSelection;
import io.webagent4j.workflow.WorkflowExecutionPlan;
import io.webagent4j.workflow.WorkflowInputs;
import io.webagent4j.workflow.WorkflowPlanNode;
import io.webagent4j.workflow.WorkflowPlanner;
import io.webagent4j.workflow.WorkflowStepType;
import io.webagent4j.workflow.WorkflowSteps;
import io.webagent4j.workflow.WorkflowVariable;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * PLAN-012 real-Playwright evidence: {@link WorkflowPlanner#plan(Workflow)} on a workflow whose
 * action steps are backed by real, live DOM elements causes zero clicks and zero navigation -
 * independent proof from the fixture's own server-side click counter ({@link
 * Phase4TestSupport#clickCount}), never from the library's own bookkeeping alone. This is the same
 * fixture and the same real click-backed steps {@code WorkflowBranchingIT} uses to prove the
 * opposite fact (that an actual execution does click) - here proving planning never does.
 */
class WorkflowExecutionPlanIT {

    private static final WorkflowVariable<IPage> PAGE =
            WorkflowVariable.publicValue("page", IPage.class);

    /**
     * A condition that counts every {@code evaluate()} call - must never be invoked by planning.
     */
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
    void planningARealClickBackedWorkflowCausesZeroClicksAndRepresentsBothBranches()
            throws Exception {
        AtomicInteger evaluations = new AtomicInteger();
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/workflow-branch-ready")) {
            Workflow workflow =
                    Workflow.builder("branch-plan")
                            .requiredInput(PAGE)
                            .step(
                                    WorkflowSteps.ifElse(
                                            "branch",
                                            new CountingCondition(true, evaluations),
                                            List.of(confirmStep()),
                                            List.of(cancelStep())))
                            .build();

            WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);

            // The plan is fully built - but nothing about the real page was ever touched.
            assertThat(evaluations).hasValue(0);
            assertThat(support.clickCount("confirm")).isZero();
            assertThat(support.clickCount("cancel")).isZero();
            assertThat(support.clickCount()).isZero();

            WorkflowPlanNode conditional = plan.nodes().get(0);
            assertThat(conditional.stepType()).isEqualTo(WorkflowStepType.CONDITIONAL);
            assertThat(conditional.branches()).hasSize(2);
            assertThat(conditional.branches().get(0).kind())
                    .isEqualTo(WorkflowBranchSelection.THEN);
            assertThat(conditional.branches().get(0).nodes().get(0).stepId().value())
                    .isEqualTo("confirm");
            assertThat(conditional.branches().get(1).kind())
                    .isEqualTo(WorkflowBranchSelection.ELSE);
            assertThat(conditional.branches().get(1).nodes().get(0).stepId().value())
                    .isEqualTo("cancel");

            // For contrast (never executed here): a real execution of the very same workflow does
            // click exactly once through the real action pipeline - proving the plan/execution
            // distinction is genuine, not merely a difference in what is asserted.
            var engine = new io.webagent4j.workflow.WorkflowEngine();
            var result = engine.execute(workflow, WorkflowInputs.builder().put(PAGE, page).build());
            assertThat(result.completed()).isTrue();
            support.awaitClickCount("confirm", 1);
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
}
