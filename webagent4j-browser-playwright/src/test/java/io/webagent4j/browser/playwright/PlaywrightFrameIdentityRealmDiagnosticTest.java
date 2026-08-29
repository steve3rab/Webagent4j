package io.webagent4j.browser.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.FrameLocator;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Narrow, click-free boundary diagnostic for one question only: once {@link
 * PlaywrightCandidateIdentityBridge#install(BrowserContext)} has run for a context, is its
 * closure-private bridge actually reachable - from the exact realm {@link
 * ElementHandle#evaluate(String, Object)} evaluates a probe script in - for a physical element
 * living inside an iframe document?
 *
 * <p>This exists to distinguish, with real browser evidence rather than assumption, between two
 * shapes a persistent "the identity bridge is unavailable" signal could have: the probe script's
 * own {@code globalThis} not being the element's owning window ({@code
 * element.ownerDocument.defaultView}) at all - a realm mismatch - versus the bridge never having
 * been delivered to that document's {@code globalThis} in the first place, under either name. Only
 * the first shape can be fixed by anchoring the bridge lookup to the element's owning window
 * instead of the evaluating realm's implicit global; the second requires a different fix entirely
 * (and specifically not a late re-installation of the init script, which would let this document's
 * own application JavaScript run before the bridge's pristine primitive capture, defeating the
 * exact tamper-resistance property the bridge exists for).
 *
 * <p>The diagnostic script below never returns the real, random per-document identity token, the
 * bridge property name's own value, or any raw backend exception text to Java - only booleans and,
 * for identity capture, whether two immediately consecutive captures on the same untouched node
 * agree, checked entirely inside the one script invocation. This mirrors the diagnostics discipline
 * {@link PlaywrightScopeResolver} and {@link PlaywrightLocatorBackend} already use: structured,
 * safe signals only, never a raw value that could carry something sensitive.
 *
 * <p>Skips (rather than fails) whenever the requested engine cannot be launched locally - the same
 * honesty {@link PlaywrightCandidateIdentityBridgeTest} already applies, and the only truthful
 * outcome in an environment with no installable Firefox at all: a skip here is not evidence the
 * underlying question was answered, only that this environment could not answer it.
 */
class PlaywrightFrameIdentityRealmDiagnosticTest {

    private static final String DIAGNOSTIC_SCRIPT =
            """
            (element, bridgeName) => {
              const ownerDocument = element.ownerDocument;
              const ownerWindow = ownerDocument ? ownerDocument.defaultView : null;
              const sameRealm = ownerWindow === globalThis;

              const globalCandidate = globalThis[bridgeName];
              const globalBridgePresent = typeof globalCandidate === "function";

              const ownerWindowCheckedSeparately =
                ownerWindow != null && ownerWindow !== globalThis;
              let ownerWindowBridgePresent = globalBridgePresent;
              if (!globalBridgePresent && ownerWindowCheckedSeparately) {
                try {
                  ownerWindowBridgePresent = typeof ownerWindow[bridgeName] === "function";
                } catch (unreachableWindow) {
                  ownerWindowBridgePresent = false;
                }
              }

              const activeBridge = globalBridgePresent
                ? globalCandidate
                : (ownerWindowBridgePresent ? ownerWindow[bridgeName] : null);

              let documentMismatch = false;
              let identityCaptured = false;
              let identityReprovedSame = false;
              let bridgeInvokeThrew = false;

              if (typeof activeBridge === "function") {
                try {
                  const first = activeBridge("identity", element, null);
                  const second = activeBridge("identity", element, null);
                  if (first && typeof first === "object" && first.documentMismatch === true) {
                    documentMismatch = true;
                  } else if (
                    first
                    && typeof first === "object"
                    && typeof first.identity === "string"
                  ) {
                    identityCaptured = true;
                    identityReprovedSame =
                      second
                      && typeof second === "object"
                      && second.identity === first.identity;
                  }
                } catch (invocationFailure) {
                  bridgeInvokeThrew = true;
                }
              }

              return {
                sameRealm,
                globalBridgePresent,
                ownerWindowCheckedSeparately,
                ownerWindowBridgePresent,
                documentMismatch,
                identityCaptured,
                identityReprovedSame,
                bridgeInvokeThrew
              };
            }
            """;

    private static final String SIMPLE_IFRAME_HTML =
            "<html><body><iframe name=\"checkout\" srcdoc=\"<button>Pay</button>\">"
                    + "</iframe></body></html>";

    private static final String NESTED_IFRAME_HTML =
            "<html><body><iframe name=\"outer\" srcdoc=\""
                    + "<iframe name=&quot;inner&quot; srcdoc=&quot;&lt;button&gt;Pay&lt;/button&gt;&quot;>"
                    + "</iframe>\"></iframe></body></html>";

    @Test
    void simpleIframeButtonBridgeIsReachableInTheElementsOwningRealm() {
        RealmDiagnosticResult result =
                diagnoseFirstMatch(
                        SIMPLE_IFRAME_HTML, page -> page.frameLocator("iframe[name=\"checkout\"]"));

        assertHealthyOutcome(result);
    }

    @Test
    void nestedIframeButtonBridgeIsReachableInTheElementsOwningRealm() {
        RealmDiagnosticResult result =
                diagnoseFirstMatch(
                        NESTED_IFRAME_HTML,
                        page ->
                                page.frameLocator("iframe[name=\"outer\"]")
                                        .frameLocator("iframe[name=\"inner\"]"));

        assertHealthyOutcome(result);
    }

