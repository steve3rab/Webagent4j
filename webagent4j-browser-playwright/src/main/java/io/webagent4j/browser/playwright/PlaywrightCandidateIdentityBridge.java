package io.webagent4j.browser.playwright;

import com.microsoft.playwright.BrowserContext;
import java.util.Objects;
import java.util.UUID;

/**
 * Installs a tamper-resistant per-document registry for opaque Playwright candidate identities.
 *
 * <p>The application realm can discover and even invoke the immutable bridge entry point, but it
 * cannot read or replace the closure-private {@code WeakMap} state used by the browser script.
 * Invoking the bridge early can only allocate a unique identity for a node; it cannot choose,
 * collide, replace, or delete identities.
 *
 * <p>A fresh random document token is generated before application JavaScript executes. This keeps
 * the first candidate in two different documents from accidentally receiving the same stability
 * identity across navigation or replacement.
 */
final class PlaywrightCandidateIdentityBridge {

    private static final String PROPERTY_NAME = "__webagent4jCandidateIdentity_" + randomToken();

    private static final String INIT_SCRIPT =
            """
            (() => {
              const bridgeName = "%s";

              /*
               * Capture all primitives before application JavaScript can monkey-patch them.
               * The identity store itself never becomes a property of globalThis.
               */
              const apply = Reflect.apply;
              const defineProperty = Object.defineProperty;
              const getOwnPropertyDescriptor = Object.getOwnPropertyDescriptor;
              const weakMapHas = WeakMap.prototype.has;
              const weakMapGet = WeakMap.prototype.get;
              const weakMapSet = WeakMap.prototype.set;
              const querySelectorAll = Document.prototype.querySelectorAll;
              const arrayIndexOf = Array.prototype.indexOf;
              const isConnectedGetter =
                getOwnPropertyDescriptor(Node.prototype, "isConnected").get;
              const ownerDocumentGetter =
                getOwnPropertyDescriptor(Node.prototype, "ownerDocument").get;
              const currentDocument = document;

              const ids = new WeakMap();
              let sequence = 0n;

              const randomWords = new Uint32Array(4);
              const getRandomValues = crypto.getRandomValues;
              apply(getRandomValues, crypto, [randomWords]);
              let documentToken = "";
              for (let i = 0; i < randomWords.length; i++) {
                documentToken += randomWords[i].toString(16).padStart(8, "0");
              }

              const identify = element => {
                if (element == null) {
                  return { absent: true };
                }

                const ownerDocument =
                  apply(ownerDocumentGetter, element, []);
                if (ownerDocument !== currentDocument) {
                  return { documentMismatch: true };
                }

                if (!apply(isConnectedGetter, element, [])) {
                  return { absent: true };
                }

                if (!apply(weakMapHas, ids, [element])) {
                  const identity =
                    `webagent4j-${documentToken}-${++sequence}`;
                  apply(weakMapSet, ids, [element, identity]);
                }

                const all =
                  apply(querySelectorAll, currentDocument, ["*"]);
                const order =
                  apply(arrayIndexOf, all, [element]);

                return {
                  identity: apply(weakMapGet, ids, [element]),
                  domOrder: order < 0 ? 0 : order
                };
              };

              defineProperty(globalThis, bridgeName, {
                value: identify,
                enumerable: false,
                writable: false,
                configurable: false
              });
            })();
            """
                    .formatted(PROPERTY_NAME);

    private static final String IDENTITY_SCRIPT =
            """
            (element, bridgeName) => {
              const identify = globalThis[bridgeName];
              if (typeof identify !== "function") {
                return { bridgeMissing: true };
              }
              return identify(element);
            }
            """;

    private PlaywrightCandidateIdentityBridge() {}

    /**
     * Installs the bridge for every future document and child-frame document in {@code context}.
     */
    static void install(BrowserContext context) {
        Objects.requireNonNull(context, "context").addInitScript(INIT_SCRIPT);
    }

    /** Returns the browser-side probe used on the already-resolved physical element handle. */
    static String identityScript() {
        return IDENTITY_SCRIPT;
    }

    /** Returns the process-local bridge property name passed to the identity probe. */
    static String bridgeName() {
        return PROPERTY_NAME;
    }

    private static String randomToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
