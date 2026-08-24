package io.webagent4j.browser.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.AmbiguousLocatorException;
import io.webagent4j.locator.ILiveLocatorContext;
import io.webagent4j.locator.ILocatorBackend;
import io.webagent4j.locator.ILocatorEngine;
import io.webagent4j.locator.LocatorConfig;
import io.webagent4j.locator.LocatorContext;
import io.webagent4j.locator.LocatorDiagnostics;
import io.webagent4j.locator.LocatorDiagnosticsLevel;
import io.webagent4j.locator.LocatorResolutionPolicy;
import io.webagent4j.locator.LocatorResult;
import io.webagent4j.locator.LocatorScope;
import io.webagent4j.locator.LocatorStrategyType;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.ILocatorScope;
import io.webagent4j.locator.api.LocatorDefinition;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link PlaywrightFind}/{@link PlaywrightLocator} resolve a mixed chain of explicit-element
 * and structured {@code within(...)} calls in exactly the order they were declared, never grouped
 * by scope kind, and that each subsequent explicit element scope is proven to belong to the scope
 * declared before it. Each test asserts the actual sequence of {@link ILocatorEngine} calls (via
 * the exact {@link LocatorContext} each call receives), not merely that resolution eventually
 * succeeds - a reordered or unvalidated chain can resolve to a plausible-looking, wrong target just
 * as easily as it can fail outright, so only checking the final result would not catch that.
 *
 * <p>See {@link PlaywrightScopeContainmentTest} for containment-failure coverage (foreign element,
 * detached element, self case, stop-on-failure).
 */
class PlaywrightMixedScopeOrderTest {

    @Test
    void resolvesAStructuredScopeThenAnExplicitElementScopeInDeclaredOrder() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext base = pageContext();
        TestElement regionA = element("Product A region");
        TestElement elementB = element("outer-container");
        TestElement target = element("Continue");
        allowDescendantOrSelf(elementB, regionA);

        LocatorContext afterA = base.within(regionA.element());
        LocatorContext afterB = afterA.within(elementB.element());
        when(engine.locateSingle(eq(base), byAriaLabel("Product A"))).thenReturn(result(regionA));
        when(engine.locateSingle(eq(base), byAccessibleName())).thenReturn(result(regionA));
        stubTerminalResolution(engine, afterB, target);

        IElement resolved =
                new PlaywrightFind(engine, base)
                        .within(scope("Product A"))
                        .within(elementB.element())
                        .button()
                        .named("Continue")
                        .single();

