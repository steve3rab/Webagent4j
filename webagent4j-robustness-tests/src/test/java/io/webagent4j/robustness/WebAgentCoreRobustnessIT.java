package io.webagent4j.robustness;

import static io.webagent4j.verification.Verifications.textVisible;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.action.ActionResult;
import io.webagent4j.action.ObservationCapturePolicy;
import io.webagent4j.action.Secret;
import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IPage;
import io.webagent4j.core.WebAgent;
import io.webagent4j.locator.AmbiguousLocatorException;
import io.webagent4j.locator.LocatorNotFoundException;
import io.webagent4j.locator.LocatorResolutionStatus;
import io.webagent4j.locator.api.ElementRole;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@Tag("robustness")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebAgentCoreRobustnessIT {

    private RobustnessTestApplication application;
    private IBrowser browser;

    @BeforeAll
    void startInfrastructure() throws Exception {
        application = RobustnessTestApplication.start();
        browser = WebAgent.browser().playwright().chromium().headless(true).launch();
    }

    @AfterAll
    void stopInfrastructure() {
        browser.close();
        application.close();
    }

    @Test
    void journeyALoginCoversObservationLocationActionsVerificationAndDiff() {
        application.reset();
        try (IPage page = browser.open(application.fixtureUrl("journeys/login.html"))) {
            var before = page.observe();
            assertThat(before.forms()).hasSize(1);

            var email = page.find().textbox().labelled("Email").single();
            var password = page.find().textbox().labelled("Password").single();
            var remember = page.find().checkbox().named("Remember me").single();
            page.action().type(email, "user@example.test").execute().throwIfFailed();
            page.action()
                    .typeSecret(password, Secret.of("not-a-real-secret"))
                    .execute()
                    .throwIfFailed();
            page.action().check(remember).execute().throwIfFailed();

            ActionResult<Void> result =
                    page.action()
                            .click(page.find().button().named("Sign in").single())
                            .expect(textVisible("Welcome to your account"))
                            .captureObservations(ObservationCapturePolicy.ALWAYS)
                            .execute();

            assertSuccessfulTrackedAction(result, "journey-login");
            assertThat(result.afterObservation().headings())
                    .anyMatch(heading -> heading.text().equals("Dashboard"));
            assertThat(before.diff(result.afterObservation()).empty()).isFalse();
            assertBrowserHealthy(page);
        }
    }

    @Test
    void journeyBUsesScopeToSelectTheCorrectDuplicateCardAction() {
        application.reset();
        try (IPage page = browser.open(application.fixtureUrl("journeys/product-cards.html"))) {
            var before = page.observe();
            assertThat(before.buttons()).hasSize(2);
            var card = page.find().role(ElementRole.REGION).named("Desk lamp").single();
            var add = card.find().button().named("Add").single();

            ActionResult<Void> result =
                    page.action()
                            .click(add)
                            .expect(textVisible("lamp added to cart"))
                            .captureObservations(ObservationCapturePolicy.ALWAYS)
                            .execute();

            assertSuccessfulTrackedAction(result, "journey-product-lamp");
            assertThat(result.diff().empty()).isFalse();
            assertBrowserHealthy(page);
        }
    }

    @Test
    void journeyCHandlesADeferredPortalModalAndClosesIt() {
        application.reset();
        try (IPage page = browser.open(application.fixtureUrl("journeys/dynamic-modal.html"))) {
            ActionResult<Void> opened =
                    page.action()
                            .click(page.find().button().named("Open preferences").single())
                            .expect(textVisible("Preferences"))
                            .timeout(Duration.ofSeconds(2))
                            .captureObservations(ObservationCapturePolicy.ALWAYS)
                            .execute();

            assertSuccessfulTrackedAction(opened, "journey-modal-open");
            assertThat(opened.afterObservation().dialogs()).hasSize(1);
            assertThat(opened.diff().dialogsOpened()).hasSize(1);

            ActionResult<Void> closed =
                    page.action()
                            .click(page.find().button().named("Close preferences").single())
                            .captureObservations(ObservationCapturePolicy.ALWAYS)
                            .execute();
            assertThat(closed.success()).isTrue();
            assertThat(closed.afterObservation().dialogs()).isEmpty();
            assertBrowserHealthy(page);
        }
    }

    @Test
    void journeyDBadSemanticsFailsSafelyWithoutExecutingAnything() {
        application.reset();
        try (IPage page = browser.open(application.fixtureUrl("journeys/bad-semantics.html"))) {
            assertThat(page.observe().buttons()).isEmpty();

            assertThatThrownBy(() -> page.find().button().named("Approve transfer").single())
                    .isInstanceOf(LocatorNotFoundException.class)
                    .extracting(error -> ((LocatorNotFoundException) error).status())
                    .isEqualTo(LocatorResolutionStatus.UNRESOLVABLE);
            assertThat(application.executionCount()).isZero();
            assertBrowserHealthy(page);
        }
    }

    @Test
    void journeyEAmbiguousControlsFailSafelyWithoutExecutingAnything() {
        application.reset();
        try (IPage page =
                browser.open(application.fixtureUrl("ambiguous/duplicate-controls.html"))) {
            assertThatThrownBy(() -> page.find().button().named("Continue checkout").single())
                    .isInstanceOf(AmbiguousLocatorException.class)
                    .extracting(error -> ((AmbiguousLocatorException) error).status())
                    .isEqualTo(LocatorResolutionStatus.AMBIGUOUS);
            assertThat(application.executionCount()).isZero();
            assertBrowserHealthy(page);
        }
    }

    private void assertSuccessfulTrackedAction(ActionResult<Void> result, String target) {
        assertThat(result.success()).isTrue();
        assertThat(result.postconditions()).allMatch(postcondition -> postcondition.success());
        assertThat(application.actualTarget()).isEqualTo(target);
        assertThat(application.executionCount()).isEqualTo(1);
        assertThat(result.afterObservation()).isNotNull();
    }

    private static void assertBrowserHealthy(IPage page) {
        assertThat(page.evaluate("1 + 1")).isEqualTo(2);
    }
}
