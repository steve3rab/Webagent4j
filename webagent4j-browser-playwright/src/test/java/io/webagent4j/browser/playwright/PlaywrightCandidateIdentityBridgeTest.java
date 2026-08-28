package io.webagent4j.browser.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link PlaywrightCandidateIdentityBridge}'s install script tolerates running more than
 * once against the same document.
 *
 * <p>At least one non-Chromium engine is documented to deliver a context-registered init script
 * more than once for the same iframe document (see the class-level note on the script's re-entry
 * guard). Whether that double-delivery actually happens is a real, browser-specific behavior this
 * suite cannot observe without that engine, but what happens <em>if</em> it does is not
 * browser-specific at all: {@code Object.defineProperty} with {@code configurable: false} throwing
 * on a second definition is plain ECMAScript semantics, identical on every spec-compliant engine
 * including the one used here. Running the exact install script text twice on the same real
 * document and confirming it never throws is therefore a complete, engine-independent proof that
 * this specific defect class cannot recur, even where only Chromium is available.
 *
 * <p>Skips (rather than fails) when no local Chromium install is reachable, so this test does not
 * fabricate a result in an environment that cannot launch a browser at all - the same honesty this
 * feature's cross-browser qualification requires.
 */
class PlaywrightCandidateIdentityBridgeTest {

    @Test
    void installScriptToleratesRunningTwiceOnTheSameDocumentWithoutThrowing() {
        Playwright playwright;
        try {
            playwright = Playwright.create();
        } catch (RuntimeException noLocalBrowser) {
            assumeTrue(
                    false, "No local Chromium install reachable: " + noLocalBrowser.getMessage());
            return;
        }
        try {
            Browser browser;
            try {
                browser =
                        playwright
                                .chromium()
                                .launch(new BrowserType.LaunchOptions().setHeadless(true));
            } catch (RuntimeException noMatchingBrowserBuild) {
                assumeTrue(
                        false,
                        "No matching local Chromium build reachable: "
                                + noMatchingBrowserBuild.getMessage());
                return;
            }
            try (Page page = browser.newPage()) {
                page.navigate("about:blank");

                assertThatCode(
                                () -> {
                                    page.evaluate(
                                            PlaywrightCandidateIdentityBridge.installScript());
                                    // The second run on the very same document is the exact
                                    // condition the guard exists for.
                                    page.evaluate(
                                            PlaywrightCandidateIdentityBridge.installScript());
                                })
                        .doesNotThrowAnyException();

                Object bridgeType =
                        page.evaluate(
                                "bridgeName => typeof globalThis[bridgeName]",
                                PlaywrightCandidateIdentityBridge.bridgeName());
                assertThat(bridgeType).isEqualTo("function");
            } finally {
                browser.close();
            }
        } finally {
            playwright.close();
        }
    }
}
