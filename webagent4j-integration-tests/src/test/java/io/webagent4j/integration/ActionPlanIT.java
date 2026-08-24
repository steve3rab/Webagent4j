package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionPlanStatus;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.IActionPlan;
import org.junit.jupiter.api.Test;

/**
 * Proves IActionPlan's real-browser contract: plan() performs zero backend side effects, and
 * IActionPlan.execute() always revalidates against the live DOM instead of trusting the plan()-time
 * snapshot, so it can never act on the wrong element, silently tolerate new ambiguity, or ignore a
 * precondition that stopped holding. Independent proof comes from the fixture's own server-side
 * click counter, not from the library's own success/failure verdict.
 */
class ActionPlanIT {

    @Test
    void planAloneNeverModifiesThePage() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/click")) {
            var target = page.find().button().named("Increment").reference();

            IActionPlan<Void> plan = page.action().click(target).plan();

            assertThat(plan.status()).isEqualTo(ActionPlanStatus.READY);
            assertThat(page.content()).contains("id=\"counter\">0");
            assertThat(support.clickCount()).isZero();
        }
    }

    @Test
    void executingAReadyPlanForAnUnchangedTargetRunsTheBackendExactlyOnce() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/plan-same-target")) {
            var target = page.find().button().named("Confirm").reference();

            IActionPlan<Void> plan = page.action().click(target).plan();
            assertThat(plan.status()).isEqualTo(ActionPlanStatus.READY);

            page.evaluate("replaceConfirmButtonWithFreshNode()");
            ActionResult<Void> result = plan.execute();

            assertThat(result.success()).isTrue();
            assertThat(support.clickCount()).isEqualTo(1);
        }
    }

    @Test
    void revalidationBlocksExecutionWhenTheSemanticTargetIsGone() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/plan-wrong-target")) {
            var target = page.find().button().named("Confirm").reference();

            IActionPlan<Void> plan = page.action().click(target).plan();
            assertThat(plan.status()).isEqualTo(ActionPlanStatus.READY);

            page.evaluate("replaceConfirmButtonWithUnrelatedDeleteButton()");
            ActionResult<Void> result = plan.execute();

            assertThat(result.success()).isFalse();
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(ActionFailureType.TARGET_NOT_FOUND);
            // Independent proof: neither the original "Confirm" nor the unrelated "Delete" button
            // that replaced it was ever clicked.
            assertThat(support.clickCount()).isZero();
        }
    }

    @Test
    void revalidationBlocksExecutionWhenNewAmbiguityAppears() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/plan-ambiguity")) {
            var target = page.find().button().named("Confirm").reference();

            IActionPlan<Void> plan = page.action().click(target).plan();
            assertThat(plan.status()).isEqualTo(ActionPlanStatus.READY);

            page.evaluate("addDuplicateConfirmButton()");
            ActionResult<Void> result = plan.execute();

            assertThat(result.success()).isFalse();
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(ActionFailureType.TARGET_AMBIGUOUS);
            assertThat(support.clickCount()).isZero();
        }
    }

    @Test
    void pageControlledCandidateIdentityStateCannotHideNewAmbiguity() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/plan-ambiguity")) {
            var target = page.find().button().named("Confirm").reference();

            IActionPlan<Void> plan = page.action().click(target).plan();
            assertThat(plan.status()).isEqualTo(ActionPlanStatus.READY);

            /*
             * Reproduce the exact old trust-boundary defect. The vulnerable implementation kept
             * its identity WeakMap directly on application globalThis, allowing the page to make
             * two physical nodes appear to have the same opaque backend identity.
             */
            page.evaluate(
                    """
                    (() => {
                      const original = document.querySelector("#host button");
                      const originalIdentity =
                        globalThis.__webagent4jLocatorIds?.get(original)
                          ?? "forged-collision";

                      addDuplicateConfirmButton();

                      const candidates =
                        document.querySelectorAll("#host button");
                      const forged = new WeakMap();
                      forged.set(candidates[0], originalIdentity);
                      forged.set(candidates[1], originalIdentity);

                      globalThis.__webagent4jLocatorIds = forged;
                      globalThis.__webagent4jLocatorSequence = 1;
                    })()
                    """);

            ActionResult<Void> result = plan.execute();

            assertThat(result.success()).isFalse();
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(ActionFailureType.TARGET_AMBIGUOUS);
            assertThat(support.clickCount()).isZero();
        }
    }

    @Test
    void revalidationBlocksExecutionWhenThePreconditionStopsHolding() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/plan-precondition-invalidates")) {
            var target = page.find().button().named("Confirm").reference();

            IActionPlan<Void> plan = page.action().click(target).plan();
            assertThat(plan.status()).isEqualTo(ActionPlanStatus.READY);

            page.evaluate("disableConfirmButton()");
            ActionResult<Void> result = plan.execute();

            assertThat(result.success()).isFalse();
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(ActionFailureType.PRECONDITION_FAILED);
            assertThat(support.clickCount()).isZero();
        }
    }
}
