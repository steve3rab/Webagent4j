package io.webagent4j.browser.playwright;

import com.microsoft.playwright.ElementHandle;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Playwright-only implementation of the backend-neutral locator discovery port. */
final class PlaywrightLocatorBackend implements ILocatorBackend {

    private static final String IDENTITY_SCRIPT =
            PlaywrightCandidateIdentityBridge.identityScript();

    private static final int MAXIMUM_INSPECTIONS_PER_CANDIDATE = 8;
    private static final AtomicLong STRUCTURED_SCOPE_LEASE_SEQUENCE = new AtomicLong();

    private final Locator documentRoot;
    private final ILocatorEngine engine;
    private final LocatorContext rootContext;

    /** Creates a backend rooted at the top-level page document. */
    PlaywrightLocatorBackend(Page page, ILocatorEngine engine, LocatorConfig config) {
        this(page.locator("html"), engine, config, LocatorScope.page());
    }

    /**
     * Creates a backend rooted at a frame's own document. The root remains a lazy locator so a
     * replaced/navigated document is re-resolved by Playwright.
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
        int discoveredCount = countOrZero(resolved);
        int count = Math.min(discoveredCount, candidateLimit);
        double candidateInspectionTimeout = operationTimeoutMillis(timeout, count);
        List<LocatorBackendCandidate> candidates = new ArrayList<>(count);
        Runnable scopeIdentityValidator =
                scope.root()
                        .filter(PlaywrightElement.class::isInstance)
                        .map(PlaywrightElement.class::cast)
                        .<Runnable>map(element -> element::validateScopeIdentity)
                        .orElse(null);

        for (int index = 0; index < count; index++) {
            Locator item = resolved.nth(index);
            Map<String, Object> identity = identifyOrNull(item);
            if (identity == null) {
                continue;
            }
            ElementRole knownRole = knownRole(query);
            String identityToken = String.valueOf(identity.get("identity"));
            PlaywrightElement element =
                    new PlaywrightElement(
                            item,
                            knownRole,
                            this,
                            scope,
                            config,
                            candidateInspectionTimeout,
                            scopeIdentityValidator,
                            identityToken);
            candidates.add(
                    new LocatorBackendCandidate(
                            identityToken,
                            element,
                            ((Number) identity.get("domOrder")).intValue()));
        }
        return new LocatorBackendSearchResult(candidates, discoveredCount, discoveredCount > count);
    }

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
     * Resolves candidate identity through current element handles instead of {@code evaluateAll()}.
     *
     * <p>This avoids re-resolving a locator containing the isolated structured-scope selector in
     * Playwright's main world. The selector engine establishes the physical match first; identity
     * is then inspected on that exact node. No nested locator wait is introduced.
     *
     * <p>Package-visible so {@link PlaywrightElement#isStillTheOriginallyResolvedTarget()} can
     * reuse the exact same inspection this class uses at discovery time, rather than a second,
     * potentially inconsistent implementation.
     */
    static Map<String, Object> identifyOrNull(Locator item) {
        List<ElementHandle> handles = List.of();
        try {
            handles = item.elementHandles();
            if (handles.isEmpty()) {
                return null;
            }
            if (handles.size() > 1) {
                throw new AmbiguousLocatorException(
                        "Candidate locator became ambiguous during current-DOM identity inspection");
            }
            Object inspected =
                    handles.getFirst()
                            .evaluate(
                                    IDENTITY_SCRIPT,
                                    PlaywrightCandidateIdentityBridge.bridgeName());
            return validatedIdentityOrNull(item, inspected);
        } catch (PlaywrightException failure) {
            if (PlaywrightFailureClassifier.isFrameUnavailable(failure)) {
                return null;
            }
            if (PlaywrightFailureClassifier.isDifferentDocumentAdoptionRace(failure)) {
                if (confirmedAbsent(item, failure)) {
                    return null;
                }
                // The candidate still selector-matches something, but the browser just reported
                // its own execution-context/document lifecycle racing a frame or document
                // transition - genuinely transient, not a proven identity mismatch. Reporting it
                // as a retryable "not found this poll" (never as a match) lets the caller's
                // existing bounded wait loop absorb the settling window on the same shared
                // deadline, replaying no side effect, rather than aborting resolution outright.
                throw new LocatorNotFoundException(
                        "Candidate identity inspection raced a document transition");
            }
            throw failure;
        } finally {
            dispose(handles);
        }
    }