    private static void assertHealthyOutcome(RealmDiagnosticResult result) {
        assertThat(result.documentMismatch()).as("diagnostic outcome: %s", result).isFalse();
        assertThat(result.identityCaptured()).as("diagnostic outcome: %s", result).isTrue();
        assertThat(result.identityReprovedSame()).as("diagnostic outcome: %s", result).isTrue();
        assertThat(result.bridgeInvokeThrew()).as("diagnostic outcome: %s", result).isFalse();
        assertThat(result.classify())
                .as("diagnostic outcome: %s", result)
                .isEqualTo(RealmDiagnosticOutcome.SAME_REALM_BRIDGE_PRESENT);
    }

    private RealmDiagnosticResult diagnoseFirstMatch(
            String html, java.util.function.Function<Page, FrameLocator> buttonFrame) {
        Playwright playwright;
        try {
            playwright = Playwright.create();
        } catch (RuntimeException noLocalDriver) {
            assumeTrue(
                    false, "No local Playwright driver reachable: " + noLocalDriver.getMessage());
            return null;
        }
        try {
            String engine = System.getProperty("diagnostic.browser", "firefox");
            Browser browser;
            try {
                BrowserType.LaunchOptions launchOptions =
                        new BrowserType.LaunchOptions().setHeadless(true);
                String executablePath = System.getProperty("diagnostic.executablePath");
                if (executablePath != null && !executablePath.isBlank()) {
                    launchOptions.setExecutablePath(java.nio.file.Path.of(executablePath));
                }
                browser =
                        switch (engine) {
                            case "chromium" -> playwright.chromium().launch(launchOptions);
                            case "webkit" -> playwright.webkit().launch(launchOptions);
                            default -> playwright.firefox().launch(launchOptions);
                        };
            } catch (RuntimeException noMatchingBuild) {
                assumeTrue(
                        false,
                        "No local " + engine + " build reachable: " + noMatchingBuild.getMessage());
                return null;
            }
            try {
                BrowserContext context = browser.newContext();
                PlaywrightCandidateIdentityBridge.install(context);
                Page page = context.newPage();
                page.setContent(html);

                Locator button = buttonFrame.apply(page).locator("button");
                button.waitFor();
                assertThat(button.count()).as("fixture button must be present").isEqualTo(1);

                List<ElementHandle> handles = button.elementHandles();
                assertThat(handles).hasSize(1);
                ElementHandle handle = handles.getFirst();
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> raw =
                            (Map<String, Object>)
                                    handle.evaluate(
                                            DIAGNOSTIC_SCRIPT,
                                            PlaywrightCandidateIdentityBridge.bridgeName());
                    return RealmDiagnosticResult.fromRaw(raw);
                } finally {
                    handle.dispose();
                }
            } finally {
                browser.close();
            }
        } finally {
            playwright.close();
        }
    }

    /** The safe, non-sensitive taxonomy this diagnostic can report - never a raw identity value. */
    enum RealmDiagnosticOutcome {
        SAME_REALM_BRIDGE_PRESENT,
        OWNER_WINDOW_BRIDGE_PRESENT,
        OWNER_WINDOW_BRIDGE_MISSING,
        GLOBAL_BRIDGE_MISSING,
        DOCUMENT_MISMATCH,
        BRIDGE_PRESENT_BUT_INVALID
    }

    /**
     * Structured, safe result of one diagnostic probe. Every field is a boolean; nothing here can
     * carry a raw identity token, a DOM value, or backend exception text.
     */
    record RealmDiagnosticResult(
            boolean sameRealm,
            boolean globalBridgePresent,
            boolean ownerWindowCheckedSeparately,
            boolean ownerWindowBridgePresent,
            boolean documentMismatch,
            boolean identityCaptured,
            boolean identityReprovedSame,
            boolean bridgeInvokeThrew) {

        static RealmDiagnosticResult fromRaw(Map<String, Object> raw) {
            return new RealmDiagnosticResult(
                    Boolean.TRUE.equals(raw.get("sameRealm")),
                    Boolean.TRUE.equals(raw.get("globalBridgePresent")),
                    Boolean.TRUE.equals(raw.get("ownerWindowCheckedSeparately")),
                    Boolean.TRUE.equals(raw.get("ownerWindowBridgePresent")),
                    Boolean.TRUE.equals(raw.get("documentMismatch")),
                    Boolean.TRUE.equals(raw.get("identityCaptured")),
                    Boolean.TRUE.equals(raw.get("identityReprovedSame")),
                    Boolean.TRUE.equals(raw.get("bridgeInvokeThrew")));
        }

        /**
         * A single overall classification, in priority order. {@code
         * OWNER_WINDOW_BRIDGE_PRESENT}/{@code OWNER_WINDOW_BRIDGE_MISSING} are only ever returned
         * when the owning window genuinely differs from the evaluating realm ({@link
         * #ownerWindowCheckedSeparately}) - when it does not, checking it again would tell nothing
         * beyond what the global check already answered, so the outcome collapses to {@code
         * GLOBAL_BRIDGE_MISSING}.
         */
        RealmDiagnosticOutcome classify() {
            if (documentMismatch) {
                return RealmDiagnosticOutcome.DOCUMENT_MISMATCH;
            }
            if (bridgeInvokeThrew) {
                return RealmDiagnosticOutcome.BRIDGE_PRESENT_BUT_INVALID;
            }
            if (globalBridgePresent) {
                return RealmDiagnosticOutcome.SAME_REALM_BRIDGE_PRESENT;
            }
            if (!ownerWindowCheckedSeparately) {
                return RealmDiagnosticOutcome.GLOBAL_BRIDGE_MISSING;
            }
            return ownerWindowBridgePresent
                    ? RealmDiagnosticOutcome.OWNER_WINDOW_BRIDGE_PRESENT
                    : RealmDiagnosticOutcome.OWNER_WINDOW_BRIDGE_MISSING;
        }
    }
}
