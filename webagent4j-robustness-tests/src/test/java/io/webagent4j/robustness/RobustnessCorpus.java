package io.webagent4j.robustness;

import io.webagent4j.locator.api.ElementRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class RobustnessCorpus {

    private RobustnessCorpus() {}

    static List<RobustnessScenario> scenarios() {
        List<RobustnessScenario> scenarios = new ArrayList<>();
        addClean(scenarios);
        addAria(scenarios);
        addDynamic(scenarios);
        addAmbiguous(scenarios);
        addForms(scenarios);
        addActions(scenarios);
        addHostile(scenarios);
        if (scenarios.size() != 100) {
            throw new IllegalStateException("robustness corpus must contain exactly 100 scenarios");
        }
        return List.copyOf(scenarios);
    }

    private static void addClean(List<RobustnessScenario> scenarios) {
        List<Definition> definitions =
                List.of(
                        def(ElementRole.TEXTBOX, "Email address", "clean-email"),
                        def(ElementRole.TEXTBOX, "Display name", "clean-name"),
                        def(ElementRole.TEXTBOX, "Biography", "clean-bio"),
                        def(ElementRole.CHECKBOX, "Accept terms", "clean-terms"),
                        def(ElementRole.CHECKBOX, "Product news", "clean-news"),
                        def(ElementRole.RADIO, "Basic plan", "clean-basic"),
                        def(ElementRole.RADIO, "Pro plan", "clean-pro"),
                        def(ElementRole.SELECT, "Country", "clean-country"),
                        def(ElementRole.BUTTON, "Save profile", "clean-save"),
                        def(ElementRole.BUTTON, "Reset profile", "clean-reset"),
                        def(ElementRole.BUTTON, "Submit settings", "clean-submit"),
                        def(ElementRole.LINK, "Security settings", "clean-security"),
                        def(ElementRole.LINK, "Privacy settings", "clean-privacy"),
                        def(ElementRole.LINK, "Billing settings", "clean-billing"),
                        def(ElementRole.BUTTON, "Open menu", "clean-menu"),
                        def(ElementRole.BUTTON, "Get help", "clean-help"),
                        def(ElementRole.BUTTON, "Export data", "clean-export"),
                        def(ElementRole.IMAGE, "Current profile picture", "clean-avatar"),
                        def(ElementRole.HEADING, "Notification preferences", "clean-preferences"),
                        def(ElementRole.FORM, "Search documentation", "clean-search-form"));
        for (int index = 0; index < definitions.size(); index++) {
            Definition definition = definitions.get(index);
            scenarios.add(
                    scenario(
                            id("CLEAN", index + 1),
                            "Resolve clean semantic " + definition.role().name().toLowerCase(),
                            "clean/semantic-controls.html",
                            DifficultyLevel.LEVEL_1_CLEAN,
                            ScenarioExpectation.MUST_RESOLVE_EXACT,
                            Set.of(RobustnessTag.LABEL),
                            definition,
                            MatchMode.EXACT_NAME));
        }
    }

    private static void addAria(List<RobustnessScenario> scenarios) {
        List<Definition> definitions =
                List.of(
                        def(ElementRole.BUTTON, "Proceed to checkout", "aria-checkout"),
                        def(ElementRole.BUTTON, "Search products", "aria-search"),
                        def(ElementRole.BUTTON, "Delete file", "aria-delete"),
                        def(ElementRole.BUTTON, "Share document", "aria-share"),
                        def(ElementRole.BUTTON, "Close dialog", "aria-close"),
                        def(ElementRole.BUTTON, "Open notifications", "aria-notifications"),
                        def(ElementRole.BUTTON, "Custom submit", "aria-custom-submit"),
                        def(ElementRole.SWITCH, "Dark mode", "aria-dark-mode"),
                        def(ElementRole.CHECKBOX, "Marketing consent", "aria-consent"),
                        def(ElementRole.RADIO, "Express delivery", "aria-delivery"),
                        def(ElementRole.TAB, "Description tab", "aria-description-tab"),
                        def(ElementRole.TAB, "Reviews tab", "aria-reviews-tab"),
                        def(ElementRole.MENU, "Account actions", "aria-account-menu"),
                        def(ElementRole.MENUITEM, "Edit account", "aria-edit-account"),
                        def(ElementRole.MENUITEM, "Sign out account", "aria-sign-out"),
                        def(ElementRole.DIALOG, "Privacy notice", "aria-privacy-dialog"),
                        def(ElementRole.ALERTDIALOG, "Delete confirmation", "aria-delete-dialog"),
                        def(ElementRole.STATUS, "Upload status", "aria-upload-status"),
                        def(ElementRole.IMAGE, "Quarterly revenue chart", "aria-chart"),
                        def(ElementRole.LINK, "Open support center", "aria-support"));
        for (int index = 0; index < definitions.size(); index++) {
            scenarios.add(
                    scenario(
                            id("ARIA", index + 1),
                            "Resolve standards-based ARIA control " + (index + 1),
                            "aria/accessible-controls.html",
                            DifficultyLevel.LEVEL_1_CLEAN,
                            ScenarioExpectation.MUST_RESOLVE_EXACT,
                            Set.of(RobustnessTag.ARIA),
                            definitions.get(index),
                            MatchMode.EXACT_NAME));
        }
    }

    private static void addDynamic(List<RobustnessScenario> scenarios) {
        List<String> names =
                List.of(
                        "Delayed save",
                        "Delayed send",
                        "Delayed confirm",
                        "Delayed publish",
                        "Delayed continue",
                        "Replaced cart",
                        "Replaced account",
                        "Replaced address",
                        "Replaced payment",
                        "Replaced profile",
                        "Stable menu",
                        "Stable dialog",
                        "Stable search",
                        "Stable upload",
                        "Stable download");
        for (int index = 0; index < names.size(); index++) {
            String query =
                    index < 3
                            ? List.of("Delayed sav", "Delayed sendt", "Delayed confrim").get(index)
                            : names.get(index);
            Definition definition = def(ElementRole.BUTTON, query, "dynamic-" + (index + 1));
            scenarios.add(
                    configured(
                            scenario(
                                    id("DYNAMIC", index + 1),
                                    "Wait for deterministic dynamic control " + names.get(index),
                                    "dynamic/dynamic-controls.html",
                                    DifficultyLevel.LEVEL_2_DYNAMIC,
                                    index < 3
                                            ? ScenarioExpectation.MUST_RESOLVE_FUZZY
                                            : ScenarioExpectation.MUST_RESOLVE_EXACT,
                                    index < 3
                                            ? Set.of(RobustnessTag.DYNAMIC, RobustnessTag.FUZZY)
                                            : Set.of(RobustnessTag.DYNAMIC),
                                    definition,
                                    index < 3 ? MatchMode.FUZZY_NAME : MatchMode.EXACT_NAME),
                            true,
                            false,
                            false,
                            true,
                            1500));
        }
    }

    private static void addAmbiguous(List<RobustnessScenario> scenarios) {
        List<Definition> definitions =
                List.of(
                        def(ElementRole.BUTTON, "Continue checkout", ""),
                        def(ElementRole.BUTTON, "Add product", ""),
                        def(ElementRole.BUTTON, "Validate address", ""),
                        def(ElementRole.BUTTON, "Next step", ""),
                        def(ElementRole.BUTTON, "Choose item", ""),
                        def(ElementRole.BUTTON, "Open options", ""),
                        def(ElementRole.BUTTON, "Confirm order", ""),
                        def(ElementRole.BUTTON, "Save changes", ""),
                        def(ElementRole.BUTTON, "Send message", ""),
                        def(ElementRole.BUTTON, "Publish article", ""),
                        def(ElementRole.LINK, "Read details", ""),
                        def(ElementRole.CHECKBOX, "Enable feature", ""),
                        def(ElementRole.RADIO, "Standard delivery", ""),
                        def(ElementRole.BUTTON, "Options", ""),
                        def(ElementRole.BUTTON, "Icon action", ""));
        for (int index = 0; index < definitions.size(); index++) {
            scenarios.add(
                    scenario(
                            id("AMBIGUOUS", index + 1),
                            "Reject equivalent duplicate candidates " + (index + 1),
                            "ambiguous/duplicate-controls.html",
                            DifficultyLevel.LEVEL_4_AMBIGUOUS,
                            ScenarioExpectation.MUST_BE_AMBIGUOUS,
                            Set.of(RobustnessTag.AMBIGUOUS, RobustnessTag.SECURITY),
                            definitions.get(index),
                            MatchMode.EXACT_NAME));
        }
    }

    private static void addForms(List<RobustnessScenario> scenarios) {
        List<String> forms = List.of("Billing", "Shipping", "Login", "Newsletter", "Search");
        for (int index = 0; index < forms.size(); index++) {
            String form = forms.get(index);
            String prefix = form.toLowerCase();
            scenarios.add(
                    scoped(
                            scenario(
                                    id("FORM", index * 2 + 1),
                                    "Resolve duplicate Email field within " + form,
                                    "forms/scoped-forms.html",
                                    DifficultyLevel.LEVEL_3_PARTIAL_SEMANTICS,
                                    ScenarioExpectation.MUST_RESOLVE_EXACT,
                                    Set.of(RobustnessTag.FORM, RobustnessTag.LABEL),
                                    def(ElementRole.TEXTBOX, "Email", "form-" + prefix + "-email"),
                                    MatchMode.LABEL),
                            ElementRole.FORM,
                            form));
            String buttonName = index < 2 ? "Continue" : "Submit";
            scenarios.add(
                    scoped(
                            scenario(
                                    id("FORM", index * 2 + 2),
                                    "Resolve duplicate action within " + form,
                                    "forms/scoped-forms.html",
                                    DifficultyLevel.LEVEL_3_PARTIAL_SEMANTICS,
                                    ScenarioExpectation.MUST_RESOLVE_EXACT,
                                    Set.of(RobustnessTag.FORM),
                                    def(
                                            ElementRole.BUTTON,
                                            buttonName,
                                            "form-" + prefix + "-" + buttonName.toLowerCase()),
                                    MatchMode.EXACT_NAME),
                            ElementRole.FORM,
                            form));
        }
    }

    private static void addActions(List<RobustnessScenario> scenarios) {
        List<Definition> definitions =
                List.of(
                        def(ElementRole.BUTTON, "Log in safely", "action-login"),
                        def(ElementRole.BUTTON, "Add selected product", "action-cart"),
                        def(ElementRole.BUTTON, "Open account modal", "action-modal"),
                        def(ElementRole.BUTTON, "Save document", "action-save"),
                        def(ElementRole.BUTTON, "Send notification", "action-send"),
                        def(ElementRole.BUTTON, "Publish release", "action-publish"),
                        def(ElementRole.BUTTON, "Confirm reservation", "action-confirm"),
                        def(ElementRole.BUTTON, "Prepare download", "action-download"),
                        def(ElementRole.BUTTON, "Confirm upload", "action-upload"),
                        def(ElementRole.BUTTON, "Finish workflow", "action-finish"));
        for (int index = 0; index < definitions.size(); index++) {
            scenarios.add(
                    scenario(
                            id("ACTION", index + 1),
                            "Execute and independently verify action " + (index + 1),
                            "actions/tracked-actions.html",
                            DifficultyLevel.LEVEL_2_DYNAMIC,
                            ScenarioExpectation.MUST_EXECUTE_AND_VERIFY,
                            Set.of(RobustnessTag.ACTION, RobustnessTag.SECURITY),
                            definitions.get(index),
                            MatchMode.EXACT_NAME));
        }
    }

    private static void addHostile(List<RobustnessScenario> scenarios) {
        List<Definition> fuzzy =
                List.of(
                        def(ElementRole.BUTTON, "Delete", ""),
                        def(ElementRole.BUTTON, "Save", ""),
                        def(ElementRole.BUTTON, "Cancel", ""),
                        def(ElementRole.BUTTON, "Publish", ""),
                        def(ElementRole.BUTTON, "Add", ""));
        for (int index = 0; index < fuzzy.size(); index++) {
            scenarios.add(
                    scenario(
                            id("HOSTILE", index + 1),
                            "Reject dangerous fuzzy false positive " + fuzzy.get(index).name(),
                            "hostile/unsafe-matches.html",
                            DifficultyLevel.LEVEL_5_HOSTILE,
                            ScenarioExpectation.MUST_BE_UNRESOLVABLE,
                            Set.of(RobustnessTag.FUZZY, RobustnessTag.SECURITY),
                            fuzzy.get(index),
                            MatchMode.FUZZY_NAME));
        }
        scenarios.add(
                scenario(
                        "HOSTILE-006",
                        "Reject icon-only clickable div without semantics",
                        "hostile/unsafe-matches.html",
                        DifficultyLevel.LEVEL_5_HOSTILE,
                        ScenarioExpectation.MUST_BE_UNRESOLVABLE,
                        Set.of(RobustnessTag.SECURITY),
                        def(ElementRole.BUTTON, "Invisible icon action", ""),
                        MatchMode.EXACT_NAME));
        scenarios.add(
                scenario(
                        "HOSTILE-007",
                        "Degrade gracefully for missing aria-labelledby target",
                        "hostile/unsafe-matches.html",
                        DifficultyLevel.LEVEL_5_HOSTILE,
                        ScenarioExpectation.MUST_BE_UNRESOLVABLE,
                        Set.of(RobustnessTag.ARIA, RobustnessTag.SECURITY),
                        def(ElementRole.BUTTON, "Missing accessible label", ""),
                        MatchMode.EXACT_NAME));
        scenarios.add(
                configured(
                        scenario(
                                "HOSTILE-008",
                                "Reject disabled matching control as not interactable",
                                "hostile/unsafe-matches.html",
                                DifficultyLevel.LEVEL_5_HOSTILE,
                                ScenarioExpectation.MUST_FAIL_INTERACTABILITY,
                                Set.of(RobustnessTag.SECURITY),
                                def(ElementRole.BUTTON, "Disabled checkout", ""),
                                MatchMode.EXACT_NAME),
                        false,
                        true,
                        false,
                        false,
                        0));
        scenarios.add(
                configured(
                        scenario(
                                "HOSTILE-009",
                                "Reject overlaid matching control as not interactable",
                                "hostile/unsafe-matches.html",
                                DifficultyLevel.LEVEL_5_HOSTILE,
                                ScenarioExpectation.MUST_FAIL_INTERACTABILITY,
                                Set.of(RobustnessTag.OVERLAY, RobustnessTag.SECURITY),
                                def(ElementRole.BUTTON, "Covered checkout", ""),
                                MatchMode.EXACT_NAME),
                        false,
                        false,
                        true,
                        false,
                        0));
        scenarios.add(
                configured(
                        scenario(
                                "HOSTILE-010",
                                "Bound waiting for a control that never appears",
                                "hostile/unsafe-matches.html",
                                DifficultyLevel.LEVEL_5_HOSTILE,
                                ScenarioExpectation.MUST_TIMEOUT,
                                Set.of(RobustnessTag.DYNAMIC, RobustnessTag.SECURITY),
                                def(ElementRole.BUTTON, "Never appears", ""),
                                MatchMode.EXACT_NAME),
                        true,
                        false,
                        false,
                        true,
                        120));
    }

    private static RobustnessScenario scenario(
            String id,
            String description,
            String fixture,
            DifficultyLevel difficulty,
            ScenarioExpectation expectation,
            Set<RobustnessTag> tags,
            Definition definition,
            MatchMode match) {
        return new RobustnessScenario(
                id,
                description,
                fixture,
                difficulty,
                expectation,
                tags,
                definition.role(),
                match,
                definition.name(),
                definition.target(),
                null,
                "",
                false,
                false,
                false,
                false,
                0);
    }

    private static RobustnessScenario configured(
            RobustnessScenario scenario,
            boolean visibleOnly,
            boolean enabledOnly,
            boolean clickableOnly,
            boolean waitUntilVisible,
            long timeoutMillis) {
        return new RobustnessScenario(
                scenario.id(),
                scenario.description(),
                scenario.fixture(),
                scenario.difficulty(),
                scenario.expectation(),
                scenario.tags(),
                scenario.role(),
                scenario.match(),
                scenario.query(),
                scenario.expectedTarget(),
                scenario.scopeRole(),
                scenario.scopeName(),
                visibleOnly,
                enabledOnly,
                clickableOnly,
                waitUntilVisible,
                timeoutMillis);
    }

    private static RobustnessScenario scoped(
            RobustnessScenario scenario, ElementRole scopeRole, String scopeName) {
        return new RobustnessScenario(
                scenario.id(),
                scenario.description(),
                scenario.fixture(),
                scenario.difficulty(),
                scenario.expectation(),
                scenario.tags(),
                scenario.role(),
                scenario.match(),
                scenario.query(),
                scenario.expectedTarget(),
                scopeRole,
                scopeName,
                scenario.visibleOnly(),
                scenario.enabledOnly(),
                scenario.clickableOnly(),
                scenario.waitUntilVisible(),
                scenario.timeoutMillis());
    }

    private static Definition def(ElementRole role, String name, String target) {
        return new Definition(role, name, target);
    }

    private static String id(String prefix, int number) {
        return "%s-%03d".formatted(prefix, number);
    }

    private record Definition(ElementRole role, String name, String target) {}
}
