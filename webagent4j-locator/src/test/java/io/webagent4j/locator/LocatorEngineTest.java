package io.webagent4j.locator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.dom.IElement;
import io.webagent4j.locator.LocatorTestFixtures.FakeBackend;
import io.webagent4j.locator.LocatorTestFixtures.TestElement;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.ILocator;
import io.webagent4j.locator.api.LocatorDefinition;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocatorEngineTest {

    private final LocatorEngine engine = new LocatorEngine();

    @Test
    void selectsExactAccessibleNameBeforeFuzzyFallback() {
        IElement wrongRole = LocatorTestFixtures.element(ElementRole.LINK, "Sign in");
        IElement target = LocatorTestFixtures.element(ElementRole.BUTTON, "SIGN IN");
        FakeBackend backend = new FakeBackend(List.of(wrongRole, target));

        LocatorResult result =
                engine.locate(
                        context(backend),
                        LocatorDefinition.forRole(ElementRole.BUTTON).named("Sign in"));

        assertThat(result.element()).isSameAs(target);
        assertThat(result.strategy()).isEqualTo(LocatorStrategyType.ACCESSIBLE_NAME);
        assertThat(result.confidence()).isEqualTo(1.0);
        assertThat(backend.queries())
                .extracting(LocatorBackendQuery::strategy)
                .containsExactly(LocatorStrategyType.ACCESSIBLE_NAME, LocatorStrategyType.LABEL);
        assertThat(result.candidates().get(0).evidence())
                .extracting(LocatorEvidence::strategy)
                .contains(
                        LocatorStrategyType.ROLE,
                        LocatorStrategyType.ACCESSIBLE_NAME,
                        LocatorStrategyType.LABEL);
        assertThat(result.diagnostics().candidatesDeduplicated()).isEqualTo(1);
        assertThat(result.explain()).contains("Requested:", "Candidates:", "score=1.00");
    }

    @Test
    void appliesVisibilityEnabledAndAttributeFilters() {
        IElement hidden =
                new TestElement(
                        ElementRole.BUTTON,
                        "Pay",
                        "Pay",
                        "button",
                        Map.of("data-kind", "primary"),
                        false,
                        true);
        IElement disabled =
                new TestElement(
                        ElementRole.BUTTON,
                        "Pay",
                        "Pay",
                        "button",
                        Map.of("data-kind", "primary"),
                        true,
                        false);
        IElement target =
                new TestElement(
                        ElementRole.BUTTON,
                        "Pay",
                        "Pay",
                        "button",
                        Map.of("data-kind", "primary"),
                        true,
                        true);
        LocatorDefinition definition =
                LocatorDefinition.forRole(ElementRole.BUTTON)
                        .named("Pay")
                        .withAttribute("data-kind", "primary")
                        .visibleOnly()
                        .enabledOnly();

        LocatorResult result =
                engine.locate(
                        context(new FakeBackend(List.of(hidden, disabled, target))), definition);

        assertThat(result.element()).isSameAs(target);
    }

    @Test
    void tryFindReturnsTheSingleMatchingTarget() {
        IElement target = LocatorTestFixtures.element(ElementRole.BUTTON, "Add");
        FakeBackend backend = new FakeBackend(List.of(target));
        ILocator<IElement> locator =
                locator(
                        context(backend),
                        LocatorDefinition.forRole(ElementRole.BUTTON).named("Add"));

        assertThat(locator.tryFind()).contains(target);
    }

    @Test
    void tryFindReturnsEmptyWhenTargetIsMissing() {
        FakeBackend backend = new FakeBackend(List.of());
        ILocator<IElement> locator =
                locator(
                        context(backend),
                        LocatorDefinition.forRole(ElementRole.BUTTON).named("Missing"));

        assertThat(locator.tryFind()).isEmpty();
    }

    @Test
    void tryFindDoesNotHideAmbiguity() {
        FakeBackend backend =
                new FakeBackend(
                        List.of(
                                LocatorTestFixtures.element(ElementRole.BUTTON, "Add"),
                                LocatorTestFixtures.element(ElementRole.BUTTON, "Add")));
        ILocator<IElement> locator =
                locator(
                        context(backend),
                        LocatorDefinition.forRole(ElementRole.BUTTON).named("Add"));

        assertThatThrownBy(locator::tryFind)
                .isInstanceOf(AmbiguousLocatorException.class)
                .hasMessageContaining("1. BUTTON")
                .hasMessageContaining("score=1.00");
    }

    @Test
    void detectsAmbiguityForEquivalentSingleCandidates() {
        FakeBackend backend =
                new FakeBackend(
                        List.of(
                                LocatorTestFixtures.element(ElementRole.BUTTON, "Add"),
                                LocatorTestFixtures.element(ElementRole.BUTTON, "Add")));

        assertThatThrownBy(
                        () ->
                                engine.locateSingle(
                                        context(backend),
                                        LocatorDefinition.forRole(ElementRole.BUTTON).named("Add")))
                .isInstanceOf(AmbiguousLocatorException.class)
                .hasMessageContaining("1. BUTTON")
                .hasMessageContaining("score=1.00");
    }

    @Test
    void reportsNotFoundAfterTheConfiguredTimeout() {
        assertThatThrownBy(
                        () ->
                                engine.locate(
                                        context(new FakeBackend(List.of())),
                                        LocatorDefinition.forRole(ElementRole.BUTTON)
                                                .named("Missing")))
                .isInstanceOf(LocatorNotFoundException.class)
                .hasMessageContaining("No element matched")
                .hasMessageContaining("Candidates:");
    }

    @Test
    void usesFuzzyOnlyWhenExactStrategiesFail() {
        IElement target = LocatorTestFixtures.element(ElementRole.BUTTON, "Ajouter au panier");
        FakeBackend backend = new FakeBackend(List.of(target));

        LocatorResult result =
                engine.locate(
                        context(backend),
                        LocatorDefinition.forRole(ElementRole.BUTTON).fuzzyName("Ajouter panier"));

        assertThat(result.element()).isSameAs(target);
        assertThat(result.strategy()).isEqualTo(LocatorStrategyType.FUZZY_TEXT);
        assertThat(backend.queries())
                .extracting(LocatorBackendQuery::strategy)
                .containsSequence(
                        LocatorStrategyType.ACCESSIBLE_NAME,
                        LocatorStrategyType.LABEL,
                        LocatorStrategyType.VISIBLE_TEXT,
                        LocatorStrategyType.FUZZY_TEXT);
    }

    @Test
    void resolvesAllInDomOrderAndReusesTheSameEngineForScope() {
        IElement form = LocatorTestFixtures.element(ElementRole.FORM, "Payment");
        IElement first = LocatorTestFixtures.element(ElementRole.BUTTON, "Pay");
        IElement second = LocatorTestFixtures.element(ElementRole.BUTTON, "Pay");
        FakeBackend backend = new FakeBackend(List.of(form), List.of(first, second));
        LocatorContext scoped = context(backend).within(form);

        List<LocatorCandidate> results =
                engine.locateAll(
                        scoped, LocatorDefinition.forRole(ElementRole.BUTTON).named("Pay"));

        assertThat(results).extracting(LocatorCandidate::element).containsExactly(first, second);
        assertThat(backend.queries()).isNotEmpty();
    }

    private ILocator<IElement> locator(LocatorContext context, LocatorDefinition definition) {
        return new ILocator<>() {
            @Override
            public ILocator<IElement> named(String name) {
                return locator(context, definition.named(name));
            }

            @Override
            public ILocator<IElement> nameContaining(String text) {
                return locator(context, definition.nameContaining(text));
            }

            @Override
            public ILocator<IElement> fuzzyName(String name) {
                return locator(context, definition.fuzzyName(name));
            }

            @Override
            public ILocator<IElement> labelled(String label) {
                return locator(context, definition.labelled(label));
            }

            @Override
            public ILocator<IElement> visible() {
                return locator(context, definition.visibleOnly());
            }

            @Override
            public ILocator<IElement> hidden() {
                return locator(context, definition.hiddenOnly());
            }

            @Override
            public ILocator<IElement> enabled() {
                return locator(context, definition.enabledOnly());
            }

            @Override
            public ILocator<IElement> disabled() {
                return locator(context, definition.disabledOnly());
            }

            @Override
            public ILocator<IElement> editable() {
                return locator(context, definition.editableOnly());
            }

            @Override
            public ILocator<IElement> readonly() {
                return locator(context, definition.readOnlyOnly());
            }

            @Override
            public ILocator<IElement> checked() {
                return locator(context, definition.checkedOnly());
            }

            @Override
            public ILocator<IElement> selected() {
                return locator(context, definition.selectedOnly());
            }

            @Override
            public ILocator<IElement> focused() {
                return locator(context, definition.focusedOnly());
            }

            @Override
            public ILocator<IElement> inViewport() {
                return locator(context, definition.inViewportOnly());
            }

            @Override
            public ILocator<IElement> clickable() {
                return locator(context, definition.clickableOnly());
            }

            @Override
            public ILocator<IElement> covered() {
                return locator(context, definition.coveredOnly());
            }

            @Override
            public ILocator<IElement> timeout(Duration timeout) {
                return locator(context, definition.withTimeout(timeout));
            }

            @Override
            public ILocator<IElement> waitUntilVisible() {
                return locator(context, definition.waitingUntilVisible());
            }

            @Override
            public ILocator<IElement> stableFor(Duration duration) {
                return locator(context, definition.stableFor(duration));
            }

            @Override
            public io.webagent4j.locator.api.IElementReference<IElement> reference() {
                return () -> engine.locateSingle(context, definition).element();
            }

            @Override
            public IElement first() {
                return engine.locate(context, definition).element();
            }

            @Override
            public IElement single() {
                return engine.locateSingle(context, definition).element();
            }

            @Override
            public List<IElement> all() {
                return engine.locateAll(context, definition).stream()
                        .map(LocatorCandidate::element)
                        .toList();
            }
        };
    }

    private static LocatorContext context(FakeBackend backend) {
        LocatorConfig config =
                new LocatorConfig(
                        0.80,
                        20,
                        Duration.ofMillis(2),
                        true,
                        true,
                        0.02,
                        LocatorScoringConfig.defaults());
        return LocatorContext.page(backend, config);
    }
}
