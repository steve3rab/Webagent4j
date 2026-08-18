package io.webagent4j.browser.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.webagent4j.dom.IElement;
import io.webagent4j.locator.AmbiguousLocatorException;
import io.webagent4j.locator.ILocatorBackend;
import io.webagent4j.locator.ILocatorEngine;
import io.webagent4j.locator.LocatorConfig;
import io.webagent4j.locator.LocatorContext;
import io.webagent4j.locator.LocatorDiagnostics;
import io.webagent4j.locator.LocatorDiagnosticsLevel;
import io.webagent4j.locator.LocatorResolutionPolicy;
import io.webagent4j.locator.LocatorResult;
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
 * by scope kind. Each test asserts the actual sequence of {@link ILocatorEngine} calls (via the
 * exact {@link LocatorContext} each call receives), not merely that resolution eventually succeeds
 * - a reordered chain can resolve to a plausible-looking, wrong target just as easily as it can
 * fail outright, so only checking the final result would not catch that.
 */
class PlaywrightMixedScopeOrderTest {

    @Test
    void resolvesAStructuredScopeThenAnExplicitElementScopeInDeclaredOrder() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext base = pageContext();
        IElement regionA = element("Product A region");
        IElement elementB = element("outer-container");
        IElement target = element("Continue");

        LocatorContext afterA = base.within(regionA);
        LocatorContext afterB = afterA.within(elementB);
        when(engine.locateSingle(eq(base), byAccessibleName())).thenReturn(result(regionA));
        when(engine.locateSingle(eq(afterB), any())).thenReturn(result(target));

        IElement resolved =
                new PlaywrightFind(engine, base)
                        .within(scope("Product A"))
                        .within(elementB)
                        .button()
                        .named("Continue")
                        .single();

        assertThat(resolved).isSameAs(target);
        // scopeA resolved from the untouched base context - not from a context already narrowed
        // by elementB, which is what the previous (buggy) eager-element implementation produced.
        verify(engine).locateSingle(eq(base), byAccessibleName());
        // the final target is resolved inside base -> Product A -> outer-container, in that order.
        verify(engine).locateSingle(eq(afterB), any());
    }

    @Test
    void resolvesAnExplicitElementScopeThenAStructuredScopeInDeclaredOrder() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext base = pageContext();
        IElement elementA = element("outer-container");
        IElement regionB = element("Available region");
        IElement target = element("Ajouter");

        LocatorContext afterA = base.within(elementA);
        LocatorContext afterB = afterA.within(regionB);
        when(engine.locateSingle(eq(afterA), byAccessibleName())).thenReturn(result(regionB));
        when(engine.locateSingle(eq(afterB), any())).thenReturn(result(target));

        IElement resolved =
                new PlaywrightFind(engine, base)
                        .within(elementA)
                        .within(scope("Available"))
                        .button()
                        .named("Ajouter")
                        .single();

        assertThat(resolved).isSameAs(target);
        // the structured scope is resolved from base -> outer-container, not from the untouched
        // base context: elementA is applied first, exactly as declared.
        verify(engine).locateSingle(eq(afterA), byAccessibleName());
        verify(engine, never()).locateSingle(eq(base), byAccessibleName());
        verify(engine).locateSingle(eq(afterB), any());
    }

    @Test
    void resolvesAThreeLevelMixedChainInDeclaredOrder() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext base = pageContext();
        IElement regionA = element("Product A region");
        IElement elementB = element("outer-container");
        IElement regionC = element("Available region");
        IElement target = element("Ajouter");

        LocatorContext afterA = base.within(regionA);
        LocatorContext afterB = afterA.within(elementB);
        LocatorContext afterC = afterB.within(regionC);
        when(engine.locateSingle(eq(base), byAccessibleName())).thenReturn(result(regionA));
        when(engine.locateSingle(eq(afterB), byAccessibleName())).thenReturn(result(regionC));
        when(engine.locateSingle(eq(afterC), any())).thenReturn(result(target));

        IElement resolved =
                new PlaywrightFind(engine, base)
                        .within(scope("Product A"))
                        .within(elementB)
                        .within(scope("Available"))
                        .button()
                        .named("Ajouter")
                        .single();

        assertThat(resolved).isSameAs(target);
        verify(engine).locateSingle(eq(base), byAccessibleName());
        verify(engine).locateSingle(eq(afterB), byAccessibleName());
        verify(engine).locateSingle(eq(afterC), any());
    }

    @Test
    void preservesOrderWhenWithinIsCalledAgainAfterATerminalRoleSelector() {
        // within(...) is also callable on ILocator itself (after .button(), etc.), not only on
        // IFind - both must feed the same single ordered chain.
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext base = pageContext();
        IElement regionA = element("Product A region");
        IElement elementB = element("outer-container");
        IElement target = element("Continue");

        LocatorContext afterA = base.within(regionA);
        LocatorContext afterB = afterA.within(elementB);
        when(engine.locateSingle(eq(base), byAccessibleName())).thenReturn(result(regionA));
        when(engine.locateSingle(eq(afterB), any())).thenReturn(result(target));

        IElement resolved =
                new PlaywrightFind(engine, base)
                        .within(scope("Product A"))
                        .button()
                        .within(elementB)
                        .named("Continue")
                        .single();

        assertThat(resolved).isSameAs(target);
        verify(engine).locateSingle(eq(base), byAccessibleName());
        verify(engine).locateSingle(eq(afterB), any());
    }

    @Test
    void stopsTheChainWhenAnIntermediateStructuredScopeIsAmbiguous() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext base = pageContext();
        IElement elementA = element("outer-container");
        AmbiguousLocatorException ambiguous = new AmbiguousLocatorException("two \"Available\"");
        LocatorContext afterA = base.within(elementA);
        when(engine.locateSingle(eq(afterA), byAccessibleName())).thenThrow(ambiguous);

        var locator =
                new PlaywrightFind(engine, base)
                        .within(elementA)
                        .within(scope("Available"))
                        .within(element("never reached"))
                        .button()
                        .named("Ajouter");

        org.assertj.core.api.Assertions.assertThatRuntimeException()
                .isThrownBy(locator::single)
                .isSameAs(ambiguous);
        // The scope after the failed one, and the final target, must never be evaluated.
        verify(engine, never()).locateSingle(any(), argThat(d -> d.role().isPresent()));
    }

    private static ILocatorScope<IElement> scope(String... containingText) {
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

    private static LocatorDefinition byAccessibleName() {
        return argThat(definition -> definition.accessibleName().isPresent());
    }

    private static LocatorContext pageContext() {
        ILocatorBackend backend =
                (query, scope, config, timeout, limit) -> {
                    throw new UnsupportedOperationException("backend must not be invoked directly");
                };
        return LocatorContext.page(backend, LocatorConfig.builder().build());
    }

    private static LocatorResult result(IElement element) {
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
                element,
                LocatorStrategyType.ACCESSIBLE_NAME,
                1.0,
                1.0,
                true,
                List.of(),
                diagnostics);
    }

    private static IElement element(String name) {
        IElement element = mock(IElement.class);
        when(element.role()).thenReturn(ElementRole.REGION);
        when(element.accessibleName()).thenReturn(name);
        return element;
    }
}
