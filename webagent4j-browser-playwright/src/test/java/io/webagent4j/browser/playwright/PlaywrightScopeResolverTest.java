package io.webagent4j.browser.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatRuntimeException;
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
import io.webagent4j.locator.LocatorNotFoundException;
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
 * Proves {@link PlaywrightScopeResolver}'s two safety-critical contracts: the accessible-name ->
 * visible-text fallback only ever triggers on a demonstrated typed "not found" outcome, and every
 * {@code containingText} constraint is honored in order rather than only the first one.
 */
class PlaywrightScopeResolverTest {

    @Test
    void fallsBackToVisibleTextOnlyWhenAccessibleNameResolutionIsNotFound() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        IElement byVisibleText = element("Available");
        when(engine.locateSingle(eq(context), byAccessibleName()))
                .thenThrow(new LocatorNotFoundException("no accessible-name match"));
        when(engine.locateSingle(eq(context), byVisibleText())).thenReturn(result(byVisibleText));

        LocatorContext resolved =
                PlaywrightScopeResolver.resolveStructuredScope(engine, context, scope("Available"));

        assertThat(resolved.scope().root()).contains(byVisibleText);
    }

    @Test
    void neverFallsBackWhenAccessibleNameResolutionIsAmbiguous() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        AmbiguousLocatorException ambiguous =
                new AmbiguousLocatorException("two \"Shipping\" regions");
        when(engine.locateSingle(eq(context), byAccessibleName())).thenThrow(ambiguous);

        assertThatRuntimeException()
                .isThrownBy(
                        () ->
                                PlaywrightScopeResolver.resolveStructuredScope(
                                        engine, context, scope("Shipping")))
                .isSameAs(ambiguous);
        verify(engine, never()).locateSingle(eq(context), byVisibleText());
    }

    @Test
    void neverFallsBackOnAGenuineBackendOrRuntimeFailure() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        RuntimeException backendFailure = new IllegalStateException("browser disconnected");
        when(engine.locateSingle(eq(context), byAccessibleName())).thenThrow(backendFailure);

        assertThatRuntimeException()
                .isThrownBy(
                        () ->
                                PlaywrightScopeResolver.resolveStructuredScope(
                                        engine, context, scope("Shipping")))
                .isSameAs(backendFailure);
        verify(engine, never()).locateSingle(eq(context), byVisibleText());
    }

    @Test
    void honorsEveryContainingTextConstraintInOrderInsteadOfOnlyTheFirst() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        IElement laptopB = element("Laptop B region");
        IElement available = element("Available row");
        when(engine.locateSingle(eq(context), byAccessibleName())).thenReturn(result(laptopB));
        LocatorContext narrowedToLaptopB = context.within(laptopB);
        when(engine.locateSingle(eq(narrowedToLaptopB), byAccessibleName()))
                .thenReturn(result(available));

        LocatorContext resolved =
                PlaywrightScopeResolver.resolveStructuredScope(
                        engine, context, scope("Laptop B", "Available"));

        assertThat(resolved.scope().root()).contains(available);
        assertThat(resolved.scope().path()).hasSize(3); // Page -> Laptop B -> Available
        verify(engine).locateSingle(eq(context), byAccessibleName());
        verify(engine).locateSingle(eq(narrowedToLaptopB), byAccessibleName());
    }

    @Test
    void rejectsABlankContainingTextConstraintInsteadOfSilentlySkippingIt() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();

        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                PlaywrightScopeResolver.resolveStructuredScope(
                                        engine, context, scope("  ")));
    }

    @Test
    void rejectsANullContainingTextConstraintEvenAfterAnEarlierValidOne() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        IElement laptopB = element("Laptop B region");
        when(engine.locateSingle(eq(context), byAccessibleName())).thenReturn(result(laptopB));

        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                PlaywrightScopeResolver.resolveStructuredScope(
                                        engine, context, scope("Laptop B", null)));
    }

    private static LocatorDefinition byAccessibleName() {
        return argThat(definition -> definition.accessibleName().isPresent());
    }

    private static LocatorDefinition byVisibleText() {
        return argThat(definition -> definition.visibleText().isPresent());
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
