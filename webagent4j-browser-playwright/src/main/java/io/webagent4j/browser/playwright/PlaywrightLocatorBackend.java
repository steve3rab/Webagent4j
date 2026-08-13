package io.webagent4j.browser.playwright;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import io.webagent4j.common.LocatorException;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.ILocatorBackend;
import io.webagent4j.locator.ILocatorEngine;
import io.webagent4j.locator.LocatorBackendCandidate;
import io.webagent4j.locator.LocatorBackendCapabilities;
import io.webagent4j.locator.LocatorBackendCapability;
import io.webagent4j.locator.LocatorBackendQuery;
import io.webagent4j.locator.LocatorBackendSearchResult;
import io.webagent4j.locator.LocatorConfig;
import io.webagent4j.locator.LocatorContext;
import io.webagent4j.locator.LocatorScope;
import io.webagent4j.locator.LocatorStrategyType;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.IFind;
import io.webagent4j.locator.api.TextMatch;
import io.webagent4j.locator.api.TextMatchType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
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

    private final Page page;
    private final ILocatorEngine engine;
    private final LocatorContext pageContext;

    PlaywrightLocatorBackend(Page page, ILocatorEngine engine, LocatorConfig config) {
        this.page = page;
        this.engine = engine;
        this.pageContext = LocatorContext.page(this, config);
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
    @SuppressWarnings("unchecked")
    public LocatorBackendSearchResult find(
            LocatorBackendQuery query,
            LocatorScope scope,
            LocatorConfig config,
            Duration timeout,
            int candidateLimit) {
        Locator root =
                scope.root()
                        .map(PlaywrightLocatorBackend::unwrap)
                        .orElseGet(() -> page.locator("html"));
        Locator resolved = resolve(root, query, config);
        int discoveredCount = resolved.count();
        int count = Math.min(discoveredCount, candidateLimit);
        List<LocatorBackendCandidate> candidates = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            Locator item = resolved.nth(index);
            Map<String, Object> identity = (Map<String, Object>) item.evaluate(IDENTITY_SCRIPT);
            ElementRole knownRole = query.role().orElse(ElementRole.UNKNOWN);
            candidates.add(
                    new LocatorBackendCandidate(
                            String.valueOf(identity.get("identity")),
                            new PlaywrightElement(item, knownRole, this, scope, config),
                            ((Number) identity.get("domOrder")).intValue()));
        }
        return new LocatorBackendSearchResult(candidates, discoveredCount, discoveredCount > count);
    }

    IFind<IElement> findOnPage() {
        return new PlaywrightFind(engine, pageContext);
    }

    IFind<IElement> findOnPage(LocatorConfig config) {
        return new PlaywrightFind(engine, LocatorContext.page(this, config));
    }

    IFind<IElement> findWithin(IElement element, LocatorScope parentScope, LocatorConfig config) {
        return new PlaywrightFind(
                engine, new LocatorContext(this, parentScope, config).within(element));
    }

    LocatorContext context() {
        return pageContext;
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
        return text.type() == TextMatchType.EXACT
                || text.type() == TextMatchType.CASE_INSENSITIVE_EXACT;
    }

    private static String attributeSelector(String name, String value) {
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

    private static Locator unwrap(IElement element) {
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
