package io.webagent4j.browser.playwright;

import com.microsoft.playwright.BrowserContext;
import java.util.Objects;
import java.util.UUID;

/**
 * Installs tamper-resistant per-document DOM primitives used by Playwright safety checks.
 *
 * <p>The application realm can discover and invoke the immutable bridge entry point, but it cannot
 * read or replace the closure-private state used by the browser script. Candidate identities remain
 * unique per physical node, and DOM containment uses pristine primitives captured before
 * application JavaScript can monkey-patch them.
 */
final class PlaywrightCandidateIdentityBridge {

    private static final String PROPERTY_NAME = "__webagent4jCandidateIdentity_" + randomToken();

    private static final String INIT_SCRIPT =
            """
            (() => {
              const bridgeName = "%s";

              /*
               * At least one browser engine's init-script delivery is not exactly-once per
               * document: a context-registered init script can run more than once for the same
               * document (observed for iframe documents). The bridge below is installed as a
               * non-configurable property, so re-running this script unguarded on a document that
               * already has a bridge would throw from Object.defineProperty and could leave this
               * script's own re-entrant state half-built. Detect that re-entry first, before any
               * other setup, and no-op: the first installation - already bound to this exact
               * document - remains the sole authority, so a duplicate run can never install a
               * second, competing bridge or observe a torn one.
               */
              if (typeof globalThis[bridgeName] === "function") {
                return;
              }

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
              const nodeContains = Node.prototype.contains;
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

              const contains = (ancestorOrSelf, element) => {
                if (ancestorOrSelf == null || element == null) {
                  return { absent: true };
                }

                const ancestorDocument =
                  apply(ownerDocumentGetter, ancestorOrSelf, []);
                const elementDocument =
                  apply(ownerDocumentGetter, element, []);
                if (ancestorDocument !== currentDocument
                    || elementDocument !== currentDocument) {
                  return { documentMismatch: true };
                }

                if (!apply(isConnectedGetter, ancestorOrSelf, [])
                    || !apply(isConnectedGetter, element, [])) {
                  return { absent: true };
                }

                return {
                  contains: apply(nodeContains, ancestorOrSelf, [element])
                };
              };

              const invoke = (operation, first, second) => {
                if (operation === "identity") {
                  return identify(first);
                }
                if (operation === "contains") {
                  return contains(first, second);
                }
                return { unsupported: true };
              };

              defineProperty(globalThis, bridgeName, {
                value: invoke,
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
              const bridge = globalThis[bridgeName];
              if (typeof bridge !== "function") {
                return { bridgeMissing: true };
              }
              return bridge("identity", element, null);
            }
            """;

    private static final String DESCENDANT_OR_SELF_SCRIPT =
            """
            (element, ancestorOrSelf) => {
              const bridge = globalThis["%s"];
              if (typeof bridge !== "function") {
                throw new Error("WebAgent4j DOM trust bridge is unavailable");
              }

              const inspected = bridge("contains", ancestorOrSelf, element);
              if (inspected == null || typeof inspected !== "object") {
                throw new Error("WebAgent4j DOM containment inspection returned invalid data");
              }
              if (inspected.documentMismatch === true) {
                throw new Error("WebAgent4j DOM containment crossed a document boundary");
              }
              if (inspected.unsupported === true) {
                throw new Error("WebAgent4j DOM containment operation is unsupported");
              }
              if (inspected.absent === true) {
                return false;
              }
              if (typeof inspected.contains !== "boolean") {
                throw new Error("WebAgent4j DOM containment inspection returned malformed data");
              }
              return inspected.contains;
            }
            """
                    .formatted(PROPERTY_NAME);

    private PlaywrightCandidateIdentityBridge() {}

    /**
     * Installs the bridge for every future document and child-frame document in {@code context}.
     */
    static void install(BrowserContext context) {
        Objects.requireNonNull(context, "context").addInitScript(INIT_SCRIPT);
    }

    /**
     * Returns the raw install script text, for tests that need to prove it is safe to evaluate more
     * than once against the same document (a condition at least one non-Chromium engine is known to
     * produce for a context-registered init script on an iframe document) without using {@link
     * #install(BrowserContext)}'s real {@code addInitScript} registration.
     */
    static String installScript() {
        return INIT_SCRIPT;
    }

    /** Returns the browser-side probe used on an already-resolved physical element handle. */
    static String identityScript() {
        return IDENTITY_SCRIPT;
    }

    /** Returns the trusted DOM containment probe. */
    static String descendantOrSelfScript() {
        return DESCENDANT_OR_SELF_SCRIPT;
    }

    /** Returns the process-local bridge property name passed to the identity probe. */
    static String bridgeName() {
        return PROPERTY_NAME;
    }

    private static String randomToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
