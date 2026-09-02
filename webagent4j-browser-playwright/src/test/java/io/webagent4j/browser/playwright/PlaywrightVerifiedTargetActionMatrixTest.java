package io.webagent4j.browser.playwright;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import io.webagent4j.action.KeyPress;
import io.webagent4j.action.Secret;
import io.webagent4j.action.Selection;
import io.webagent4j.browser.BrowserOptions;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.LocatorConfig;
import io.webagent4j.locator.LocatorScope;
import io.webagent4j.locator.api.ElementRole;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * PT-001 through PT-010 (plus PT-003c/PT-003d for {@code typeSequentially}, added for Governed
 * Actions V2): proves that every {@link PlaywrightActionBackend} side-effecting method - not merely
 * {@code click}, which {@link PlaywrightAtomicIdentityBindingTest} already covers - consumes an
 * identity-verified target's exact bound {@link ElementHandle} rather than independently
 * re-resolving the underlying {@link Locator}.
 *
 * <p>The scenario every test here defends against: candidate T1 is selected, its identity captured,
 * and re-proven by {@link PlaywrightElement#verifiedForExecution()} - which atomically binds the
 * exact physical {@link ElementHandle} that reproved it. The live DOM could, at any moment after
 * that, replace T1 with a different physical node T2 that still satisfies the exact same locator; a
 * method that performed its native call through the {@link Locator} instead of the already-bound
 * handle could silently land on T2. Every test below proves the opposite: the native call reaches
 * only the bound handle, and the {@link Locator} (T2's only possible entry point once a handle is
 * bound) is never touched for the actual side effect.
 */
class PlaywrightVerifiedTargetActionMatrixTest {

    private static final String CAPTURED_IDENTITY = "webagent4j-A";

    @Test
    void pt001ClickConsumesTheExactVerifiedHandleNeverTheLocator() {
        ElementHandle handle = mock(ElementHandle.class);
        Locator locator = verifiedLocator(handle);
        IElement verified = verifiedElement(locator);

        backend().click(verified);

        verify(handle, times(1)).click();
        verify(locator, never()).click();
    }

    @Test
    void pt002DoubleClickConsumesTheExactVerifiedHandleNeverTheLocator() {
        ElementHandle handle = mock(ElementHandle.class);
        Locator locator = verifiedLocator(handle);
        IElement verified = verifiedElement(locator);

        backend().doubleClick(verified);

        verify(handle, times(1)).dblclick();
        verify(locator, never()).dblclick();
    }

    @Test
    void pt003FillConsumesTheExactVerifiedHandleNeverTheLocator() {
        ElementHandle handle = mock(ElementHandle.class);
        Locator locator = verifiedLocator(handle);
        IElement verified = verifiedElement(locator);

        backend().fill(verified, "hello");

        verify(handle, times(1)).fill("hello");
        verify(locator, never()).fill(anyString());
    }

    @Test
    void pt003bFillSecretConsumesTheExactVerifiedHandleNeverTheLocator() {
        ElementHandle handle = mock(ElementHandle.class);
        Locator locator = verifiedLocator(handle);
        IElement verified = verifiedElement(locator);

        backend().fillSecret(verified, Secret.of("s3cr3t"));

        verify(handle, times(1)).fill("s3cr3t");
        verify(locator, never()).fill(anyString());
    }

    @Test
    void pt003cTypeSequentiallyConsumesTheExactVerifiedHandleNeverTheLocator() {
        ElementHandle handle = mock(ElementHandle.class);
        Locator locator = verifiedLocator(handle);
        IElement verified = verifiedElement(locator);

        backend().typeSequentially(verified, "hello");

        verify(handle, times(1)).type("hello");
        verify(locator, never()).pressSequentially(anyString());
    }

    @Test
    void pt003dTypeSequentiallySecretConsumesTheExactVerifiedHandleNeverTheLocator() {
        ElementHandle handle = mock(ElementHandle.class);
        Locator locator = verifiedLocator(handle);
        IElement verified = verifiedElement(locator);

        backend().typeSequentiallySecret(verified, Secret.of("s3cr3t"));

        verify(handle, times(1)).type("s3cr3t");
        verify(locator, never()).pressSequentially(anyString());
    }

    @Test
    void pt004ClearConsumesTheExactVerifiedHandleNeverTheLocator() {
        // clear() has no ElementHandle-native equivalent; the bound path performs the
        // protocol-equivalent handle.fill("") instead - the assertion here is that the empty fill
        // reaches the bound handle, and Locator#clear() (the only path that could reach a
        // replacement T2) is never invoked.
        ElementHandle handle = mock(ElementHandle.class);
        Locator locator = verifiedLocator(handle);
        IElement verified = verifiedElement(locator);

        backend().clear(verified);

        verify(handle, times(1)).fill("");
        verify(locator, never()).clear();
    }

    @Test
    void pt005SubmitConsumesTheExactVerifiedHandleNeverTheLocator() {
        ElementHandle handle = mock(ElementHandle.class);
        Locator locator = verifiedLocator(handle);
        IElement verified = verifiedElement(locator);

        backend().submit(verified);

        verify(handle, times(1)).evaluate(anyString());
        verify(locator, never()).evaluate(anyString());
    }

    @Test
    void pt006SelectConsumesTheExactVerifiedHandleNeverTheLocator() {
        ElementHandle handle = mock(ElementHandle.class);
        Locator locator = verifiedLocator(handle);
        IElement verified = verifiedElement(locator);

        backend().select(verified, Selection.byValue("option-1"));

        verify(handle, times(1))
                .selectOption(any(com.microsoft.playwright.options.SelectOption.class));
        verify(locator, never())
                .selectOption(any(com.microsoft.playwright.options.SelectOption.class));
    }

    @Test
    void pt007CheckConsumesTheExactVerifiedHandleNeverTheLocator() {
        ElementHandle handle = mock(ElementHandle.class);
        Locator locator = verifiedLocator(handle);
        IElement verified = verifiedElement(locator);

        backend().check(verified);

        verify(handle, times(1)).check();
        verify(locator, never()).check();
    }

    @Test
    void pt008UncheckConsumesTheExactVerifiedHandleNeverTheLocator() {
        ElementHandle handle = mock(ElementHandle.class);
        Locator locator = verifiedLocator(handle);
        IElement verified = verifiedElement(locator);

        backend().uncheck(verified);

        verify(handle, times(1)).uncheck();
        verify(locator, never()).uncheck();
    }

    @Test
    void pt009PressConsumesTheExactVerifiedHandleNeverTheLocator() {
        // Caution: a key press can trigger form submission or navigation. This test only proves
        // the native press() call itself is bound to the exact verified handle - the same
        // TOCTOU-closing guarantee every other action gets, regardless of what the key press then
        // causes downstream.
        ElementHandle handle = mock(ElementHandle.class);
        Locator locator = verifiedLocator(handle);
        IElement verified = verifiedElement(locator);

        backend().press(verified, KeyPress.of(io.webagent4j.action.PortableKey.ENTER));

        verify(handle, times(1)).press("Enter");
        verify(locator, never()).press(anyString());
    }

    @Test
    void pt010UploadConsumesTheExactVerifiedHandleNeverTheLocator() {
        // Security-sensitive: this test never logs or asserts on the file path's contents, only on
        // which handle received the native setInputFiles call.
        ElementHandle handle = mock(ElementHandle.class);
        Locator locator = verifiedLocator(handle);
        IElement verified = verifiedElement(locator);

        backend().upload(verified, List.of(Path.of("upload-fixture.txt")));

        verify(handle, times(1)).setInputFiles(any(Path[].class));
        verify(locator, never()).setInputFiles(any(Path[].class));
    }

    @Test
    void focusConsumesTheExactVerifiedHandleNeverTheLocator() {
        ElementHandle handle = mock(ElementHandle.class);
        Locator locator = verifiedLocator(handle);
        IElement verified = verifiedElement(locator);

        backend().focus(verified);

        verify(handle, times(1)).focus();
        verify(locator, never()).focus();
    }

    @Test
    void blurConsumesTheExactVerifiedHandleNeverTheLocator() {
        // blur() has no ElementHandle-native equivalent either; the bound path invokes the
        // equivalent native DOM blur() directly on the bound handle via evaluate().
        ElementHandle handle = mock(ElementHandle.class);
        Locator locator = verifiedLocator(handle);
        IElement verified = verifiedElement(locator);

        backend().blur(verified);

        verify(handle, times(1)).evaluate("element => element.blur()");
        verify(locator, never()).blur();
    }

    @Test
    void hoverConsumesTheExactVerifiedHandleNeverTheLocator() {
        ElementHandle handle = mock(ElementHandle.class);
        Locator locator = verifiedLocator(handle);
        IElement verified = verifiedElement(locator);

        backend().hover(verified);

        verify(handle, times(1)).hover();
        verify(locator, never()).hover();
    }

    private static PlaywrightActionBackend backend() {
        return new PlaywrightActionBackend(mock(Page.class), BrowserOptions.defaults());
    }

    /** Stubs {@code locator} so a fresh atomic identity re-verification reproves {@code handle}. */
    private static Locator verifiedLocator(ElementHandle handle) {
        Locator locator = mock(Locator.class);
        when(locator.elementHandles()).thenReturn(List.of(handle));
        when(handle.evaluate(anyString(), any()))
                .thenReturn(Map.of("identity", CAPTURED_IDENTITY, "domOrder", 0));
        return locator;
    }

    /** Returns the atomically-verified element view carrying {@code locator}'s bound handle. */
    private static IElement verifiedElement(Locator locator) {
        PlaywrightElement element =
                new PlaywrightElement(
                        locator,
                        ElementRole.BUTTON,
                        null,
                        LocatorScope.page(),
                        LocatorConfig.defaults(),
                        1_000.0,
                        null,
                        CAPTURED_IDENTITY);
        return element.verifiedForExecution().orElseThrow();
    }
}
