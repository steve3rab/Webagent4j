package io.webagent4j.browser.playwright;

import static org.assertj.core.api.Assertions.assertThatRuntimeException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.microsoft.playwright.Locator;
import io.webagent4j.browser.BrowserOptions;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.ILocatorBackend;
import io.webagent4j.locator.ILocatorEngine;
import io.webagent4j.locator.LocatorCandidate;
import io.webagent4j.locator.LocatorConfig;
import io.webagent4j.locator.LocatorContext;
import io.webagent4j.locator.LocatorScope;
import io.webagent4j.locator.LocatorStrategyType;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.TextMatch;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link PlaywrightFrameLocator#tryFind()} preserves its documented contract - only a typed
 * "not found" outcome becomes an empty {@link Optional}, everything else propagates - when the
 * failure originates from URL candidate inspection specifically, mirroring {@link
 * PlaywrightFrameScopeResolverTest}'s resolver-level proof of the same rule.
 */
class PlaywrightFrameLocatorTest {

    @Test
    void tryFindNeverConvertsAUrlInspectionBackendFailureIntoAnEmptyOptional() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context =
                LocatorContext.page(throwingBackend(), LocatorConfig.builder().build());
        Locator iframeLocator = mock(Locator.class);
        RuntimeException backendFailure = new IllegalStateException("browser disconnected");
        when(iframeLocator.elementHandle(any(Locator.ElementHandleOptions.class)))
                .thenThrow(backendFailure);
        IElement iframe =
                new PlaywrightElement(
                        iframeLocator,
                        ElementRole.UNKNOWN,
                        null,
                        LocatorScope.page(),
                        LocatorConfig.builder().build());
        when(engine.locateAll(eq(context), any())).thenReturn(List.of(candidate(iframe)));

        PlaywrightFrameLocator locator =
                new PlaywrightFrameLocator(
                        engine,
                        context,
                        List.of(),
                        LocatorConfig.builder().build(),
                        BrowserOptions.defaults(),
                        mock(PlaywrightActionBackend.class));

        assertThatRuntimeException()
                .isThrownBy(
                        () ->
                                locator.withUrl(TextMatch.exact("https://example.com/checkout"))
                                        .tryFind())
                .isSameAs(backendFailure);
    }

    private static ILocatorBackend throwingBackend() {
        return (query, scope, config, timeout, limit) -> {
            throw new UnsupportedOperationException("backend must not be invoked directly");
        };
    }

    private static LocatorCandidate candidate(IElement element) {
        return new LocatorCandidate(
                "id-" + System.identityHashCode(element),
                element,
                LocatorStrategyType.CSS,
                1.0,
                1.0,
                0,
                List.of(),
                true,
                true,
                true);
    }
}
