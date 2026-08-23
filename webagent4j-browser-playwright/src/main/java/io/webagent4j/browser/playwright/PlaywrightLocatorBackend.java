package io.webagent4j.browser.playwright;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.AriaRole;
import io.webagent4j.common.LocatorException;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.AmbiguousLocatorException;
import io.webagent4j.locator.ILocatorBackend;
import io.webagent4j.locator.ILocatorEngine;
import io.webagent4j.locator.LocatorBackendCandidate;
import io.webagent4j.locator.LocatorBackendCapabilities;
import io.webagent4j.locator.LocatorBackendCapability;
import io.webagent4j.locator.LocatorBackendQuery;
import io.webagent4j.locator.LocatorBackendSearchResult;
import io.webagent4j.locator.LocatorConfig;
import io.webagent4j.locator.LocatorContext;
import io.webagent4j.locator.LocatorNotFoundException;
import io.webagent4j.locator.LocatorScope;
import io.webagent4j.locator.LocatorStrategyType;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.IFind;
import io.webagent4j.locator.api.TextMatch;
import io.webagent4j.locator.api.TextMatchType;
import io.webagent4j.wait.WaitBudget;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Playwright-only implementation of the backend-neutral locator discovery port. */
final class PlaywrightLocatorBackend implements ILocatorBackend {

    private static final String IDENTITY_SCRIPT =
            """
            element => {
              globalThis.__webagent4jLocatorIds ||= new WeakMap();
              globalThis.__webagent4jLocatorSequence ||= 0;
              if (!globalThis.__webagent4jLocatorIds.has(element)) {
                globalThis.__webagent4jLocatorIds.set(
                  element, `webagent4j-${++globalThis.__webagent4jLocatorSequence}`);
              }
              const order = Array.prototype.indexOf.call(
                document.querySelectorAll('*'), element);
              return {
                identity: globalThis.__webagent4jLocatorIds.get(element),
                domOrder: Math.max(0, order)
              };
            }
            """;

    private static final String CURRENT_IDENTITY_SCRIPT =
            "elements => { const identify = "
                    + IDENTITY_SCRIPT
                    + "; return elements.length === 0 ? null : identify(elements[0]); }";

    private static final int MAXIMUM_INSPECTIONS_PER_CANDIDATE = 8;

    private final Locator documentRoot;
    private final ILocatorEngine engine;
    private final LocatorContext rootContext;

    /** Creates a backend rooted at the top-level page document. */
    PlaywrightLocatorBackend(Page page, ILocatorEngine engine, LocatorConfig config) {
        this(page.locator("html"), engine, config, LocatorScope.page());
    }

    /**
     * Creates a backend rooted at a frame's own document instead of the top-level page: {@code
     * documentRoot} is a lazily-resolving {@link Locator} (for example {@code
     * frameLocator.locator("html")}) so every query issued through this backend re-resolves the
     * frame's current document fresh on each real Playwright call, never reusing a handle captured
     * against a document that has since been replaced or navigated away from.
     */
    PlaywrightLocatorBackend(
            Locator documentRoot, ILocatorEngine engine, LocatorConfig config, LocatorScope scope) {
        this.documentRoot = documentRoot;
        this.engine = engine;
        this.rootContext = new LocatorContext(this, scope, config);
    }

    @Override
    public LocatorBackendCapabilities capabilities() {
        EnumSet<LocatorStrategyType> strategies = EnumSet.allOf(LocatorStrategyType.class);
        strategies.remove(LocatorStrategyType.CUSTOM);
        return new LocatorBackendCapabilities(
                strategies,
                Set.of(
                        LocatorBackendCapability.NATIVE_SEMANTICS,
                        LocatorBackendCapability.RE_RESOLUTION,
                        LocatorBackendCapability.SCOPED_SEARCH,
                        LocatorBackendCapability.ELEMENT_STATE,
                        LocatorBackendCapability.VIEWPORT,
                        LocatorBackendCapability.COVERAGE,
                        LocatorBackendCapability.INTERACTABILITY));
    }

