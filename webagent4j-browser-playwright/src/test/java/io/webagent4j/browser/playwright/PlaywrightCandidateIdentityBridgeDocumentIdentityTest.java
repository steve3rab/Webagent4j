package io.webagent4j.browser.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Real-browser regression coverage (DOC-ID-001..007) for the bridge's document-freshness fix: at
 * least one browser engine can keep a document's own {@code globalThis} alive while silently
 * replacing the {@code Document} that realm's own {@code document} accessor resolves to, and the
 * bridge must re-evaluate identity and containment against whichever {@code Document} is active
 * <em>right now</em>, never a snapshot captured once at install time.
 *
 * <p>An actual same-realm document replacement is a real, engine-specific browser behavior (see
 * {@link PlaywrightFrameIdentityRealmDiagnosticTest}, which observes it directly against real
 * Firefox in CI) that cannot be portably forced from page script in every engine this suite runs
 * on. What every engine <em>does</em> support, in a fully spec-compliant, portable way, is {@code
 * document.implementation.createHTMLDocument(...)}: an in-memory, disconnected {@code Document}
 * created inside the exact same window/realm as the page's own live document, without any
 * navigation at all. A node that lives only in that document has a real, distinct {@code
 * ownerDocument} while the realm's live {@code document} accessor still resolves to the page's own
 * document - precisely the shape {@code activeDocument() !== ownerDocument(node)} the fixed bridge
 * must reject, engine-independently.
 *
 * <p>Skips (rather than fails) whenever the requested engine cannot be launched locally, exactly
 * like {@link PlaywrightCandidateIdentityBridgeTest} and {@link
 * PlaywrightFrameIdentityRealmDiagnosticTest}.
 */
class PlaywrightCandidateIdentityBridgeDocumentIdentityTest {

    @Test
    void doc001TwoConsecutiveProbesOnTheSameNodeAgreeOnIdentity() {
        withPage(
                page -> {
                    page.setContent("<button id=\"btn\">Pay</button>");
                    page.evaluate(PlaywrightCandidateIdentityBridge.installScript());
                    ElementHandle button = page.querySelector("#btn");

                    String identity1 = identityOf(button);
                    String identity2 = identityOf(button);

                    assertThat(identity1).isNotBlank();
                    assertThat(identity2).isEqualTo(identity1);
                    return null;
                });
    }

    @Test
    void doc002ANodeReplacedBySelectorEquivalentButPhysicallyDifferentNodeGetsADifferentIdentity() {
        withPage(
                page -> {
                    page.setContent("<button id=\"btn\">Pay</button>");
                    page.evaluate(PlaywrightCandidateIdentityBridge.installScript());
                    ElementHandle originalButton = page.querySelector("#btn");
                    String originalIdentity = identityOf(originalButton);

                    page.evaluate(
                            "document.getElementById('btn')"
                                    + ".outerHTML = '<button id=\"btn\">Pay</button>'");
                    ElementHandle replacementButton = page.querySelector("#btn");
                    String replacementIdentity = identityOf(replacementButton);

                    assertThat(replacementIdentity).isNotBlank().isNotEqualTo(originalIdentity);
                    return null;
                });
    }

    @Test
    void doc004ANodeFromAForeignInMemoryDocumentIsRejectedAsADocumentMismatch() {
        withPage(
                page -> {
                    page.setContent("<div id=\"root\"></div>");
                    page.evaluate(PlaywrightCandidateIdentityBridge.installScript());

                    ElementHandle foreignButton =
                            page.evaluateHandle(
                                            "() => {"
                                                    + "const foreignDocument ="
                                                    + " document.implementation"
                                                    + ".createHTMLDocument('foreign');"
                                                    + "const button ="
                                                    + " foreignDocument.createElement('button');"
                                                    + "foreignDocument.body.appendChild(button);"
                                                    + "return button;"
                                                    + "}")
                                    .asElement();

                    @SuppressWarnings("unchecked")
                    Map<String, Object> inspected =
                            (Map<String, Object>)
                                    foreignButton.evaluate(
                                            PlaywrightCandidateIdentityBridge.identityScript(),
                                            PlaywrightCandidateIdentityBridge.bridgeName());

                    assertThat(inspected.get("documentMismatch")).isEqualTo(true);
                    assertThat(inspected).doesNotContainKey("identity");
                    return null;
                });
    }

