package io.webagent4j.robustness;

import static io.webagent4j.verification.Verifications.textVisible;
import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionResult;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IPage;
import io.webagent4j.core.WebAgent;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.AmbiguousLocatorException;
import io.webagent4j.locator.LocatorNotFoundException;
import io.webagent4j.locator.LocatorResolutionStatus;
import io.webagent4j.locator.api.IElementReference;
import io.webagent4j.locator.api.IFind;
import io.webagent4j.locator.api.ILocator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("robustness")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RobustnessBenchmarkIT {

    private final List<ScenarioResult> results = new ArrayList<>();
    private RobustnessTestApplication application;
    private IBrowser browser;

    @BeforeAll
    void startInfrastructure() throws Exception {
        application = RobustnessTestApplication.start();
        browser = WebAgent.browser().playwright().chromium().headless(true).launch();
    }

    @AfterAll
    void writeReportAndStopInfrastructure() throws Exception {
        try {
            RobustnessReportWriter.write(List.copyOf(results));
            if (System.getProperty("scenario", "").isBlank()) {
                RobustnessMetrics metrics = RobustnessMetrics.from(results);
                assertThat(metrics.total()).isEqualTo(100);
                assertThat(metrics.wrongTargets()).as("wrong target gate").isZero();
                assertThat(metrics.unexpectedExceptions()).as("unexpected exception gate").isZero();
                assertThat(
                                results.stream()
                                        .filter(
                                                result ->
                                                        result.scenario().expectation()
                                                                == ScenarioExpectation
                                                                        .MUST_BE_AMBIGUOUS)
                                        .allMatch(ScenarioResult::expectedOutcome))
                        .as("all expected ambiguity must be recognized")
                        .isTrue();
                assertThat(
                                results.stream()
                                        .filter(
                                                result ->
                                                        result.scenario().expectation()
                                                                == ScenarioExpectation
                                                                        .MUST_BE_UNRESOLVABLE)
                                        .allMatch(ScenarioResult::expectedOutcome))
                        .as("all expected unresolved cases must be rejected safely")
                        .isTrue();
                assertThat(
                                results.stream()
                                        .filter(
                                                result ->
                                                        result.scenario().id().startsWith("CLEAN-")
                                                                || result.scenario()
                                                                        .id()
                                                                        .startsWith("ARIA-"))
                                        .allMatch(ScenarioResult::expectedOutcome))
                        .as("clean and standards-based ARIA gate")
                        .isTrue();
            }
        } finally {
            if (browser != null) {
                browser.close();
            }
            if (application != null) {
                application.close();
            }
        }
    }

    Stream<RobustnessScenario> scenarios() {
        String selected = System.getProperty("scenario", "").strip();
        Stream<RobustnessScenario> scenarios = RobustnessCorpus.scenarios().stream();
        if (!selected.isBlank()) {
            List<RobustnessScenario> matching =
                    scenarios.filter(scenario -> scenario.id().equals(selected)).toList();
            if (matching.isEmpty()) {
                throw new IllegalArgumentException("Unknown robustness scenario: " + selected);
            }
            return matching.stream();
        }
        return scenarios;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void validatesScenario(RobustnessScenario scenario) {
        application.reset();
        try (IPage page = browser.open(application.fixtureUrl(scenario.fixture()))) {
            ScenarioResult result = execute(page, scenario);
            results.add(result);
            if (!result.expectedOutcome() || result.unexpectedException()) {
                RobustnessReportWriter.failureArtifacts(page, result);
            }
            assertThat(result.unexpectedException())
                    .as(reproduction(scenario) + System.lineSeparator() + result.diagnostics())
                    .isFalse();
            assertThat(result.wrongTarget())
                    .as(reproduction(scenario) + System.lineSeparator() + "wrong target selected")
                    .isFalse();
            assertThat(result.expectedOutcome())
                    .as(
                            reproduction(scenario)
                                    + System.lineSeparator()
                                    + "expected "
                                    + scenario.expectation()
                                    + " but got "
                                    + result.status()
                                    + System.lineSeparator()
                                    + result.diagnostics())
                    .isTrue();
        }
    }

    private ScenarioResult execute(IPage page, RobustnessScenario scenario) {
        long started = System.nanoTime();
        try {
            ILocator<IElement> locator = locator(page, scenario);
            IElement element;
            boolean reresolved = false;
            if (scenario.id().startsWith("DYNAMIC-")
                    && Integer.parseInt(scenario.id().substring("DYNAMIC-".length())) >= 6) {
                IElementReference<IElement> reference = locator.reference();
                element = reference.resolve();
                page.evaluate("new Promise(resolve => setTimeout(resolve, 220))");
                element = reference.resolve();
                reresolved = "2".equals(element.attributes().get("data-generation"));
            } else {
                element = locator.single();
            }
            Duration duration = elapsed(started);
            String actualTarget = element.attributes().getOrDefault("data-target", "");
            boolean actionSucceeded = false;
            boolean verificationFailed = false;
            boolean wrongTarget =
                    scenario.expectedTarget().isBlank()
                            || !scenario.expectedTarget().equals(actualTarget);
            if (scenario.expectation() == ScenarioExpectation.MUST_EXECUTE_AND_VERIFY) {
                ActionResult<Void> action =
                        page.action()
                                .click(element)
                                .expect(textVisible("Executed " + scenario.expectedTarget()))
                                .execute();
                actionSucceeded = action.success();
                verificationFailed = action.status() == ActionStatus.VERIFICATION_FAILED;
                actualTarget = application.actualTarget();
                wrongTarget =
                        !scenario.expectedTarget().equals(actualTarget)
                                || application.executionCount() != 1;
            }
            return new ScenarioResult(
                    scenario,
                    LocatorResolutionStatus.RESOLVED,
                    scenario.match() != MatchMode.FUZZY_NAME,
                    scenario.match() == MatchMode.FUZZY_NAME,
                    actionSucceeded,
                    verificationFailed,
                    wrongTarget,
                    reresolved,
                    false,
                    duration,
                    actualTarget,
                    "");
        } catch (AmbiguousLocatorException ambiguous) {
            return failure(
                    scenario, ambiguous.status(), elapsed(started), ambiguous.getMessage(), false);
        } catch (LocatorNotFoundException notFound) {
            return failure(
                    scenario, notFound.status(), elapsed(started), notFound.getMessage(), false);
        } catch (RuntimeException unexpected) {
            return failure(
                    scenario,
                    LocatorResolutionStatus.UNRESOLVABLE,
                    elapsed(started),
                    unexpected.toString(),
                    true);
        }
    }

    private static ILocator<IElement> locator(IPage page, RobustnessScenario scenario) {
        IFind<IElement> find = page.find();
        if (scenario.scoped()) {
            IElement scope =
                    page.find().role(scenario.scopeRole()).named(scenario.scopeName()).single();
            find = scope.find();
        }
        ILocator<IElement> locator = find.role(scenario.role());
        locator =
                switch (scenario.match()) {
                    case EXACT_NAME -> locator.named(scenario.query());
                    case CONTAINING_NAME -> locator.nameContaining(scenario.query());
                    case FUZZY_NAME -> locator.fuzzyName(scenario.query());
                    case LABEL -> locator.labelled(scenario.query());
                };
        if (scenario.visibleOnly()) {
            locator = locator.visible();
        }
        if (scenario.enabledOnly()) {
            locator = locator.enabled();
        }
        if (scenario.clickableOnly()) {
            locator = locator.clickable();
        }
        if (scenario.waitUntilVisible()) {
            locator = locator.waitUntilVisible();
        }
        if (scenario.timeoutMillis() > 0) {
            locator = locator.timeout(Duration.ofMillis(scenario.timeoutMillis()));
        }
        return locator;
    }

    private static ScenarioResult failure(
            RobustnessScenario scenario,
            LocatorResolutionStatus status,
            Duration duration,
            String diagnostics,
            boolean unexpected) {
        return new ScenarioResult(
                scenario,
                status,
                false,
                false,
                false,
                false,
                false,
                false,
                unexpected,
                duration,
                "",
                diagnostics);
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started);
    }

    private static String reproduction(RobustnessScenario scenario) {
        return "Scenario "
                + scenario.id()
                + " failed. Run: ./mvnw -Probustness -Dscenario="
                + scenario.id()
                + " verify";
    }
}