    @Override
    public LocatorBackendSearchResult find(
            LocatorBackendQuery query,
            LocatorScope scope,
            LocatorConfig config,
            Duration timeout,
            int candidateLimit) {
        Locator root = scope.root().map(PlaywrightLocatorBackend::unwrap).orElse(documentRoot);
        Locator resolved = resolve(root, query, config);
        WaitBudget inspectionBudget = WaitBudget.start(timeout, System::nanoTime);
        int discoveredCount = countOrZero(resolved);
        int count = Math.min(discoveredCount, candidateLimit);
        List<LocatorBackendCandidate> candidates = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            if (inspectionBudget.expired()) {
                return new LocatorBackendSearchResult(
                        List.of(), discoveredCount, discoveredCount > 0);
            }
            Locator item = resolved.nth(index);
            Map<String, Object> identity = identifyOrNull(item);
            if (identity == null) {
                // count() confirmed this candidate a moment ago, but it (or, for a frame-scoped
                // backend, the whole document containing it) was torn down before evaluate()
                // reached it - the same "vanished between check and use" outcome as a detached
                // explicit scope elsewhere in this class, so it is dropped from this poll's
                // candidates rather than surfaced as a raw backend failure: the caller's
                // WaitEngine poll retries and picks it up as a normal "not currently present"
                // result.
                continue;
            }
            ElementRole knownRole = knownRole(query);
            candidates.add(
                    new LocatorBackendCandidate(
                            String.valueOf(identity.get("identity")),
                            new PlaywrightElement(
                                    item, knownRole, this, scope, config, inspectionBudget),
                            ((Number) identity.get("domOrder")).intValue()));
        }
        return new LocatorBackendSearchResult(candidates, discoveredCount, discoveredCount > count);
    }

    /**
     * Counts the current matches without leaking a disappearance signal when a lazily resolved
     * frame root vanishes between scope resolution and discovery. A timeout is absorbed only after
     * a fresh count proves absence; Playwright's canonical frame-detached protocol failure is
     * already definitive. A still-present root, a failed recheck, and every other backend failure
     * propagate unchanged.
     */
    private static int countOrZero(Locator resolved) {
        try {
            return resolved.count();
        } catch (TimeoutError vanishedFrameRoot) {
            if (confirmedAbsent(resolved, vanishedFrameRoot)) {
                return 0;
            }
            throw vanishedFrameRoot;
        } catch (PlaywrightException failure) {
            if (PlaywrightFailureClassifier.isFrameUnavailable(failure)) {
                return 0;
            }
            throw failure;
        }
    }

    /**
     * Evaluates {@link #IDENTITY_SCRIPT} against {@code item}, bounded to the candidate's share of
     * the caller's remaining resolution budget. This avoids both a fixed timeout that is too short
     * on a loaded host and Playwright's multi-second default actionability wait silently
     * multiplying the outer budget. Returns {@code null} only when a timeout is followed by a fresh
     * absence proof or Playwright explicitly reports that the owning frame was detached. A
     * still-present candidate, a failed recheck, and every other runtime failure propagate
     * unchanged.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> identifyOrNull(Locator item) {
        try {
            return (Map<String, Object>) item.evaluateAll(CURRENT_IDENTITY_SCRIPT);
        } catch (TimeoutError vanished) {
            if (confirmedAbsent(item, vanished)) {
                return null;
            }
            throw vanished;
        } catch (PlaywrightException failure) {
            if (PlaywrightFailureClassifier.isFrameUnavailable(failure)) {
                return null;
            }
            throw failure;
        }
    }

    /**
     * Reserves an equal share for identity evaluation and every possible inspection of every
     * discovered candidate. The sum of all internal Playwright timeouts therefore cannot exceed the
     * caller's remaining budget.
     */
    static double inspectionTimeoutMillis(Duration timeout, int candidateCount) {
        return operationTimeoutMillis(timeout, candidateOperationCount(candidateCount));
    }

    static double identityTimeoutMillis(Duration timeout, int candidateCount) {
        return operationTimeoutMillis(timeout, candidateOperationCount(candidateCount));
    }

    static double operationTimeoutMillis(Duration timeout, long operationCount) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        double totalMillis = timeout.getSeconds() * 1_000.0 + timeout.getNano() / 1_000_000.0;
        long boundedOperationCount = Math.max(1L, operationCount);
        return totalMillis / boundedOperationCount;
    }

    static double requirePositivePlaywrightTimeout(double timeoutMillis, String operation) {
        if (!(timeoutMillis > 0.0)) {
            throw new TimeoutError(operation + " exhausted the caller timeout");
        }
        return timeoutMillis;
    }

    private static long candidateOperationCount(int candidateCount) {
        long boundedCandidateCount = Math.max(1L, candidateCount);
        return boundedCandidateCount * (MAXIMUM_INSPECTIONS_PER_CANDIDATE + 1L);
    }

    /** Returns true only when a fresh synchronous count proves the timed-out locator is gone. */
    private static boolean confirmedAbsent(Locator locator, TimeoutError original) {
        try {
            return locator.count() == 0;
        } catch (PlaywrightException recheckFailure) {
            if (PlaywrightFailureClassifier.isFrameUnavailable(recheckFailure)) {
                return true;
            }
            original.addSuppressed(recheckFailure);
            throw original;
        } catch (RuntimeException recheckFailure) {
            original.addSuppressed(recheckFailure);
            throw original;
        }
    }

    private static ElementRole knownRole(LocatorBackendQuery query) {
        boolean guaranteedByDiscovery =
                query.strategy() == LocatorStrategyType.ROLE
                        || query.strategy() == LocatorStrategyType.ACCESSIBLE_NAME
                        || query.strategy() == LocatorStrategyType.FUZZY_TEXT;
        return guaranteedByDiscovery
                ? query.role().orElse(ElementRole.UNKNOWN)
                : ElementRole.UNKNOWN;
    }

    IFind<IElement> findOnPage() {
        return new PlaywrightFind(engine, rootContext);
    }

    IFind<IElement> findOnPage(LocatorConfig config) {
        return new PlaywrightFind(engine, new LocatorContext(this, rootContext.scope(), config));
    }

    IFind<IElement> findWithin(IElement element, LocatorScope parentScope, LocatorConfig config) {
        return new PlaywrightFind(
                engine, new LocatorContext(this, parentScope, config).within(element));
    }

    LocatorContext context() {
        return rootContext;
    }

    /**
     * Performs one caller-bounded 0/1/N classification for a structured-scope container. Unlike a
     * nested locator-engine call, this method never starts an independent retry deadline. Every
     * current candidate is inspected so a match from one accessible-name source cannot hide a
     * second match from another source.
     */
    IElement resolveUniqueContainer(
            LocatorContext context, String text, LocatorStrategyType strategy, Duration timeout) {
        Locator root =
                context.scope().root().map(PlaywrightLocatorBackend::unwrap).orElse(documentRoot);
        WaitBudget inspectionBudget = WaitBudget.start(timeout, System::nanoTime);
        if (inspectionBudget.expired()) {
            throw new LocatorNotFoundException("Structured-scope resolution budget was exhausted");
        }
        List<Integer> matchingIndices =
                matchingContainerIndices(root, text, strategy, context, inspectionBudget);
        if (matchingIndices.size() > 1) {
            throw new AmbiguousLocatorException(
                    "Structured scope text \"" + text + "\" matched multiple containers");
        }
        if (matchingIndices.isEmpty()) {
            throw new LocatorNotFoundException(
                    "No structured-scope container matched \"" + text + "\"");
        }
        return new PlaywrightElement(
                root.locator("*").nth(matchingIndices.get(0)),
                ElementRole.UNKNOWN,
                this,
                context.scope(),
                context.config(),
                inspectionBudget);
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> matchingContainerIndices(
            Locator root,
            String text,
            LocatorStrategyType strategy,
            LocatorContext context,
            WaitBudget budget) {
        try {
            List<Number> raw =
                    (List<Number>)
                            root.locator("*")
                                    .evaluateAll(
                                            PlaywrightDomInspectionScripts
                                                    .MATCHING_CONTAINER_INDICES_FUNCTION,
                                            Map.of(
                                                    "text",
                                                    text,
                                                    "locale",
                                                    context.config().locale().toLanguageTag(),
                                                    "accessible",
                                                    strategy
                                                            == LocatorStrategyType
                                                                    .ACCESSIBLE_NAME));
            return raw.stream().map(Number::intValue).toList();
        } catch (TimeoutError vanished) {
            if (confirmedAbsent(root, vanished)) {
                throw new LocatorNotFoundException("Structured scope root disappeared");
            }
            throw vanished;
        } catch (PlaywrightException failure) {
            if (PlaywrightFailureClassifier.isFrameUnavailable(failure)) {
                throw new LocatorNotFoundException("Structured scope frame disappeared");
            }
            throw failure;
        }
    }

    private Locator resolve(Locator root, LocatorBackendQuery query, LocatorConfig config) {
        return switch (query.strategy()) {
            case ROLE ->
                    query.role()
                            .map(
                                    role ->
                                            root.getByRole(
                                                    ariaRole(role),
                                                    new Locator.GetByRoleOptions()
                                                            .setIncludeHidden(true)))
                            .orElseGet(() -> root.locator("*"));
            case ACCESSIBLE_NAME -> accessibleName(root, query);
            case LABEL -> byLabel(root, query.text().orElseThrow());
            case PLACEHOLDER -> byPlaceholder(root, query.text().orElseThrow());
            case TITLE -> byTitle(root, query.text().orElseThrow());
            case ALT_TEXT -> byAltText(root, query.text().orElseThrow());
            case VISIBLE_TEXT -> byText(root, query.text().orElseThrow());
            case FUZZY_TEXT ->
                    query.role()
                            .map(
                                    role ->
                                            root.getByRole(
                                                    ariaRole(role),
                                                    new Locator.GetByRoleOptions()
                                                            .setIncludeHidden(true)))
                            .orElseGet(
                                    () ->
                                            root.locator(
                                                    "a,button,input,textarea,select,img,[role]"));
            case ID -> root.locator(attributeSelector("id", query.value().orElseThrow()));
            case NAME_ATTRIBUTE ->
                    root.locator(attributeSelector("name", query.value().orElseThrow()));
            case ATTRIBUTE ->
                    root.locator(
                            attributeSelector(
                                    query.attributeName().orElseThrow(),
                                    query.value().orElseThrow()));
            case TEST_ID -> testId(root, query.value().orElseThrow(), config);
            case CSS -> root.locator(query.value().orElseThrow());
            case XPATH -> root.locator("xpath=" + query.value().orElseThrow());
            case DOM_RELATION -> root.locator("*");
            case CUSTOM -> throw new LocatorException("Custom strategies own their discovery");
        };
    }

    private static Locator testId(Locator root, String value, LocatorConfig config) {
        String attribute = config.testIdAttribute();
        return attribute.equals("data-testid")
                ? root.getByTestId(value)
                : root.locator(attributeSelector(attribute, value));
    }

    private static Locator accessibleName(Locator root, LocatorBackendQuery query) {
        if (query.role().isEmpty()) {
            return root.locator("*");
        }
        TextMatch text = query.text().orElseThrow();
        return root.getByRole(
                ariaRole(query.role().orElseThrow()),
                new Locator.GetByRoleOptions()
                        .setName(text.value())
                        .setExact(exact(text))
                        .setIncludeHidden(true));
    }

    private static Locator byLabel(Locator root, TextMatch text) {
        return root.getByLabel(text.value(), new Locator.GetByLabelOptions().setExact(exact(text)));
    }

    private static Locator byPlaceholder(Locator root, TextMatch text) {
        return root.getByPlaceholder(
                text.value(), new Locator.GetByPlaceholderOptions().setExact(exact(text)));
    }

    private static Locator byTitle(Locator root, TextMatch text) {
        return root.getByTitle(text.value(), new Locator.GetByTitleOptions().setExact(exact(text)));
    }

    private static Locator byAltText(Locator root, TextMatch text) {
        return root.getByAltText(
                text.value(), new Locator.GetByAltTextOptions().setExact(exact(text)));
    }

    private static Locator byText(Locator root, TextMatch text) {
        return root.getByText(text.value(), new Locator.GetByTextOptions().setExact(exact(text)));
    }

    /**
     * Whether discovery should ask Playwright's native locator for its own {@code exact} matching.
     * Only {@link TextMatchType#EXACT} qualifies: Playwright's {@code exact: true} is
     * case-sensitive and does not trim/collapse whitespace, whereas {@link
     * TextMatchType#CASE_INSENSITIVE_EXACT} is still a full-string match but explicitly
     * case-insensitive. Asking Playwright for {@code exact: true} on a {@code
     * CASE_INSENSITIVE_EXACT} criterion would silently discover zero native candidates whenever the
     * DOM text differs only in case, forcing a fallback all the way to the {@code FUZZY_TEXT}
     * strategy - which {@link io.webagent4j.locator.LocatorScorer} can never mark as an exact
     * match. {@code CASE_INSENSITIVE_EXACT} instead uses Playwright's own loose ({@code exact:
     * false}) case-insensitive substring discovery here, then relies on {@link
     * io.webagent4j.locator.LocatorScorer}'s own strict, case-folded full-string comparison (via
     * {@link io.webagent4j.locator.TextMatcher}) to accept only a genuinely exact candidate and
     * reject every other loosely-discovered one.
     */
    private static boolean exact(TextMatch text) {
        return text.type() == TextMatchType.EXACT;
    }

    static String attributeSelector(String name, String value) {
        String safeName = name.replaceAll("[^A-Za-z0-9_:-]", "");
        if (safeName.isEmpty()) {
            throw new LocatorException("Attribute name is not CSS-safe: " + name);
        }
        return "["
                + safeName
                + "=\""
                + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\a ")
                + "\"]";
    }

    static Locator unwrap(IElement element) {
        if (element instanceof PlaywrightElement playwrightElement) {
            return playwrightElement.locator();
        }
        throw new LocatorException("Locator scope belongs to a different browser backend");
    }

    private static AriaRole ariaRole(ElementRole role) {
        return switch (role) {
            case LINK -> AriaRole.LINK;
            case BUTTON -> AriaRole.BUTTON;
            case TEXTBOX -> AriaRole.TEXTBOX;
            case SEARCHBOX -> AriaRole.SEARCHBOX;
            case CHECKBOX -> AriaRole.CHECKBOX;
            case RADIO -> AriaRole.RADIO;
            case SELECT -> AriaRole.COMBOBOX;
            case OPTION -> AriaRole.OPTION;
            case SLIDER -> AriaRole.SLIDER;
            case SPINBUTTON -> AriaRole.SPINBUTTON;
            case SWITCH -> AriaRole.SWITCH;
            case HEADING -> AriaRole.HEADING;
            case FORM -> AriaRole.FORM;
            case TABLE -> AriaRole.TABLE;
            case LIST -> AriaRole.LIST;
            case IMAGE -> AriaRole.IMG;
            case BANNER -> AriaRole.BANNER;
            case NAVIGATION -> AriaRole.NAVIGATION;
            case MAIN -> AriaRole.MAIN;
            case SEARCH -> AriaRole.SEARCH;
            case REGION -> AriaRole.REGION;
            case COMPLEMENTARY -> AriaRole.COMPLEMENTARY;
            case CONTENTINFO -> AriaRole.CONTENTINFO;
            case DIALOG -> AriaRole.DIALOG;
            case ALERTDIALOG -> AriaRole.ALERTDIALOG;
            case ALERT -> AriaRole.ALERT;
            case STATUS -> AriaRole.STATUS;
            case MENU -> AriaRole.MENU;
            case MENUBAR -> AriaRole.MENUBAR;
            case MENUITEM -> AriaRole.MENUITEM;
            case TAB -> AriaRole.TAB;
            case TABLIST -> AriaRole.TABLIST;
            case TABPANEL -> AriaRole.TABPANEL;
            case GRID -> AriaRole.GRID;
            case UNKNOWN -> throw new LocatorException("UNKNOWN is not a semantic locator role");
        };
    }
}