    @Test
    void doc006ContainmentIsEvaluatedAgainstTheActiveDocumentNotAForeignOne() {
        withPage(
                page -> {
                    page.setContent("<div id=\"root\"><button id=\"btn\">Pay</button></div>");
                    page.evaluate(PlaywrightCandidateIdentityBridge.installScript());
                    ElementHandle root = page.querySelector("#root");

                    ElementHandle foreignButton =
                            page.evaluateHandle(
                                            "() => {"
                                                    + "const foreignDocument ="
                                                    + " document.implementation"
                                                    + ".createHTMLDocument('foreign');"
                                                    + "const button ="
                                                    + " foreignDocument.createElement('button');"
                                                    + "foreignDocument.body.appendChild(button);"
                                                    + "return button;"
                                                    + "}")
                                    .asElement();

                    assertThatThrownBy(
                                    () ->
                                            root.evaluate(
                                                    PlaywrightCandidateIdentityBridge
                                                            .descendantOrSelfScript(),
                                                    foreignButton))
                            .hasMessageContaining("crossed a document boundary");
                    return null;
                });
    }

    @Test
    void doc007TamperingWithDomPrimitivesAfterInstallDoesNotChangeTheBridgesAnswer() {
        withPage(
                page -> {
                    page.setContent("<button id=\"btn\">Pay</button>");
                    page.evaluate(PlaywrightCandidateIdentityBridge.installScript());
                    ElementHandle button = page.querySelector("#btn");
                    String genuineIdentity = identityOf(button);

                    // A page script run after installation can still reach and redefine these
                    // ordinary (non-Unforgeable) prototype accessors and methods - proving the
                    // bridge's own pristine captures, taken before this ran, are unaffected by
                    // it is exactly what makes the bridge tamper-resistant.
                    page.evaluate(
                            "() => {"
                                    + "Object.defineProperty(Node.prototype, 'ownerDocument', {"
                                    + "  get() { return document.implementation"
                                    + ".createHTMLDocument('forged'); },"
                                    + "  configurable: true"
                                    + "});"
                                    + "Document.prototype.querySelectorAll = () => [];"
                                    + "Node.prototype.contains = () => false;"
                                    + "}");

                    String identityAfterTampering = identityOf(button);

                    assertThat(identityAfterTampering).isEqualTo(genuineIdentity);
                    return null;
                });
    }

    private static String identityOf(ElementHandle handle) {
        @SuppressWarnings("unchecked")
        Map<String, Object> inspected =
                (Map<String, Object>)
                        handle.evaluate(
                                PlaywrightCandidateIdentityBridge.identityScript(),
                                PlaywrightCandidateIdentityBridge.bridgeName());
        Object identity = inspected.get("identity");
        assertThat(identity).as("identity envelope: %s", inspected).isInstanceOf(String.class);
        return (String) identity;
    }

    private void withPage(Function<Page, Void> body) {
        Playwright playwright;
        try {
            playwright = Playwright.create();
        } catch (RuntimeException noLocalDriver) {
            assumeTrue(
                    false, "No local Playwright driver reachable: " + noLocalDriver.getMessage());
            return;
        }
        try {
            String engine = System.getProperty("diagnostic.browser", "chromium");
            BrowserType.LaunchOptions launchOptions =
                    new BrowserType.LaunchOptions().setHeadless(true);
            String executablePath = System.getProperty("diagnostic.executablePath");
            if (executablePath != null && !executablePath.isBlank()) {
                launchOptions.setExecutablePath(java.nio.file.Path.of(executablePath));
            }
            Browser browser;
            try {
                browser =
                        switch (engine) {
                            case "firefox" -> playwright.firefox().launch(launchOptions);
                            case "webkit" -> playwright.webkit().launch(launchOptions);
                            default -> playwright.chromium().launch(launchOptions);
                        };
            } catch (RuntimeException noMatchingBuild) {
                assumeTrue(
                        false,
                        "No local " + engine + " build reachable: " + noMatchingBuild.getMessage());
                return;
            }
            try {
                BrowserContext context = browser.newContext();
                Page page = context.newPage();
                body.apply(page);
            } finally {
                browser.close();
            }
        } finally {
            playwright.close();
        }
    }
}