    /**
     * Validates the complete browser-side identity envelope before it can influence deduplication,
     * ambiguity, or stability.
     *
     * <p>{@code bridgeMissing} and {@code documentMismatch} are both browser-reported conditions
     * about {@code item}'s owning document at the exact instant the probe ran - and both engines
     * that are not Chromium are documented to have execution-context lifecycles for frame/iframe
     * documents that can briefly diverge from Chromium's around a document transition (a
     * context-registered init script re-running for the same document, or a nested frame's
     * execution context being torn down and recreated). A candidate that a fresh, synchronous
     * recheck proves is no longer even present is reported as absent (safe - the caller never
     * selects it). A candidate a fresh recheck still finds present is never silently accepted as a
     * match, but it is also never treated as a fatal, non-retryable failure: descending into frames
     * and re-resolving candidates across a document transition is exactly the kind of transient
     * condition the caller's existing bounded wait loop already exists to absorb, so this is
     * reported as a retryable "not found this poll" instead, on the same shared deadline, replaying
     * no side effect. Only once that deadline is exhausted without ever observing a proven,
     * non-racing identity does resolution fail - deterministically, and still never having accepted
     * an unproven candidate as a match.
     */
    private static Map<String, Object> validatedIdentityOrNull(Locator item, Object inspected) {
        if (inspected == null) {
            throw new LocatorException("Candidate identity inspection returned null");
        }
        if (!(inspected instanceof Map<?, ?> raw)) {
            throw new LocatorException("Candidate identity inspection returned an invalid result");
        }
        if (Boolean.TRUE.equals(raw.get("bridgeMissing"))) {
            if (confirmedAbsent(
                    item,
                    new LocatorException("Playwright candidate identity bridge is unavailable"))) {
                return null;
            }
            throw new LocatorNotFoundException(
                    "Candidate identity bridge was unavailable during a document transition");
        }
        if (Boolean.TRUE.equals(raw.get("documentMismatch"))) {
            if (confirmedAbsent(
                    item,
                    new LocatorException(
                            "Candidate identity inspection observed a different document"))) {
                return null;
            }
            throw new LocatorNotFoundException(
                    "Candidate identity inspection raced a document transition");
        }
        if (Boolean.TRUE.equals(raw.get("absent"))) {
            return null;
        }

        Object identityValue = raw.get("identity");
        Object domOrderValue = raw.get("domOrder");
        if (!(identityValue instanceof String identity)
                || identity.isBlank()
                || !identity.startsWith("webagent4j-")
                || !(domOrderValue instanceof Number number)) {
            throw new LocatorException("Candidate identity inspection returned malformed data");
        }

        double rawOrder = number.doubleValue();
        if (!Double.isFinite(rawOrder)
                || rawOrder < 0.0
                || rawOrder != Math.rint(rawOrder)
                || rawOrder > Integer.MAX_VALUE) {
            throw new LocatorException(
                    "Candidate identity inspection returned an invalid DOM order");
        }

        return Map.of("identity", identity, "domOrder", (int) rawOrder);
    }

    private static void dispose(List<ElementHandle> handles) {
        for (ElementHandle handle : handles) {
            try {
                handle.dispose();
            } catch (PlaywrightException ignored) {
                // Best-effort cleanup only. Never replace the semantic result/failure of the probe.
            }
        }
    }

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

