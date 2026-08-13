package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IPage;
import io.webagent4j.core.WebAgent;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.AmbiguousLocatorException;
import io.webagent4j.locator.LocatorConfig;
import io.webagent4j.locator.LocatorDiagnostics;
import io.webagent4j.locator.LocatorDiagnosticsLevel;
import io.webagent4j.locator.LocatorNotFoundException;
import io.webagent4j.locator.LocatorResolutionBudget;
import io.webagent4j.locator.LocatorResolutionPolicy;
import io.webagent4j.locator.LocatorResult;
import io.webagent4j.locator.LocatorScoringConfig;
import io.webagent4j.locator.LocatorStrategyType;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.IElementReference;
import io.webagent4j.locator.api.LocatorDefinition;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SemanticLocatorIT {

    private static HttpServer server;
    private static ExecutorService executor;
    private static String baseUrl;

    @BeforeAll
    static void startLocatorApplication() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/locators/basic", exchange -> respond(exchange, locatorPage()));
        server.createContext("/locators/dynamic", exchange -> respond(exchange, dynamicPage()));
        server.createContext(
                "/locators/replacement", exchange -> respond(exchange, replacementPage()));
        server.createContext("/locators/overlay", exchange -> respond(exchange, overlayPage()));
        server.createContext(
                "/locators/responsive", exchange -> respond(exchange, responsivePage()));
        server.createContext("/locators/unicode", exchange -> respond(exchange, unicodePage()));
        server.createContext(
                "/locators/implicit-roles", exchange -> respond(exchange, implicitRolesPage()));
        server.createContext(
                "/locators/nested-text", exchange -> respond(exchange, nestedTextPage()));
        server.createContext("/locators/scoring", exchange -> respond(exchange, scoringPage()));
        server.createContext("/locators/large-dom", exchange -> respond(exchange, largeDomPage()));
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopLocatorApplication() {
        server.stop(0);
        executor.close();
    }

    @Test
    void resolvesNativeSemanticAndExplicitStrategies() {
        try (IBrowser browser =
                WebAgent.browser().playwright().chromium().headless(true).launch()) {
            IPage page = browser.open(baseUrl + "/locators/basic");

            assertThat(
                            page.find()
                                    .button()
                                    .named("Sign in")
                                    .visible()
                                    .enabled()
                                    .single()
                                    .accessibleName())
                    .isEqualTo("Sign in");
            assertThat(page.find().textbox().labelled("Email address").single().tagName())
                    .isEqualTo("input");
            assertThat(page.find().placeholder("name@example.com").single().attributes())
                    .containsEntry("id", "email");
            assertThat(page.find().id("email").single().role()).isEqualTo(ElementRole.TEXTBOX);
            assertThat(page.find().image().named("Profile photo").single().attributes())
                    .containsEntry("alt", "Profile photo");
            assertThat(page.find().link().nameContaining("Documentation").all()).hasSize(1);
            assertThat(page.find().testId("login-submit").single().role())
                    .isEqualTo(ElementRole.BUTTON);
            assertThat(page.find().css(".product").single().text()).isEqualTo("Product card");
            assertThat(
                            page.find()
                                    .xpath("//button[@data-testid='login-submit']")
                                    .single()
                                    .accessibleName())
                    .isEqualTo("Sign in");
        }
    }

    @Test
    void ranksFuzzyCandidatesDetectsAmbiguityAndExplainsSelection() {
        try (IBrowser browser =
                WebAgent.browser().playwright().chromium().headless(true).launch()) {
            IPage page = browser.open(baseUrl + "/locators/basic");

            IElement fuzzy = page.find().button().fuzzyName("Connexion").first();
            assertThat(fuzzy.accessibleName()).isEqualTo("Se connecter");
            assertThatThrownBy(() -> page.find().button().named("Add").single())
                    .isInstanceOf(AmbiguousLocatorException.class)
                    .hasMessageContaining("1. BUTTON", "2. BUTTON");

            LocatorResult result =
                    page.locate(
                            LocatorDefinition.element().role(ElementRole.BUTTON).named("Sign in"));
            assertThat(result.strategy()).isEqualTo(LocatorStrategyType.ACCESSIBLE_NAME);
            assertThat(result.explain()).contains("role = BUTTON", "confidence=1.00");
        }
    }

    @Test
    void supportsScopedDynamicAndStateFilteredQueries() {
        try (IBrowser browser =
                WebAgent.browser().playwright().chromium().headless(true).launch()) {
            IPage page = browser.open(baseUrl + "/locators/basic");

            IElement form = page.find().form().named("Payment").single();
            assertThat(form.find().button().named("Pay").single().accessibleName())
                    .isEqualTo("Pay");
            assertThat(page.find().button().named("Hidden action").hidden().single().visible())
                    .isFalse();
            assertThat(page.find().button().named("Disabled action").disabled().single().enabled())
                    .isFalse();
            assertThat(
                            page.find()
                                    .button()
                                    .named("Confirm")
                                    .waitUntilVisible()
                                    .timeout(Duration.ofSeconds(2))
                                    .single()
                                    .visible())
                    .isTrue();
        }
    }

    @Test
    void reResolvesSemanticReferencesAfterCompleteDomReplacement() {
        try (IBrowser browser =
                WebAgent.browser().playwright().chromium().headless(true).launch()) {
            IPage page = browser.open(baseUrl + "/locators/replacement");
            IElementReference<IElement> reference =
                    page.find().button().named("Replace target").reference();

            assertThat(reference.resolve().attributes()).containsEntry("data-generation", "1");
            page.evaluate("replaceTarget()");
            assertThat(reference.resolve().attributes()).containsEntry("data-generation", "2");
            assertThat(page.action().click(reference).execute().success()).isTrue();
            assertThat(page.evaluate("document.body.dataset.clickedGeneration")).isEqualTo("2");
        }
    }

    @Test
    void requiresContinuousTemporalStabilityAcrossRemovalAndRecreation() {
        try (IBrowser browser =
                WebAgent.browser().playwright().chromium().headless(true).launch()) {
            IPage page = browser.open(baseUrl + "/locators/dynamic");
            page.evaluate("startDynamicSequence()");
            LocatorResult result =
                    page.locate(
                            LocatorDefinition.forRole(ElementRole.BUTTON)
                                    .named("Confirm")
                                    .visibleOnly()
                                    .stableFor(Duration.ofMillis(80))
                                    .withTimeout(Duration.ofSeconds(1)));

            assertThat(result.element().attributes()).containsEntry("data-generation", "2");
            assertThat(result.diagnostics().duration())
                    .isGreaterThanOrEqualTo(Duration.ofMillis(160));
        }
    }

    @Test
    void distinguishesVisibilityFromReliableClickabilityUnderOverlay() {
        try (IBrowser browser =
                WebAgent.browser().playwright().chromium().headless(true).launch()) {
            IPage page = browser.open(baseUrl + "/locators/overlay");
            IElement button = page.find().button().named("Covered action").visible().single();

            assertThat(button.visible()).isTrue();
            assertThat(button.covered()).isTrue();
            assertThat(button.clickable()).isFalse();
            assertThat(page.find().button().named("Covered action").covered().single().attributes())
                    .containsEntry("id", "covered-action");
            assertThatThrownBy(
                            () ->
                                    page.find()
                                            .button()
                                            .named("Covered action")
                                            .clickable()
                                            .timeout(Duration.ofMillis(120))
                                            .single())
                    .isInstanceOf(LocatorNotFoundException.class);
        }
    }

    @Test
    void ranksResponsiveVisibleDuplicatesAndExcludesHiddenAccessibilityContent() {
        try (IBrowser browser =
                WebAgent.browser().playwright().chromium().headless(true).launch()) {
            IPage page = browser.open(baseUrl + "/locators/responsive");

            assertThat(page.find().button().named("Menu").first().attributes())
                    .containsEntry("id", "desktop-menu");
            assertThat(page.find().button().named("Menu").visible().single().attributes())
                    .containsEntry("id", "desktop-menu");
            assertThat(page.find().button().named("Hidden ARIA").hidden().single().visible())
                    .isFalse();
            assertThat(page.find().button().named("Hidden attribute").hidden().single().visible())
                    .isFalse();
        }
    }

    @Test
    void supportsUnicodeNestedAccessibleNamesAndConfiguredTestIds() {
        try (IBrowser browser =
                WebAgent.browser().playwright().chromium().headless(true).launch()) {
            IPage unicode = browser.open(baseUrl + "/locators/unicode");
            assertThat(unicode.find().button().named("CRÉER le compte").single().tagName())
                    .isEqualTo("button");
            LocatorConfig strict =
                    LocatorConfig.builder()
                            .resolutionPolicy(LocatorResolutionPolicy.STRICT)
                            .resolutionBudget(
                                    new LocatorResolutionBudget(
                                            Duration.ofMillis(120), 100, 10, 50))
                            .build();
            assertThatThrownBy(
                            () -> unicode.find(strict).button().named("Creer le compte").single())
                    .isInstanceOf(LocatorNotFoundException.class);

            IPage nested = browser.open(baseUrl + "/locators/nested-text");
            assertThat(nested.find().button().named("Ajouter au panier").single().text())
                    .isEqualTo("Ajouter au panier");
            assertThat(nested.find().textbox().labelled("Email address").single().attributes())
                    .containsEntry("id", "nested-email");
            assertThat(nested.find().button().named("Labelled action").single().attributes())
                    .containsEntry("aria-labelledby", "action-label");
            assertThat(nested.find().image().named("Product image").single().tagName())
                    .isEqualTo("img");
            LocatorConfig customTestId = LocatorConfig.builder().testIdAttribute("data-qa").build();
            assertThat(nested.find(customTestId).testId("checkout").single().tagName())
                    .isEqualTo("button");
        }
    }

    @Test
    void resolvesImplicitFormControlsAndAriaLandmarks() {
        try (IBrowser browser =
                WebAgent.browser().playwright().chromium().headless(true).launch()) {
            IPage page = browser.open(baseUrl + "/locators/implicit-roles");

            assertThat(page.find().banner().single().tagName()).isEqualTo("header");
            assertThat(page.find().navigation().single().tagName()).isEqualTo("nav");
            assertThat(page.find().main().single().tagName()).isEqualTo("main");
            assertThat(page.find().search().single().tagName()).isEqualTo("search");
            assertThat(page.find().complementary().single().tagName()).isEqualTo("aside");
            assertThat(page.find().contentInfo().single().tagName()).isEqualTo("footer");
            assertThat(page.find().form().named("Account").single().tagName()).isEqualTo("form");
            assertThat(page.find().textbox().all()).hasSize(4);
            assertThat(page.find().searchbox().single().attributes())
                    .containsEntry("type", "search");
            assertThat(page.find().checkbox().single().attributes())
                    .containsEntry("type", "checkbox");
            assertThat(page.find().radio().single().attributes()).containsEntry("type", "radio");
            assertThat(page.find().select().single().tagName()).isEqualTo("select");
            assertThat(page.find().option().named("Premium").single().tagName())
                    .isEqualTo("option");
            assertThat(page.find().button().named("Submit account").single().attributes())
                    .containsEntry("type", "submit");
            assertThat(page.find().link().named("Account help").single().tagName()).isEqualTo("a");
        }
    }

    @Test
    void exposesStructuredScoringDiagnosticsAndBoundsLargeDomResolution() {
        try (IBrowser browser =
                WebAgent.browser().playwright().chromium().headless(true).launch()) {
            IPage scoring = browser.open(baseUrl + "/locators/scoring");
            LocatorConfig detailed =
                    LocatorConfig.builder()
                            .diagnosticsLevel(LocatorDiagnosticsLevel.DETAILED)
                            .scoring(
                                    new LocatorScoringConfig(
                                            0.10, 0.20, 0.10, 0.05, 0.20, 0.20, 0.05, 0.05, 0.05))
                            .build();
            LocatorResult result =
                    scoring.locate(
                            LocatorDefinition.forRole(ElementRole.BUTTON).named("Checkout"),
                            detailed);
            LocatorDiagnostics diagnostics = result.diagnostics();

            assertThat(result.element().attributes()).containsEntry("id", "semantic-checkout");
            assertThat(result.candidates()).hasSize(2);
            assertThat(result.candidates().get(0).evidence())
                    .extracting("strategy")
                    .contains(LocatorStrategyType.ACCESSIBLE_NAME);
            assertThat(diagnostics.strategiesExecuted()).isNotEmpty();
            assertThat(diagnostics.candidatesDeduplicated()).isPositive();
            assertThat(diagnostics.duration()).isGreaterThanOrEqualTo(Duration.ZERO);

            IPage large = browser.open(baseUrl + "/locators/large-dom");
            assertThat(large.find().id("exact-target").single().text()).isEqualTo("Exact target");
            assertThat(large.find().button().named("Semantic target").single().tagName())
                    .isEqualTo("button");
            assertThat(large.find().button().fuzzyName("Ajouter panier").first().text())
                    .isEqualTo("Ajouter au panier");
        }
    }

    private static String locatorPage() {
        return """
                <!doctype html>
                <html lang="en">
                  <head><title>Locator fixtures</title></head>
                  <body>
                    <button aria-label="Sign in" data-testid="login-submit">Sign in</button>
                    <label for="email">Email address</label>
                    <input id="email" name="email" placeholder="name@example.com">
                    <img src="profile.png" alt="Profile photo">
                    <a href="/docs"><span>Documentation portal</span></a>
                    <div class="product">Product card</div>
                    <button>Se connecter</button>
                    <button>Add</button><button>Add</button>
                    <button style="display:none">Hidden action</button>
                    <button disabled>Disabled action</button>
                    <form aria-label="Payment">
                      <button type="button">Pay</button>
                      <button type="button">Cancel</button>
                    </form>
                    <div id="dynamic"></div>
                    <script>
                      setTimeout(() => {
                        const button = document.createElement('button');
                        button.textContent = 'Confirm';
                        document.getElementById('dynamic').appendChild(button);
                      }, 150);
                    </script>
                  </body>
                </html>
                """;
    }

    private static String dynamicPage() {
        return """
                <!doctype html>
                <html lang="en">
                  <head><title>Dynamic locator fixture</title></head>
                  <body>
                    <div id="host"></div>
                    <script>
                      const host = document.getElementById('host');
                      const add = generation => {
                        const button = document.createElement('button');
                        button.textContent = 'Confirm';
                        button.dataset.generation = generation;
                        host.replaceChildren(button);
                      };
                      function startDynamicSequence() {
                        setTimeout(() => add('1'), 20);
                        setTimeout(() => host.replaceChildren(), 60);
                        setTimeout(() => add('2'), 100);
                      }
                    </script>
                  </body>
                </html>
                """;
    }

    private static String replacementPage() {
        return """
                <!doctype html>
                <html lang="en">
                  <head><title>Replacement locator fixture</title></head>
                  <body>
                    <div id="host">
                      <button data-generation="1"
                        onclick="document.body.dataset.clickedGeneration='1'">
                        Replace target
                      </button>
                    </div>
                    <script>
                      function replaceTarget() {
                        const replacement = document.createElement('button');
                        replacement.textContent = 'Replace target';
                        replacement.dataset.generation = '2';
                        replacement.onclick = () => {
                          document.body.dataset.clickedGeneration = '2';
                        };
                        document.getElementById('host').replaceChildren(replacement);
                      }
                    </script>
                  </body>
                </html>
                """;
    }

    private static String overlayPage() {
        return """
                <!doctype html>
                <html lang="en">
                  <head>
                    <title>Overlay locator fixture</title>
                    <style>
                      #covered-action { position:fixed; left:20px; top:20px; width:180px; height:50px; }
                      #overlay { position:fixed; left:0; top:0; width:240px; height:100px;
                        background:rgba(0,0,0,.2); z-index:10; }
                    </style>
                  </head>
                  <body>
                    <button id="covered-action">Covered action</button>
                    <div id="overlay" aria-label="Blocking overlay"></div>
                  </body>
                </html>
                """;
    }

    private static String responsivePage() {
        return """
                <!doctype html>
                <html lang="en">
                  <head><title>Responsive locator fixture</title></head>
                  <body>
                    <nav aria-label="Mobile"><button id="mobile-menu" style="display:none">Menu</button></nav>
                    <nav aria-label="Desktop"><button id="desktop-menu">Menu</button></nav>
                    <button aria-hidden="true">Hidden ARIA</button>
                    <button hidden>Hidden attribute</button>
                    <button style="visibility:hidden">Visibility hidden</button>
                  </body>
                </html>
                """;
    }

    private static String unicodePage() {
        return """
                <!doctype html>
                <html lang="fr">
                  <head><title>Unicode locator fixture</title></head>
                  <body>
                    <button>  Créer&nbsp;   le compte  </button>
                  </body>
                </html>
                """;
    }

    private static String implicitRolesPage() {
        return """
                <!doctype html>
                <html lang="en">
                  <head><title>Implicit roles fixture</title></head>
                  <body>
                    <header>Site banner</header>
                    <nav aria-label="Primary"><a href="#help">Account help</a></nav>
                    <main>
                      <search><input type="search" aria-label="Find account"></search>
                      <form aria-label="Account">
                        <input type="text" aria-label="Name">
                        <input type="email" aria-label="Email">
                        <input type="password" aria-label="Password">
                        <textarea aria-label="Biography"></textarea>
                        <input type="checkbox" aria-label="Terms">
                        <input type="radio" aria-label="Plan">
                        <select aria-label="Subscription">
                          <option>Standard</option><option>Premium</option>
                        </select>
                        <button type="submit">Submit account</button>
                      </form>
                    </main>
                    <aside>Related content</aside>
                    <footer>Site information</footer>
                  </body>
                </html>
                """;
    }

    private static String nestedTextPage() {
        return """
                <!doctype html>
                <html lang="en">
                  <head><title>Nested accessible names fixture</title></head>
                  <body>
                    <button><span>Ajouter</span> <strong>au panier</strong></button>
                    <label for="nested-email">Email address</label>
                    <input id="nested-email" type="email">
                    <span id="action-label">Labelled action</span>
                    <button aria-labelledby="action-label">Icon</button>
                    <label>Nested label <input id="nested-label" type="text"></label>
                    <img alt="Product image" src="product.png">
                    <button title="Title action">Icon</button>
                    <a href="#details">Product details</a>
                    <button data-qa="checkout">Checkout</button>
                  </body>
                </html>
                """;
    }

    private static String scoringPage() {
        return """
                <!doctype html>
                <html lang="en">
                  <head><title>Scoring fixture</title></head>
                  <body>
                    <button id="text-checkout" aria-label="Different action">Checkout</button>
                    <button id="semantic-checkout" aria-label="Checkout">Proceed</button>
                  </body>
                </html>
                """;
    }

    private static String largeDomPage() {
        StringBuilder body = new StringBuilder();
        body.append(
                "<!doctype html><html lang=\"en\"><head><title>Large DOM fixture</title></head><body>");
        body.append("<button>Ajouter au panier</button><button>Semantic target</button>");
        for (int index = 0; index < 5000; index++) {
            body.append("<div data-index=\"")
                    .append(index)
                    .append("\">Item ")
                    .append(index)
                    .append("</div>");
        }
        body.append("<div id=\"exact-target\">Exact target</div></body></html>");
        return body.toString();
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, payload.length);
        try (var output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }
}
