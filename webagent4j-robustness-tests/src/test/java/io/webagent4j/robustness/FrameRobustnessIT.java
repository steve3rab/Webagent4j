package io.webagent4j.robustness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.webagent4j.action.ActionResult;
import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IFrame;
import io.webagent4j.browser.IPage;
import io.webagent4j.locator.AmbiguousLocatorException;
import io.webagent4j.locator.LocatorNotFoundException;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Ten named, deterministic, local-only adversarial scenarios (FRAME-001..FRAME-010) proving public
 * frame traversal holds up under the same hostile conditions the rest of the 100-scenario
 * robustness corpus tests at the element level: duplicate frame names must be rejected as ambiguous
 * rather than resolved to "the first one"; a frame whose name/title is a decoy for another frame's
 * criterion must never satisfy it; wrong-frame protection must hold even when two frames contain
 * pixel-identical controls; and absence, delayed insertion, and same-identity replacement must all
 * be classified deterministically. Kept as its own dedicated scenario set (mirroring {@link
 * WebAgentCoreRobustnessIT}, {@link SemanticConsistencyIT}, and friends) rather than folded into
 * {@link RobustnessCorpus}: that corpus's {@link RobustnessScenario} model and its own fixed
 * hundred-scenario invariant are element-only and were never designed for a document-boundary
 * concept, so extending them was a materially riskier change than adding a sibling scenario set.
 *
 * <p>Every positive scenario that performs a real click awaits {@link
 * RobustnessTestApplication#awaitExecution} before reading {@link
 * RobustnessTestApplication#actualTarget()}/{@link RobustnessTestApplication#executionCount()}: the
 * fixture's click handler fires its {@code /track} side effect asynchronously, with no
 * happens-before relationship to Playwright's click call returning, so reading that state
 * immediately afterward is an observation race, not a resolver correctness question - purely
 * scheduling latency on some browsers can leave the local HTTP round-trip still in flight when
 * {@code execute()} already returned. Purely negative scenarios that never click anything keep
 * asserting {@code executionCount()} directly; there is no side effect to await there.
 *
 * <p>Every positive scenario asserts success through {@link #assertActionSucceeded(ActionResult)}
 * rather than {@link ActionResult#throwIfFailed()} directly: a bare {@code throwIfFailed()} failure
 * message carries only the action id and {@link io.webagent4j.action.ActionStatus}, which is enough
 * to know an action failed but not why. {@link ActionResult#toCompactText()} is a backend-agnostic,
 * already-safe rendering - documented to never expose backend objects or sensitive values - that
 * additionally surfaces the resolved target description, the structured {@link
 * io.webagent4j.action.ActionFailureType} taxonomy, and precondition/postcondition counts, which is
 * exactly the taxonomy needed to tell a resolution failure from a governed-identity failure from a
 * backend failure without ever printing a raw Playwright exception message.
 */
@Tag("robustness")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FrameRobustnessIT {

    private RobustnessTestApplication application;
    private IBrowser browser;

    @BeforeAll
    void startInfrastructure() throws Exception {
        application = RobustnessTestApplication.start();
        browser = RobustnessBrowserLauncher.launch();
    }

    @AfterAll
    void stopInfrastructure() {
        browser.close();
        application.close();
    }

    @Test
    void frame001SimpleUniqueFrameIsResolvedAndActedOn() {
        application.reset();
        try (IPage page = browser.open(application.fixtureUrl("frames/frame-scenarios.html"))) {
            IFrame checkout = page.frame().named("checkout-simple").single();

            ActionResult<Void> result =
                    checkout.action()
                            .click(checkout.find().button().named("Pay").reference())
                            .execute();
            assertActionSucceeded(result);

            application.awaitExecution("frame-001-checkout", 1, Duration.ofSeconds(1));
            assertThat(application.actualTarget()).isEqualTo("frame-001-checkout");
            assertThat(application.executionCount()).isEqualTo(1);
        }
    }

    @Test
    void frame002AStableIdCriterionResolvesCorrectlyAmidMisleadingNameAndTitleDecoys() {
        application.reset();
        try (IPage page = browser.open(application.fixtureUrl("frames/frame-scenarios.html"))) {
            IFrame stable = page.frame().withId("dashboard-stable").single();

            ActionResult<Void> result =
                    stable.action()
                            .click(stable.find().button().named("Continue").reference())
                            .execute();
            assertActionSucceeded(result);

            application.awaitExecution("frame-002-target", 1, Duration.ofSeconds(1));
            assertThat(application.actualTarget()).isEqualTo("frame-002-target");
            assertThat(application.executionCount()).isEqualTo(1);
        }
    }

    @Test
    void frame003TwoFramesSharingTheSameNameAreRejectedAsAmbiguous() {
        application.reset();
        try (IPage page = browser.open(application.fixtureUrl("frames/frame-scenarios.html"))) {
            assertThatExceptionOfType(AmbiguousLocatorException.class)
                    .isThrownBy(() -> page.frame().named("payment-dup").single());
            assertThat(application.executionCount()).isZero();
        }
    }

    @Test
    void frame004IdenticalButtonsInTwoFramesOnlyExecuteInTheCorrectlyScopedFrame() {
        application.reset();
        try (IPage page = browser.open(application.fixtureUrl("frames/frame-scenarios.html"))) {
            IFrame productA = page.frame().named("product-a-frame").single();

            ActionResult<Void> result =
                    productA.action()
                            .click(productA.find().button().named("Buy").reference())
                            .execute();
            assertActionSucceeded(result);

            application.awaitExecution("frame-004-product-a", 1, Duration.ofSeconds(1));
            assertThat(application.actualTarget()).isEqualTo("frame-004-product-a");
            assertThat(application.executionCount()).isEqualTo(1);
        }
    }

    @Test
    void frame005ATargetInsideANestedFrameIsResolvedAndActedOn() {
        application.reset();
        try (IPage page = browser.open(application.fixtureUrl("frames/frame-scenarios.html"))) {
            IFrame outer = page.frame().named("outer-frame").single();
            IFrame inner = outer.frame().named("inner-frame").single();

            ActionResult<Void> result =
                    inner.action().click(inner.find().button().named("Pay").reference()).execute();
            assertActionSucceeded(result);

            application.awaitExecution("frame-005-nested", 1, Duration.ofSeconds(1));
            assertThat(application.actualTarget()).isEqualTo("frame-005-nested");
            assertThat(application.executionCount()).isEqualTo(1);
        }
    }

    @Test
    void frame006ATargetAbsentInsideAnExistingFrameFailsAsTypedNotFoundWithoutExecutingAnything() {
        application.reset();
        try (IPage page = browser.open(application.fixtureUrl("frames/frame-scenarios.html"))) {
            IFrame empty = page.frame().named("empty-target-frame").single();

            assertThatExceptionOfType(LocatorNotFoundException.class)
                    .isThrownBy(() -> empty.find().button().named("Continue").single());
            assertThat(application.executionCount()).isZero();
        }
    }

    @Test
    void frame007AnAbsentFrameFailsAsTypedNotFoundBeforeAnyTargetLookup() {
        application.reset();
        try (IPage page = browser.open(application.fixtureUrl("frames/frame-scenarios.html"))) {
            assertThatExceptionOfType(LocatorNotFoundException.class)
                    .isThrownBy(() -> page.frame().named("totally-missing-frame").single());
            assertThat(application.executionCount()).isZero();
        }
    }

    @Test
    void frame008AFrameInsertedAfterADelayIsFoundWithinTheBoundedTimeout() {
        application.reset();
        try (IPage page = browser.open(application.fixtureUrl("frames/frame-delayed.html"))) {
            IFrame delayed =
                    page.frame().named("delayed-frame").timeout(Duration.ofMillis(800)).single();

            ActionResult<Void> result =
                    delayed.action()
                            .click(delayed.find().button().named("Confirm").reference())
                            .execute();
            assertActionSucceeded(result);

            application.awaitExecution("frame-008-target", 1, Duration.ofSeconds(1));
            assertThat(application.actualTarget()).isEqualTo("frame-008-target");
            assertThat(application.executionCount()).isEqualTo(1);
        }
    }

    @Test
    void frame009AFrameRemovedAndReplacedBySameIdentityExecutesAgainstTheNewDocumentOnly() {
        application.reset();
        try (IPage page = browser.open(application.fixtureUrl("frames/frame-replace.html"))) {
            IFrame replaceFrame = page.frame().named("replace-frame").single();

            page.evaluate("replaceFrame()");
            ActionResult<Void> result =
                    replaceFrame
                            .action()
                            .click(replaceFrame.find().button().named("Confirm").reference())
                            .execute();
            assertActionSucceeded(result);

            application.awaitExecution("frame-009-v2", 1, Duration.ofSeconds(1));
            assertThat(application.actualTarget()).isEqualTo("frame-009-v2");
            assertThat(application.executionCount()).isEqualTo(1);
        }
    }

    @Test
    void frame010AMisleadingNameTitleCombinationNeverCrossSatisfiesTheOtherCriterion() {
        application.reset();
        try (IPage page = browser.open(application.fixtureUrl("frames/frame-scenarios.html"))) {
            IFrame billing = page.frame().named("billing").single();

            ActionResult<Void> result =
                    billing.action()
                            .click(billing.find().button().named("Continue").reference())
                            .execute();
            assertActionSucceeded(result);

            application.awaitExecution("frame-010-target", 1, Duration.ofSeconds(1));
            assertThat(application.actualTarget()).isEqualTo("frame-010-target");
            assertThat(application.executionCount()).isEqualTo(1);
        }
    }

    /**
     * Asserts a governed click succeeded, and on failure surfaces {@link
     * ActionResult#toCompactText()} in the assertion message rather than relying on {@link
     * ActionResult#throwIfFailed()}'s minimal id-and-status-only message: the compact rendering
     * adds the resolved target description, the {@link io.webagent4j.action.ActionFailureType}
     * taxonomy, and precondition/postcondition counts, all through an API already documented to
     * never expose backend objects or sensitive values.
     */
    private static void assertActionSucceeded(ActionResult<Void> result) {
        assertThat(result.success()).as("%s", result.toCompactText()).isTrue();
    }
}
