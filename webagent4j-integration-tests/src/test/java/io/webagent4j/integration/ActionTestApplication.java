package io.webagent4j.integration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

final class ActionTestApplication implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final String baseUrl;
    private final AtomicInteger clickCount;
    private final Map<String, AtomicInteger> namedClickCounts;

    private ActionTestApplication(
            HttpServer server,
            ExecutorService executor,
            AtomicInteger clickCount,
            Map<String, AtomicInteger> namedClickCounts) {
        this.server = server;
        this.executor = executor;
        this.clickCount = clickCount;
        this.namedClickCounts = namedClickCounts;
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    static ActionTestApplication start() throws IOException {
        AtomicInteger count = new AtomicInteger();
        Map<String, AtomicInteger> namedCounts = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/download/file", ActionTestApplication::download);
        server.createContext(
                "/test-state/click-count", exchange -> text(exchange, count.toString()));
        server.createContext(
                "/test-state/reset",
                exchange -> {
                    count.set(0);
                    namedCounts.clear();
                    text(exchange, "reset");
                });
        server.createContext(
                "/actions",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    html(exchange, page(path));
                });
        server.createContext("/login", exchange -> html(exchange, loginPage()));
        server.createContext("/dashboard", exchange -> html(exchange, dashboardPage()));
        server.createContext("/navigation/one", exchange -> html(exchange, navigationPage("One")));
        server.createContext("/navigation/two", exchange -> html(exchange, navigationPage("Two")));
        server.createContext(
                "/count-click",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    String prefix = "/count-click";
                    String name =
                            path.length() > prefix.length() + 1
                                    ? path.substring(prefix.length() + 1)
                                    : "";
                    if (name.isEmpty()) {
                        count.incrementAndGet();
                    } else {
                        namedCounts
                                .computeIfAbsent(name, key -> new AtomicInteger())
                                .incrementAndGet();
                    }
                    text(exchange, "ok");
                });
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
        return new ActionTestApplication(server, executor, count, namedCounts);
    }

    String url(String route) {
        return baseUrl + route;
    }

    int clickCount() {
        return clickCount.get();
    }

    /**
     * Returns the independent click count recorded under a named {@code /count-click/<name>} hit.
     */
    int clickCount(String name) {
        AtomicInteger recorded = namedClickCounts.get(name);
        return recorded == null ? 0 : recorded.get();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }

    private static String page(String path) {
        return switch (path) {
            case "/actions/type" ->
                    document(
                            "Type actions",
                            """
                            <label for="email">Email</label><input id="email" type="email">
                            <label for="password">Password</label><input id="password" type="password">
                            <p id="typed">Ready</p>
                            """);
            case "/actions/select" ->
                    document(
                            "Select actions",
                            """
                            <label for="country">Country</label><select id="country">
                              <option value="fr">France</option><option value="de">Germany</option>
                            </select><p id="selection">Ready</p>
                            """);
            case "/actions/checkbox" ->
                    document(
                            "Checkbox actions",
                            "<label><input type="
                                    + "\"checkbox\" name=\"remember\"> Remember me</label>");
            case "/actions/hover" ->
                    document(
                            "Hover actions",
                            """
                            <button onmouseenter="tip.hidden=false">Show details</button>
                            <div id="tip" role="status" hidden>Helpful details</div>
                            """);
            case "/actions/focus", "/actions/keyboard" ->
                    document(
                            "Keyboard actions",
                            """
                            <label for="first">First</label><input id="first">
                            <label for="second">Second</label><input id="second"
                              onkeydown="if(event.key==='Enter') done.hidden=false">
                            <p id="done" hidden>Submitted</p>
                            """);
            case "/actions/scroll" ->
                    document(
                            "Scroll actions",
                            "<div style=\"height:1800px\"></div><button "
                                    + "onclick=\"result.hidden=false\">Far action</button>"
                                    + "<p id=\"result\" hidden>Reached</p>");
            case "/actions/navigation" -> navigationPage("Actions navigation");
            case "/actions/upload" ->
                    document(
                            "Upload actions",
                            """
                            <label for="file">Document</label><input id="file" type="file"
                              onchange="filename.textContent=this.files[0].name">
                            <p id="filename">No file</p>
                            """);
            case "/actions/download" ->
                    document(
                            "Download actions",
                            "<a href=\"/download/file\" download>Download report</a>");
            case "/actions/dynamic-target" ->
                    document(
                            "Dynamic target",
                            """
                            <button id="confirm" onclick="result.hidden=false">Confirm</button>
                            <p id="result" hidden>Confirmed</p>
                            <script>setTimeout(() => {
                              const old = document.getElementById('confirm');
                              const fresh = document.createElement('button');
                              fresh.id='fresh'; fresh.textContent='Confirm';
                              fresh.onclick=() => result.hidden=false; old.replaceWith(fresh);
                            }, 150)</script>
                            """);
            case "/actions/plan-same-target" ->
                    document(
                            "Plan same target",
                            """
                            <button id="confirm" onclick="fetch('/count-click')">Confirm</button>
                            <script>
                              function replaceConfirmButtonWithFreshNode() {
                                const old = document.getElementById('confirm');
                                const fresh = document.createElement('button');
                                fresh.id = 'fresh';
                                fresh.textContent = 'Confirm';
                                fresh.onclick = () => fetch('/count-click');
                                old.replaceWith(fresh);
                              }
                            </script>
                            """);
            case "/actions/plan-wrong-target" ->
                    document(
                            "Plan wrong target",
                            """
                            <button id="confirm" onclick="fetch('/count-click')">Confirm</button>
                            <script>
                              function replaceConfirmButtonWithUnrelatedDeleteButton() {
                                const old = document.getElementById('confirm');
                                const wrong = document.createElement('button');
                                wrong.id = 'delete';
                                wrong.textContent = 'Delete';
                                wrong.onclick = () => fetch('/count-click');
                                old.replaceWith(wrong);
                              }
                            </script>
                            """);
            case "/actions/plan-ambiguity" ->
                    document(
                            "Plan ambiguity",
                            """
                            <div id="host"><button onclick="fetch('/count-click')">Confirm</button></div>
                            <script>
                              function addDuplicateConfirmButton() {
                                const duplicate = document.createElement('button');
                                duplicate.textContent = 'Confirm';
                                duplicate.onclick = () => fetch('/count-click');
                                document.getElementById('host').appendChild(duplicate);
                              }
                            </script>
                            """);
            case "/actions/policy-toctou" ->
                    document(
                            "Policy TOCTOU",
                            """
                            <button id="first" onclick="firstClicked()">Confirm</button>
                            <script>
                              // Synchronous, in-page oracles: a click handler running is observable
                              // the instant it happens through page.evaluate(), unlike the fetch()
                              // calls above, which are asynchronous and can still be in flight (or
                              // not yet delivered to the server) at the moment a Java-side assertion
                              // runs - a false "zero clicks" read is possible with fetch() alone.
                              window.firstClickEvents = 0;
                              window.replacementClickEvents = 0;
                              function firstClicked() {
                                window.firstClickEvents++;
                                fetch('/count-click/first');
                              }
                              function replaceFirstWithReplacementSameLocator() {
                                const old = document.getElementById('first');
                                const replacement = document.createElement('button');
                                replacement.id = 'replacement';
                                replacement.textContent = 'Confirm';
                                replacement.onclick = () => {
                                  window.replacementClickEvents++;
                                  fetch('/count-click/replacement');
                                };
                                old.replaceWith(replacement);
                              }
                            </script>
                            """);
            case "/actions/policy-toctou-fill" ->
                    document(
                            "Policy TOCTOU fill",
                            """
                            <input id="first" type="text" aria-label="Confirm"
                              oninput="window.firstInputEvents++; fetch('/count-click/first')">
                            <script>
                              // Same synchronous-oracle rationale as the click fixture above: an
                              // input event handler running is observable immediately, never racing
                              // an in-flight fetch() the browser has not yet delivered.
                              window.firstInputEvents = 0;
                              window.replacementInputEvents = 0;
                              function replaceFirstInputWithReplacementSameLocator() {
                                const old = document.getElementById('first');
                                const replacement = document.createElement('input');
                                replacement.id = 'replacement';
                                replacement.type = 'text';
                                replacement.setAttribute('aria-label', 'Confirm');
                                replacement.oninput = () => {
                                  window.replacementInputEvents++;
                                  fetch('/count-click/replacement');
                                };
                                old.replaceWith(replacement);
                              }
                            </script>
                            """);
            case "/actions/policy-toctou-submit" ->
                    document(
                            "Policy TOCTOU submit",
                            """
                            <form id="first-form" onsubmit="return firstSubmitted(event)">
                              <button type="submit" aria-label="Confirm">Confirm</button>
                            </form>
                            <script>
                              // Same synchronous-oracle rationale as the other TOCTOU fixtures - a
                              // submit handler running (with the real navigation suppressed via
                              // preventDefault()) is observable immediately via page.evaluate().
                              window.firstSubmitEvents = 0;
                              window.replacementSubmitEvents = 0;
                              function firstSubmitted(event) {
                                event.preventDefault();
                                window.firstSubmitEvents++;
                                fetch('/count-click/first');
                                return false;
                              }
                              function replaceFirstFormWithReplacementSameLocator() {
                                const old = document.getElementById('first-form');
                                const replacement = document.createElement('form');
                                replacement.id = 'replacement-form';
                                const button = document.createElement('button');
                                button.type = 'submit';
                                button.setAttribute('aria-label', 'Confirm');
                                button.textContent = 'Confirm';
                                replacement.appendChild(button);
                                replacement.onsubmit = (event) => {
                                  event.preventDefault();
                                  window.replacementSubmitEvents++;
                                  fetch('/count-click/replacement');
                                  return false;
                                };
                                old.replaceWith(replacement);
                              }
                            </script>
                            """);
            case "/actions/policy-toctou-select" ->
                    document(
                            "Policy TOCTOU select",
                            """
                            <select id="first" aria-label="Confirm"
                              onchange="window.firstSelectEvents++; fetch('/count-click/first')">
                              <option value="one">One</option>
                              <option value="two">Two</option>
                            </select>
                            <script>
                              window.firstSelectEvents = 0;
                              window.replacementSelectEvents = 0;
                              function replaceFirstSelectWithReplacementSameLocator() {
                                const old = document.getElementById('first');
                                const replacement = document.createElement('select');
                                replacement.id = 'replacement';
                                replacement.setAttribute('aria-label', 'Confirm');
                                for (const value of ['one', 'two']) {
                                  const option = document.createElement('option');
                                  option.value = value;
                                  option.textContent = value;
                                  replacement.appendChild(option);
                                }
                                replacement.onchange = () => {
                                  window.replacementSelectEvents++;
                                  fetch('/count-click/replacement');
                                };
                                old.replaceWith(replacement);
                              }
                            </script>
                            """);
            case "/actions/policy-toctou-check" ->
                    document(
                            "Policy TOCTOU check",
                            """
                            <input id="first" type="checkbox" aria-label="Confirm"
                              onchange="window.firstCheckEvents++; fetch('/count-click/first')">
                            <script>
                              window.firstCheckEvents = 0;
                              window.replacementCheckEvents = 0;
                              function replaceFirstCheckboxWithReplacementSameLocator() {
                                const old = document.getElementById('first');
                                const replacement = document.createElement('input');
                                replacement.id = 'replacement';
                                replacement.type = 'checkbox';
                                replacement.setAttribute('aria-label', 'Confirm');
                                replacement.onchange = () => {
                                  window.replacementCheckEvents++;
                                  fetch('/count-click/replacement');
                                };
                                old.replaceWith(replacement);
                              }
                            </script>
                            """);
            case "/actions/policy-toctou-uncheck" ->
                    document(
                            "Policy TOCTOU uncheck",
                            """
                            <input id="first" type="checkbox" checked aria-label="Confirm"
                              onchange="window.firstUncheckEvents++; fetch('/count-click/first')">
                            <script>
                              window.firstUncheckEvents = 0;
                              window.replacementUncheckEvents = 0;
                              function replaceFirstCheckedCheckboxWithReplacementSameLocator() {
                                const old = document.getElementById('first');
                                const replacement = document.createElement('input');
                                replacement.id = 'replacement';
                                replacement.type = 'checkbox';
                                replacement.checked = true;
                                replacement.setAttribute('aria-label', 'Confirm');
                                replacement.onchange = () => {
                                  window.replacementUncheckEvents++;
                                  fetch('/count-click/replacement');
                                };
                                old.replaceWith(replacement);
                              }
                            </script>
                            """);
            case "/actions/policy-toctou-hover" ->
                    document(
                            "Policy TOCTOU hover",
                            """
                            <button id="first" onmouseenter="firstHovered()">Confirm</button>
                            <script>
                              window.firstHoverEvents = 0;
                              window.replacementHoverEvents = 0;
                              function firstHovered() {
                                window.firstHoverEvents++;
                                fetch('/count-click/first');
                              }
                              function replaceFirstHoverTargetWithReplacementSameLocator() {
                                const old = document.getElementById('first');
                                const replacement = document.createElement('button');
                                replacement.id = 'replacement';
                                replacement.textContent = 'Confirm';
                                replacement.onmouseenter = () => {
                                  window.replacementHoverEvents++;
                                  fetch('/count-click/replacement');
                                };
                                old.replaceWith(replacement);
                              }
                            </script>
                            """);
            case "/actions/policy-toctou-press" ->
                    document(
                            "Policy TOCTOU press",
                            """
                            <input id="first" type="text" aria-label="Confirm" onkeydown="firstKeyDown(event)">
                            <script>
                              window.firstPressEvents = 0;
                              window.replacementPressEvents = 0;
                              function firstKeyDown(event) {
                                if (event.key === 'Enter') {
                                  window.firstPressEvents++;
                                  fetch('/count-click/first');
                                }
                              }
                              function replaceFirstPressTargetWithReplacementSameLocator() {
                                const old = document.getElementById('first');
                                const replacement = document.createElement('input');
                                replacement.id = 'replacement';
                                replacement.type = 'text';
                                replacement.setAttribute('aria-label', 'Confirm');
                                replacement.onkeydown = (event) => {
                                  if (event.key === 'Enter') {
                                    window.replacementPressEvents++;
                                    fetch('/count-click/replacement');
                                  }
                                };
                                old.replaceWith(replacement);
                              }
                            </script>
                            """);
            case "/actions/policy-toctou-typesequence" ->
                    document(
                            "Policy TOCTOU typeSequentially",
                            """
                            <input id="first" type="text" aria-label="Confirm"
                              oninput="window.firstTypeSeqEvents++; fetch('/count-click/first')">
                            <script>
                              window.firstTypeSeqEvents = 0;
                              window.replacementTypeSeqEvents = 0;
                              function replaceFirstTypeSeqInputWithReplacementSameLocator() {
                                const old = document.getElementById('first');
                                const replacement = document.createElement('input');
                                replacement.id = 'replacement';
                                replacement.type = 'text';
                                replacement.setAttribute('aria-label', 'Confirm');
                                replacement.oninput = () => {
                                  window.replacementTypeSeqEvents++;
                                  fetch('/count-click/replacement');
                                };
                                old.replaceWith(replacement);
                              }
                            </script>
                            """);
            case "/actions/plan-precondition-invalidates" ->
                    document(
                            "Plan precondition invalidates",
                            """
                            <button id="confirm" onclick="fetch('/count-click')">Confirm</button>
                            <script>
                              function disableConfirmButton() {
                                document.getElementById('confirm').disabled = true;
                              }
                            </script>
                            """);
            case "/actions/context-multi" ->
                    document(
                            "Context multi",
                            """
                            <section aria-label="Laptop A">
                              <h2>Laptop A</h2>
                              <div aria-label="Unavailable"><span>Unavailable</span>
                                <button onclick="fetch('/count-click/laptopA-unavailable')">Ajouter</button></div>
                              <div aria-label="Available"><span>Available</span>
                                <button onclick="fetch('/count-click/laptopA-available')">Ajouter</button></div>
                            </section>
                            <section aria-label="Laptop B">
                              <h2>Laptop B</h2>
                              <div aria-label="Unavailable"><span>Unavailable</span>
                                <button onclick="fetch('/count-click/laptopB-unavailable')">Ajouter</button></div>
                              <div aria-label="Available"><span>Available</span>
                                <button onclick="fetch('/count-click/laptopB-available')">Ajouter</button></div>
                            </section>
                            """);
            case "/actions/context-ambiguous" ->
                    document(
                            "Context ambiguous",
                            """
                            <section aria-label="Shipping"><button
                              onclick="fetch('/count-click/shipping-1')">Continue</button></section>
                            <section aria-label="Shipping"><button
                              onclick="fetch('/count-click/shipping-2')">Continue</button></section>
                            """);
            case "/actions/context-cross-source-ambiguous" ->
                    document(
                            "Context cross-source ambiguous",
                            """
                            <section aria-label="Shipping"><button
                              onclick="fetch('/count-click/shipping-1')">Continue</button></section>
                            <h2 id="shipping-title">Shipping</h2>
                            <section aria-labelledby="shipping-title"><button
                              onclick="fetch('/count-click/shipping-2')">Continue</button></section>
                            """);
            case "/actions/context-dynamic-ambiguous" ->
                    document(
                            "Context dynamic ambiguous",
                            """
                            <section id="shipping-1" aria-label="Shipping"><button
                              onclick="fetch('/count-click/shipping-1')">Continue</button></section>
                            <script>setTimeout(() => {
                              const duplicate = document.createElement('section');
                              duplicate.setAttribute('aria-label', 'Shipping');
                              duplicate.innerHTML =
                                '<button onclick="fetch(\\'/count-click/shipping-2\\')">Continue</button>';
                              document.body.appendChild(duplicate);
                            }, 150)</script>
                            """);
            case "/actions/context-dynamic-ambiguous-target-unique" ->
                    document(
                            "Context dynamic ambiguous target unique",
                            """
                            <section id="shipping-1" aria-label="Shipping"><button
                              onclick="fetch('/count-click/shipping-1')">Continue</button></section>
                            <script>setTimeout(() => {
                              const duplicate = document.createElement('section');
                              duplicate.setAttribute('aria-label', 'Shipping');
                              duplicate.innerHTML = '<span>No Continue here</span>';
                              document.body.appendChild(duplicate);
                            }, 150)</script>
                            """);
            case "/actions/context-dynamic-disappears" ->
                    document(
                            "Context dynamic disappears",
                            """
                            <section id="shipping-solo" aria-label="Shipping"><button
                              onclick="fetch('/count-click/shipping-solo')">Continue</button></section>
                            <script>setTimeout(() => {
                              document.getElementById('shipping-solo').remove();
                            }, 150)</script>
                            """);
            case "/actions/context-dynamic-replaced" ->
                    document(
                            "Context dynamic replaced",
                            """
                            <section id="shipping-old" aria-label="Shipping"><button
                              onclick="fetch('/count-click/shipping-continue')">Continue</button></section>
                            <script>setTimeout(() => {
                              const old = document.getElementById('shipping-old');
                              const fresh = document.createElement('section');
                              fresh.id = 'shipping-fresh';
                              fresh.setAttribute('aria-label', 'Shipping');
                              fresh.innerHTML =
                                '<button onclick="fetch(\\'/count-click/shipping-continue\\')">Continue</button>';
                              old.replaceWith(fresh);
                            }, 150)</script>
                            """);
            case "/actions/context-scope-insert-before-use" ->
                    document(
                            "Context scope insertion race",
                            """
                            <section id="shipping-original" aria-label="Shipping"><button
                              onclick="fetch('/count-click/shipping-original')">Continue</button></section>
                            """);
            case "/actions/context-scope-replace-before-use" ->
                    document(
                            "Context scope replacement race",
                            """
                            <section id="shipping-original" aria-label="Shipping"><button
                              onclick="fetch('/count-click/shipping-original')">Continue</button></section>
                            """);
            case "/actions/context-scope-reorder-before-use" ->
                    document(
                            "Context scope reorder race",
                            """
                            <section id="shipping-original" aria-label="Shipping"><button
                              onclick="fetch('/count-click/shipping-original')">Continue</button></section>
                            <section id="billing-existing" aria-label="Billing"><button
                              onclick="fetch('/count-click/billing-existing')">Continue</button></section>
                            """);
            case "/actions/context-scope-duplicate-before-use" ->
                    document(
                            "Context scope ambiguity race",
                            """
                            <section id="shipping-original" aria-label="Shipping"><button
                              onclick="fetch('/count-click/shipping-original')">Continue</button></section>
                            """);
            case "/actions/context-scope-preexisting-attribute" ->
                    document(
                            "Context scope pre-existing application attribute",
                            """
                            <section id="shipping-original" aria-label="Shipping"
                              data-webagent4j-scope-id="app-owned-value"><button
                              onclick="fetch('/count-click/shipping-original')">Continue</button></section>
                            """);
            case "/actions/context-dynamic-semantic-change" ->
                    document(
                            "Context dynamic semantic change",
                            """
                            <section id="shipping-old" aria-label="Shipping"><button
                              onclick="fetch('/count-click/shipping-continue')">Continue</button></section>
                            <script>setTimeout(() => {
                              const old = document.getElementById('shipping-old');
                              const billing = document.createElement('section');
                              billing.setAttribute('aria-label', 'Billing');
                              billing.innerHTML =
                                '<button onclick="fetch(\\'/count-click/billing-continue\\')">Continue</button>';
                              old.replaceWith(billing);
                            }, 150)</script>
                            """);
            case "/actions/context-dynamic-nested-ambiguous" ->
                    document(
                            "Context dynamic nested ambiguous",
                            """
                            <section aria-label="Laptop B">
                              <h2>Laptop B</h2>
                              <div id="laptopB-available" aria-label="Available"><span>Available</span>
                                <button onclick="fetch('/count-click/laptopB-available')">Ajouter</button></div>
                            </section>
                            <script>setTimeout(() => {
                              const section = document.querySelector('section[aria-label="Laptop B"]');
                              const duplicate = document.createElement('div');
                              duplicate.setAttribute('aria-label', 'Available');
                              duplicate.innerHTML =
                                '<span>Available</span><button '
                                + 'onclick="fetch(\\'/count-click/laptopB-available-2\\')">Ajouter</button>';
                              section.appendChild(duplicate);
                            }, 150)</script>
                            """);
            case "/actions/mixed-scope-product" ->
                    document(
                            "Mixed scope product",
                            """
                            <section aria-label="Product A">
                              <h2>Product A</h2>
                              <div id="outer-container">
                                <section aria-label="Available">
                                  <button onclick="fetch('/count-click/product-a-ajouter')">Ajouter</button>
                                </section>
                              </div>
                            </section>
                            <section aria-label="Product B">
                              <h2>Product B</h2>
                              <div id="other-container">
                                <section aria-label="Available">
                                  <button onclick="fetch('/count-click/product-b-ajouter')">Ajouter</button>
                                </section>
                              </div>
                            </section>
                            """);
            case "/actions/mixed-scope-product-dynamic" ->
                    document(
                            "Mixed scope product dynamic",
                            """
                            <section aria-label="Product A">
                              <h2>Product A</h2>
                              <div id="outer-container">
                                <section aria-label="Available" id="available-old">
                                  <button onclick="fetch('/count-click/product-a-ajouter')">Ajouter</button>
                                </section>
                              </div>
                            </section>
                            <section aria-label="Product B">
                              <h2>Product B</h2>
                              <div id="other-container">
                                <section aria-label="Available">
                                  <button onclick="fetch('/count-click/product-b-ajouter')">Ajouter</button>
                                </section>
                              </div>
                            </section>
                            <script>
                              function replaceProductAAvailableRegion() {
                                const old = document.getElementById('available-old');
                                const fresh = document.createElement('section');
                                fresh.setAttribute('aria-label', 'Available');
                                fresh.innerHTML =
                                  '<button onclick="fetch(\\'/count-click/product-a-ajouter\\')">Ajouter</button>';
                                old.replaceWith(fresh);
                              }
                            </script>
                            """);
            case "/actions/mixed-scope-detached-child" ->
                    document(
                            "Mixed scope detached child",
                            """
                            <section aria-label="Product A">
                              <h2>Product A</h2>
                              <div id="outer-container">
                                <button onclick="fetch('/count-click/product-a-confirm')">Confirm</button>
                              </div>
                            </section>
                            <script>
                              function detachOuterContainer() {
                                document.getElementById('outer-container').remove();
                              }
                            </script>
                            """);
            case "/actions/mixed-scope-child-moved" ->
                    document(
                            "Mixed scope child moved",
                            """
                            <section aria-label="Product A">
                              <h2>Product A</h2>
                              <div id="panel">
                                <button onclick="fetch('/count-click/panel-confirm')">Confirm</button>
                              </div>
                            </section>
                            <section aria-label="Product B" id="product-b">
                              <h2>Product B</h2>
                            </section>
                            <script>
                              function movePanelToProductB() {
                                document.getElementById('product-b').appendChild(
                                  document.getElementById('panel'));
                              }
                            </script>
                            """);
            case "/actions/delayed-result", "/actions/retry" ->
                    document(
                            "Delayed result",
                            """
                            <button onclick="fetch('/count-click'); setTimeout(() => done.hidden=false, 650)">
                              Process once</button><p id="done" hidden>Completed once</p>
                            """);
            case "/actions/dialog" ->
                    document(
                            "Dialog actions",
                            """
                            <button onclick="notice.showModal()">Open notifications</button>
                            <dialog id="notice" aria-label="Notifications"><h2>Notifications</h2>
                              <button onclick="notice.close()">Close</button></dialog>
                            """);
            case "/actions/failure" ->
                    document(
                            "Failure actions",
                            "<button disabled>Disabled action</button><p>Still usable</p>");
            case "/actions/overlay" ->
                    document(
                            "Overlay actions",
                            """
                            <button id="covered">Covered action</button>
                            <div style="position:fixed;inset:0;background:#fff8;z-index:2"></div>
                            """);
            case "/actions/form" -> loginPage();
            case "/actions/ambiguous" ->
                    document(
                            "Ambiguous actions",
                            "<button>Duplicate</button><button>Duplicate</button>");
            case "/actions/workflow-branch-ready" ->
                    document(
                            "Workflow branch ready",
                            """
                            <button id="confirm" aria-label="Confirm"
                              onclick="fetch('/count-click/confirm')">Confirm</button>
                            <button id="cancel" aria-label="Cancel"
                              onclick="fetch('/count-click/cancel')">Cancel</button>
                            """);
            case "/actions/workflow-branch-target-changed" ->
                    document(
                            "Workflow branch target changed",
                            """
                            <button id="confirm" aria-label="Confirm"
                              onclick="fetch('/count-click/original')">Confirm</button>
                            <button id="cancel" aria-label="Cancel"
                              onclick="fetch('/count-click/cancel')">Cancel</button>
                            <script>
                              function replaceConfirmButtonWithFreshNodeSameLocator() {
                                const old = document.getElementById('confirm');
                                const fresh = document.createElement('button');
                                fresh.id = 'confirm-replacement';
                                fresh.setAttribute('aria-label', 'Confirm');
                                fresh.textContent = 'Confirm';
                                fresh.onclick = () => fetch('/count-click/replacement');
                                old.replaceWith(fresh);
                              }
                            </script>
                            """);
            case "/actions/workflow-loop-pagination" ->
                    document(
                            "Workflow loop pagination",
                            """
                            <p id="page-indicator" role="status" aria-label="Current page">1</p>
                            <button id="next" aria-label="Next" onclick="
                              var indicator = document.getElementById('page-indicator');
                              var next = Number(indicator.textContent) + 1;
                              indicator.textContent = String(next);
                              fetch('/count-click/next');
                              if (next >= 3) { document.getElementById('next').remove(); }
                            ">Next</button>
                            """);
            case "/actions/click-timeout-oracle" ->
                    document(
                            "Click timeout oracle",
                            """
                            <p id="counter">0</p>
                            <button id="increment" onclick="
                              counter.textContent = String(Number(counter.textContent) + 1);
                              fetch('/count-click');
                            ">Increment</button>
                            """);
            default ->
                    document(
                            "Click actions",
                            """
                            <p id="counter">0</p><button onclick="counter.textContent='1'">Increment</button>
                            <button onclick="this.innerHTML='<span>Resilient done</span>'"
                              class="before" id="generated-1">Resilient action</button>
                            """);
        };
    }

    private static String loginPage() {
        return document(
                "Sign in",
                """
                <form aria-label="Sign in" onsubmit="event.preventDefault(); location='/dashboard'">
                  <label for="email">Email</label><input id="email" type="email" required>
                  <label for="password">Password</label><input id="password" type="password" required>
                  <label><input type="checkbox" name="remember"> Remember me</label>
                  <button type="submit">Sign in</button>
                </form>
                """);
    }

    private static String dashboardPage() {
        return document(
                "Dashboard",
                """
                <nav aria-label="Primary"><a href="/navigation/one">Home</a></nav>
                <h1>Welcome</h1><p>user@example.test</p>
                <button onclick="notice.showModal()">Open notifications</button>
                <dialog id="notice" aria-label="Notifications"><h2>Notifications</h2></dialog>
                """);
    }

    private static String navigationPage(String title) {
        return document(
                title, "<h1>" + title + "</h1><a href=\"/navigation/two\">Continue navigation</a>");
    }

    private static String document(String title, String body) {
        return "<!doctype html><html lang=\"en\"><head><title>"
                + title
                + "</title></head><body><main><h1>"
                + title
                + "</h1>"
                + body
                + "</main></body></html>";
    }

    private static void download(HttpExchange exchange) throws IOException {
        byte[] payload = "WebAgent4J download fixture\n".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=report.txt");
        exchange.sendResponseHeaders(200, payload.length);
        try (var output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }

    private static void html(HttpExchange exchange, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        respond(exchange, body);
    }

    private static void text(HttpExchange exchange, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        respond(exchange, body);
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, payload.length);
        try (var output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }
}