    /**
     * Returns true only when a fresh synchronous count proves that a failed current-DOM locator is
     * gone. This is shared by timeout, cross-document handle-adoption races, and JS-detected
     * document-mismatch/bridge-missing signals ({@link #validatedIdentityOrNull}) - every condition
     * where the underlying browser engine reported something about {@code locator}'s document
     * identity that a synchronous re-check can independently confirm or refute.
     */
    private static boolean confirmedAbsent(Locator locator, RuntimeException original) {
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
     * Resolves one structured-scope container with strict semantic 0/1/N classification and stable
     * physical identity.
     *
     * <p>The isolated selector engine keeps constant-size state per live DOM element: {@code
     * {stableIdentity, transientLease}}. A fresh lease is bound atomically to the sole semantic
     * match, identity is inspected on the lease-guarded physical node, and the lease is atomically
     * promoted to that stable identity. Existing stable identity is never overwritten by a
     * competing bind lease, so old scopes remain usable while later resolutions cannot retarget
     * them to a replacement node.
     */
    IElement resolveUniqueContainer(
            LocatorContext context, String text, LocatorStrategyType strategy, Duration timeout) {
        Locator root =
                context.scope().root().map(PlaywrightLocatorBackend::unwrap).orElse(documentRoot);
        Runnable ancestorValidator =
                context.scope()
                        .root()
                        .filter(PlaywrightElement.class::isInstance)
                        .map(PlaywrightElement.class::cast)
                        .<Runnable>map(element -> element::validateScopeIdentity)
                        .orElse(() -> {});

        ancestorValidator.run();

        String lease = nextStructuredScopeLease();

        Locator bindingLocator =
                structuredScopeLocator(
                        root, text, strategy, context, StructuredScopeOperation.BIND, lease);
        int currentCount = countOrZero(bindingLocator);
        if (currentCount > 1) {
            throw new AmbiguousLocatorException(
                    "Structured scope text \"" + text + "\" matched multiple containers");
        }
        if (currentCount == 0) {
            throw new LocatorNotFoundException(
                    "No structured-scope container matched \"" + text + "\"");
        }

        Locator leasedLocator =
                structuredScopeLocator(
                        root,
                        text,
                        strategy,
                        context,
                        StructuredScopeOperation.LEASE_GUARDED,
                        lease);
        Map<String, Object> inspected = identifyOrNull(leasedLocator);
        if (inspected == null) {
            throw new LocatorNotFoundException(
                    "Structured-scope container changed before physical identity capture");
        }
        String stableIdentity = String.valueOf(inspected.get("identity"));

        String promotion = lease + "\u0000" + stableIdentity;
        Locator promotionLocator =
                structuredScopeLocator(
                        root, text, strategy, context, StructuredScopeOperation.PROMOTE, promotion);
        int promotionCount = countOrZero(promotionLocator);
        if (promotionCount > 1) {
            throw new AmbiguousLocatorException(
                    "Structured scope text \""
                            + text
                            + "\" became ambiguous during identity binding");
        }
        if (promotionCount == 0) {
            throw new LocatorNotFoundException(
                    "Structured-scope container changed during physical identity binding");
        }

        Locator guardedLocator =
                structuredScopeLocator(
                        root,
                        text,
                        strategy,
                        context,
                        StructuredScopeOperation.GUARDED,
                        stableIdentity);
        requireSameUniqueContainer(guardedLocator, text);

        Runnable validator =
                () -> {
                    ancestorValidator.run();
                    requireSameUniqueContainer(guardedLocator, text);
                };

        return new PlaywrightElement(
                guardedLocator,
                ElementRole.UNKNOWN,
                this,
                context.scope(),
                context.config(),
                operationTimeoutMillis(timeout, 1),
                validator,
                stableIdentity);
    }

    private static String nextStructuredScopeLease() {
        long sequence = STRUCTURED_SCOPE_LEASE_SEQUENCE.incrementAndGet();
        if (sequence == 0L) {
            throw new LocatorException("Structured-scope lease sequence exhausted");
        }
        return "scope-lease-" + Long.toUnsignedString(sequence);
    }

    private static void requireSameUniqueContainer(Locator guardedLocator, String text) {
        int currentCount = countOrZero(guardedLocator);
        if (currentCount > 1) {
            throw new AmbiguousLocatorException(
                    "Structured scope text \"" + text + "\" became ambiguous before use");
        }
        if (currentCount == 0) {
            throw new LocatorNotFoundException(
                    "Structured-scope container disappeared or changed identity before use");
        }
    }

    private static Locator structuredScopeLocator(
            Locator root,
            String text,
            LocatorStrategyType strategy,
            LocatorContext context,
            StructuredScopeOperation operation,
            String binding) {
        String source =
                switch (strategy) {
                    case ACCESSIBLE_NAME -> "a";
                    case VISIBLE_TEXT -> "t";
                    default ->
                            throw new LocatorException(
                                    "Unsupported structured-scope strategy: " + strategy);
                };
        String selector =
                PlaywrightDomInspectionScripts.STRUCTURED_SCOPE_SELECTOR_ENGINE
                        + "="
                        + source
                        + "."
                        + operation.code()
                        + "."
                        + encodeSelectorPart(context.config().locale().toLanguageTag())
                        + "."
                        + encodeSelectorPart(text)
                        + "."
                        + encodeSelectorPart(binding);
        return root.locator(selector);
    }

    private enum StructuredScopeOperation {
        BIND("b"),
        LEASE_GUARDED("l"),
        PROMOTE("p"),
        GUARDED("g");

        private final String code;

        StructuredScopeOperation(String code) {
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    private static String encodeSelectorPart(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
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