        assertThat(resolved).isSameAs(target.element());
        verify(engine).locateSingle(eq(base), byAriaLabel("Product A"));
        verify(engine).locateSingle(eq(base), byAccessibleName());
    }

    @Test
    void resolvesAnExplicitElementScopeThenAStructuredScopeInDeclaredOrder() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext base = pageContext();
        TestElement elementA = element("outer-container");
        TestElement regionB = element("Available region");
        TestElement target = element("Ajouter");

        LocatorContext afterA = base.within(elementA.element());
        LocatorContext afterB = afterA.within(regionB.element());
        when(engine.locateSingle(eq(afterA), byAriaLabel("Available"))).thenReturn(result(regionB));
        when(engine.locateSingle(eq(afterA), byAccessibleName())).thenReturn(result(regionB));
        stubTerminalResolution(engine, afterB, target);

        IElement resolved =
                new PlaywrightFind(engine, base)
                        .within(elementA.element())
                        .within(scope("Available"))
                        .button()
                        .named("Ajouter")
                        .single();

        assertThat(resolved).isSameAs(target.element());
        verify(engine).locateSingle(eq(afterA), byAriaLabel("Available"));
        verify(engine).locateSingle(eq(afterA), byAccessibleName());
        verify(engine, never()).locateSingle(eq(base), byAriaLabel("Available"));
    }

    @Test
    void resolvesAThreeLevelMixedChainInDeclaredOrder() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext base = pageContext();
        TestElement regionA = element("Product A region");
        TestElement elementB = element("outer-container");
        TestElement regionC = element("Available region");
        TestElement target = element("Ajouter");
        allowDescendantOrSelf(elementB, regionA);

        LocatorContext afterA = base.within(regionA.element());
        LocatorContext afterB = afterA.within(elementB.element());
        LocatorContext afterC = afterB.within(regionC.element());
        when(engine.locateSingle(eq(base), byAriaLabel("Product A"))).thenReturn(result(regionA));
        when(engine.locateSingle(eq(base), byAccessibleName())).thenReturn(result(regionA));
        when(engine.locateSingle(eq(afterB), byAriaLabel("Available"))).thenReturn(result(regionC));
        when(engine.locateSingle(eq(afterB), byAccessibleName())).thenReturn(result(regionC));
        stubTerminalResolution(engine, afterC, target);

        IElement resolved =
                new PlaywrightFind(engine, base)
                        .within(scope("Product A"))
                        .within(elementB.element())
                        .within(scope("Available"))
                        .button()
                        .named("Ajouter")
                        .single();

        assertThat(resolved).isSameAs(target.element());
        verify(engine).locateSingle(eq(base), byAriaLabel("Product A"));
        verify(engine).locateSingle(eq(base), byAccessibleName());
        verify(engine).locateSingle(eq(afterB), byAriaLabel("Available"));
        verify(engine).locateSingle(eq(afterB), byAccessibleName());
    }

    @Test
    void preservesOrderWhenWithinIsCalledAgainAfterATerminalRoleSelector() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext base = pageContext();
        TestElement regionA = element("Product A region");
        TestElement elementB = element("outer-container");
        TestElement target = element("Continue");
        allowDescendantOrSelf(elementB, regionA);

        LocatorContext afterA = base.within(regionA.element());
        LocatorContext afterB = afterA.within(elementB.element());
        when(engine.locateSingle(eq(base), byAriaLabel("Product A"))).thenReturn(result(regionA));
        when(engine.locateSingle(eq(base), byAccessibleName())).thenReturn(result(regionA));
        stubTerminalResolution(engine, afterB, target);

        IElement resolved =
                new PlaywrightFind(engine, base)
                        .within(scope("Product A"))
                        .button()
                        .within(elementB.element())
                        .named("Continue")
                        .single();

        assertThat(resolved).isSameAs(target.element());
        verify(engine).locateSingle(eq(base), byAriaLabel("Product A"));
        verify(engine).locateSingle(eq(base), byAccessibleName());
    }

    @Test
    void stopsTheChainWhenAnIntermediateStructuredScopeIsAmbiguous() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext base = pageContext();
        TestElement elementA = element("outer-container");
        AmbiguousLocatorException ambiguous = new AmbiguousLocatorException("two \"Available\"");
        LocatorContext afterA = base.within(elementA.element());
        when(engine.locateSingle(eq(afterA), byAriaLabel("Available"))).thenThrow(ambiguous);
        when(engine.locateSingle(any(ILiveLocatorContext.class), any()))
                .thenAnswer(
                        invocation -> {
                            ((ILiveLocatorContext) invocation.getArgument(0)).resolve();
                            throw new AssertionError(
                                    "the target definition must never be reached after an"
                                            + " ambiguous scope");
                        });

        var locator =
                new PlaywrightFind(engine, base)
                        .within(elementA.element())
                        .within(scope("Available"))
                        .within(element("never reached").element())
                        .button()
                        .named("Ajouter");

        org.assertj.core.api.Assertions.assertThatRuntimeException()
                .isThrownBy(locator::single)
                .isSameAs(ambiguous);

        verify(engine, never())
                .locateSingle(any(LocatorContext.class), argThat(d -> d.role().isPresent()));
    }

    private static void stubTerminalResolution(
            ILocatorEngine engine, LocatorContext expectedContext, TestElement expectedResult) {
        when(engine.locateSingle(any(ILiveLocatorContext.class), any()))
                .thenAnswer(
                        invocation -> {
                            LocatorContext resolved =
                                    ((ILiveLocatorContext) invocation.getArgument(0)).resolve();
                            assertThat(resolved).isEqualTo(expectedContext);
                            return result(expectedResult);
                        });
    }

    static ILocatorScope<IElement> scope(String... containingText) {
        List<String> values = Arrays.asList(containingText);
        return new ILocatorScope<>() {
            @Override
            public Optional<IElement> scopeElement() {
                return Optional.empty();
            }

            @Override
            public List<String> containingText() {
                return values;
            }
        };
    }

    static LocatorDefinition byAriaLabel(String value) {
        return argThat(definition -> value.equals(definition.attributes().get("aria-label")));
    }

    static LocatorDefinition byAccessibleName() {
        return argThat(definition -> definition.accessibleName().isPresent());
    }

    static LocatorContext pageContext() {
        ILocatorBackend backend =
                (query, scope, config, timeout, limit) -> {
                    throw new UnsupportedOperationException("backend must not be invoked directly");
                };
        return LocatorContext.page(backend, LocatorConfig.builder().build());
    }

    static LocatorResult result(TestElement element) {
        LocatorDefinition definition = LocatorDefinition.element();
        LocatorDiagnostics diagnostics =
                new LocatorDiagnostics(
                        definition,
                        LocatorResolutionPolicy.BALANCED,
                        LocatorDiagnosticsLevel.BASIC,
                        List.of("Page"),
                        List.of(),
                        List.of(),
                        1,
                        0,
                        0,
                        List.of(),
                        1,
                        0,
                        Optional.empty(),
                        Duration.ZERO,
                        false,
                        Set.of(),
                        Optional.empty(),
                        List.of());
        return new LocatorResult(
                definition,
                element.element(),
                LocatorStrategyType.ACCESSIBLE_NAME,
                1.0,
                1.0,
                true,
                List.of(),
                diagnostics);
    }

    /** A {@link PlaywrightElement} with its underlying mocked locator and handle exposed. */
    record TestElement(PlaywrightElement element, Locator locator, ElementHandle handle) {}

    /** Creates a present test element backed by a mocked live Locator. */
    static TestElement element(String accessibleName) {
        Locator locator = mock(Locator.class);
        ElementHandle containmentHandle = mock(ElementHandle.class);
        ElementHandle inspectionHandle = mock(ElementHandle.class);

        when(locator.count()).thenReturn(1);
        when(locator.elementHandle()).thenReturn(containmentHandle);
        when(locator.elementHandles()).thenReturn(List.of(inspectionHandle));
        when(inspectionHandle.evaluate(anyString(), isNull())).thenReturn(accessibleName);

        PlaywrightElement wrapped =
                new PlaywrightElement(
                        locator,
                        ElementRole.REGION,
                        null,
                        LocatorScope.page(),
                        LocatorConfig.builder().build());

        return new TestElement(wrapped, locator, containmentHandle);
    }

    /** Stubs {@code child}'s DOM containment check to report it is inside {@code parent}. */
    static void allowDescendantOrSelf(TestElement child, TestElement parent) {
        when(child.locator()
                        .evaluate(
                                eq(PlaywrightDomInspectionScripts.DESCENDANT_OR_SELF_FUNCTION),
                                eq(parent.handle())))
                .thenReturn(true);
    }

    /** Stubs {@code child}'s DOM containment check to report it is not inside {@code parent}. */
    static void denyDescendantOrSelf(TestElement child, TestElement parent) {
        when(child.locator()
                        .evaluate(
                                eq(PlaywrightDomInspectionScripts.DESCENDANT_OR_SELF_FUNCTION),
                                eq(parent.handle())))
                .thenReturn(false);
    }
}
