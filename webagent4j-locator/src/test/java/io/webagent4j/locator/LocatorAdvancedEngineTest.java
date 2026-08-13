package io.webagent4j.locator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.dom.IElement;
import io.webagent4j.locator.LocatorDiagnostics.BudgetLimit;
import io.webagent4j.locator.LocatorTestFixtures.FakeBackend;
import io.webagent4j.locator.LocatorTestFixtures.TestElement;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.LocatorDefinition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LocatorAdvancedEngineTest {

    @Test
    void ranksAccessibleExactAheadOfVisibleTextAndDeduplicatesEvidence() {
        IElement semantic =
                new TestElement(
                        ElementRole.BUTTON, "Checkout", "Proceed", "button", Map.of(), true, true);
        IElement textOnly =
                new TestElement(
                        ElementRole.BUTTON, "Other", "Checkout", "button", Map.of(), true, true);
        LocatorResult result =
                new LocatorEngine()
                        .locate(
                                LocatorContext.page(
                                        new FakeBackend(List.of(textOnly, semantic)),
                                        LocatorConfig.builder()
                                                .scoring(lowScoring())
                                                .resolutionBudget(shortBudget())
                                                .build()),
                                LocatorDefinition.forRole(ElementRole.BUTTON).named("Checkout"));

        assertThat(result.element()).isSameAs(semantic);
        assertThat(result.candidates()).hasSize(2);
        assertThat(result.candidates().get(0).hasEvidence(LocatorStrategyType.ACCESSIBLE_NAME))
                .isTrue();
        assertThat(result.candidates().get(1).hasEvidence(LocatorStrategyType.VISIBLE_TEXT))
                .isTrue();
        assertThat(result.diagnostics().candidatesDeduplicated()).isPositive();
    }

    @Test
    void usesVisibleEnabledAndDocumentOrderAsDeterministicTieBreakers() {
        IElement hidden = element("Add", false, true);
        IElement disabled = element("Add", true, false);
        IElement firstVisible = element("Add", true, true);
        IElement secondVisible = element("Add", true, true);
        FakeBackend backend =
                new FakeBackend(List.of(hidden, disabled, firstVisible, secondVisible));
        LocatorScoringConfig scoring = lowScoring();
        LocatorContext context =
                LocatorContext.page(
                        backend,
                        LocatorConfig.builder()
                                .scoring(scoring)
                                .resolutionBudget(shortBudget())
                                .build());
        LocatorDefinition definition = LocatorDefinition.forRole(ElementRole.BUTTON).named("Add");

        List<List<String>> repeatedOrders = new ArrayList<>();
        List<String> repeatedExplanations = new ArrayList<>();
        for (int attempt = 0; attempt < 5; attempt++) {
            LocatorResult result = new LocatorEngine().locate(context, definition);
            repeatedOrders.add(
                    result.candidates().stream().map(LocatorCandidate::identity).toList());
            repeatedExplanations.add(result.explain());
        }

        assertThat(repeatedOrders).allMatch(order -> order.equals(repeatedOrders.get(0)));
        assertThat(repeatedExplanations)
                .allMatch(explanation -> explanation.equals(repeatedExplanations.get(0)));
        assertThat(repeatedOrders.get(0).get(0)).isEqualTo("candidate-2");
    }

    @Test
    void appliesConfigurableAmbiguityMarginWithoutHidingFirstContract() {
        IElement enabled = element("Add", true, true);
        IElement disabled = element("Add", true, false);
        LocatorDefinition definition = LocatorDefinition.forRole(ElementRole.BUTTON).named("Add");
        LocatorScoringConfig scoring = lowScoring();
        LocatorConfig wideMargin =
                LocatorConfig.builder()
                        .ambiguityMargin(0.05)
                        .scoring(scoring)
                        .resolutionBudget(shortBudget())
                        .build();
        LocatorConfig narrowMargin =
                LocatorConfig.builder()
                        .ambiguityMargin(0.01)
                        .scoring(scoring)
                        .resolutionBudget(shortBudget())
                        .build();

        FakeBackend backend = new FakeBackend(List.of(disabled, enabled));
        assertThatThrownBy(
                        () ->
                                new LocatorEngine()
                                        .locateSingle(
                                                LocatorContext.page(backend, wideMargin),
                                                definition))
                .isInstanceOf(AmbiguousLocatorException.class)
                .satisfies(
                        error ->
                                assertThat(((AmbiguousLocatorException) error).diagnostics())
                                        .isPresent());
        assertThat(
                        new LocatorEngine()
                                .locate(LocatorContext.page(backend, wideMargin), definition)
                                .element())
                .isSameAs(enabled);
        assertThat(
                        new LocatorEngine()
                                .locateSingle(
                                        LocatorContext.page(backend, narrowMargin), definition)
                                .element())
                .isSameAs(enabled);
    }

    @Test
    void enforcesStrictBalancedAndPermissivePolicies() {
        IElement target = element("Ajouter au panier", true, true);
        LocatorDefinition definition =
                LocatorDefinition.forRole(ElementRole.BUTTON).fuzzyName("Ajouter panier");

        FakeBackend strictBackend = new FakeBackend(List.of(target));
        LocatorConfig strict =
                LocatorConfig.builder()
                        .resolutionPolicy(LocatorResolutionPolicy.STRICT)
                        .resolutionBudget(shortBudget())
                        .build();
        assertThatThrownBy(
                        () ->
                                new LocatorEngine()
                                        .locate(
                                                LocatorContext.page(strictBackend, strict),
                                                definition))
                .isInstanceOf(LocatorNotFoundException.class);
        assertThat(strictBackend.queries())
                .extracting(LocatorBackendQuery::strategy)
                .doesNotContain(LocatorStrategyType.FUZZY_TEXT);

        for (LocatorResolutionPolicy policy :
                List.of(LocatorResolutionPolicy.BALANCED, LocatorResolutionPolicy.PERMISSIVE)) {
            LocatorConfig config =
                    LocatorConfig.builder()
                            .resolutionPolicy(policy)
                            .resolutionBudget(shortBudget())
                            .build();
            LocatorResult result =
                    new LocatorEngine()
                            .locate(
                                    LocatorContext.page(new FakeBackend(List.of(target)), config),
                                    definition);
            assertThat(result.strategy()).isEqualTo(LocatorStrategyType.FUZZY_TEXT);
            assertThat(result.exactMatch()).isFalse();
            assertThat(result.diagnostics().resolutionPolicy()).isEqualTo(policy);
        }
    }

    @Test
    void respectsCandidateAndStrategyBudgetsAndReportsReachedLimits() {
        List<IElement> many = new ArrayList<>();
        for (int index = 0; index < 150; index++) {
            many.add(element("Repeated", true, true));
        }
        LocatorConfig config =
                LocatorConfig.builder()
                        .resolutionBudget(
                                new LocatorResolutionBudget(Duration.ofMillis(100), 10, 1, 5))
                        .diagnosticsLevel(LocatorDiagnosticsLevel.DETAILED)
                        .build();
        LocatorResult result =
                new LocatorEngine()
                        .locate(
                                LocatorContext.page(new FakeBackend(many), config),
                                LocatorDefinition.forRole(ElementRole.BUTTON).named("Repeated"));

        assertThat(result.candidates()).hasSize(10);
        assertThat(result.diagnostics().budgetReached()).isTrue();
        assertThat(result.diagnostics().reachedLimits())
                .contains(BudgetLimit.CANDIDATES, BudgetLimit.STRATEGIES);
        assertThat(result.diagnostics().strategiesExecuted()).hasSize(1);
    }

    @Test
    void waitsForContinuousIdentityAndStateStabilityWithoutSleeping() {
        LocatorConfig config =
                LocatorConfig.builder()
                        .resolutionBudget(
                                new LocatorResolutionBudget(Duration.ofMillis(300), 20, 10, 10))
                        .pollingInterval(Duration.ofMillis(5))
                        .build();
        LocatorResult result =
                new LocatorEngine()
                        .locate(
                                LocatorContext.page(
                                        new FakeBackend(List.of(element("Confirm", true, true))),
                                        config),
                                LocatorDefinition.forRole(ElementRole.BUTTON)
                                        .named("Confirm")
                                        .visibleOnly()
                                        .stableFor(Duration.ofMillis(35)));

        assertThat(result.diagnostics().duration()).isGreaterThanOrEqualTo(Duration.ofMillis(30));
        assertThat(result.element().visible()).isTrue();
    }

    @Test
    void emitsStructuredLifecycleEventsThroughTheInjectedListener() {
        List<ILocatorEvent> events = new ArrayList<>();
        LocatorEngine engine =
                new LocatorEngine(
                        LocatorStrategyRegistry.defaults(),
                        new LocatorPlanFactory(),
                        new LocatorFilter(),
                        new LocatorScorer(),
                        new LocatorDiagnosticsRenderer(),
                        events::add);

        engine.locate(
                context(new FakeBackend(List.of(element("Save", true, true)))),
                LocatorDefinition.forRole(ElementRole.BUTTON).named("Save"));

        assertThat(events)
                .anyMatch(ILocatorEvent.ResolutionStarted.class::isInstance)
                .anyMatch(ILocatorEvent.StrategyExecuted.class::isInstance)
                .anyMatch(ILocatorEvent.CandidateFound.class::isInstance)
                .anyMatch(ILocatorEvent.ResolutionCompleted.class::isInstance);
    }

    @Test
    void integratesCustomStrategiesWithoutChangingTheEngine() {
        IElement customTarget = element("Plugin target", true, true);
        ILocatorStrategy custom =
                new ILocatorStrategy() {
                    @Override
                    public LocatorStrategyType type() {
                        return LocatorStrategyType.CUSTOM;
                    }

                    @Override
                    public String id() {
                        return "test-custom";
                    }

                    @Override
                    public boolean supports(LocatorDefinition definition) {
                        return definition.role().orElse(ElementRole.UNKNOWN) == ElementRole.BUTTON;
                    }

                    @Override
                    public LocatorBackendSearchResult discover(
                            LocatorDefinition definition,
                            LocatorPlanStep step,
                            LocatorContext context,
                            Duration timeout,
                            int candidateLimit,
                            List<LocatorCandidate> existingCandidates) {
                        return LocatorBackendSearchResult.complete(
                                List.of(new LocatorBackendCandidate("custom-1", customTarget, 0)));
                    }
                };
        List<ILocatorStrategy> strategies =
                new ArrayList<>(LocatorStrategyRegistry.defaults().strategies());
        strategies.add(custom);

        LocatorResult result =
                new LocatorEngine(
                                new LocatorStrategyRegistry(strategies),
                                new LocatorPlanFactory(),
                                new LocatorFilter(),
                                new LocatorScorer(),
                                new LocatorDiagnosticsRenderer())
                        .locate(
                                context(new FakeBackend(List.of())),
                                LocatorDefinition.forRole(ElementRole.BUTTON));

        assertThat(result.element()).isSameAs(customTarget);
        assertThat(result.strategy()).isEqualTo(LocatorStrategyType.CUSTOM);
    }

    @Test
    void skipsStrategiesNotDeclaredByBackendCapabilities() {
        ILocatorBackend limitedBackend =
                new ILocatorBackend() {
                    @Override
                    public LocatorBackendCapabilities capabilities() {
                        return new LocatorBackendCapabilities(
                                EnumSet.of(LocatorStrategyType.ROLE), Set.of());
                    }

                    @Override
                    public LocatorBackendSearchResult find(
                            LocatorBackendQuery query,
                            LocatorScope scope,
                            LocatorConfig config,
                            Duration timeout,
                            int candidateLimit) {
                        return LocatorBackendSearchResult.complete(List.of());
                    }
                };

        assertThatThrownBy(
                        () ->
                                new LocatorEngine()
                                        .locate(
                                                LocatorContext.page(
                                                        limitedBackend,
                                                        LocatorConfig.builder()
                                                                .resolutionBudget(shortBudget())
                                                                .build()),
                                                LocatorDefinition.forRole(ElementRole.BUTTON)
                                                        .named("Missing")))
                .isInstanceOf(LocatorNotFoundException.class)
                .satisfies(
                        error ->
                                assertThat(
                                                ((LocatorNotFoundException) error)
                                                        .diagnostics()
                                                        .orElseThrow()
                                                        .strategiesSkipped())
                                        .isNotEmpty());
    }

    private static TestElement element(String name, boolean visible, boolean enabled) {
        return new TestElement(
                ElementRole.BUTTON, name, name, "button", Map.of(), visible, enabled);
    }

    private static LocatorContext context(FakeBackend backend) {
        return LocatorContext.page(
                backend, LocatorConfig.builder().resolutionBudget(shortBudget()).build());
    }

    private static LocatorResolutionBudget shortBudget() {
        return new LocatorResolutionBudget(Duration.ofMillis(60), 100, 10, 50);
    }

    private static LocatorScoringConfig lowScoring() {
        return new LocatorScoringConfig(0.10, 0.20, 0.10, 0.05, 0.20, 0.20, 0.10, 0.04, 0.0);
    }
}
