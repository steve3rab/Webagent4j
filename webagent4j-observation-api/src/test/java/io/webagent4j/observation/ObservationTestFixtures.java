package io.webagent4j.observation;

import io.webagent4j.dom.ElementState;
import io.webagent4j.locator.api.ElementReference;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.LocatorDefinition;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class ObservationTestFixtures {

    static final Instant CAPTURED_AT = Instant.parse("2026-08-13T10:15:30Z");

    private ObservationTestFixtures() {}

    static Observation completeObservation() {
        SemanticElement main = element(1, ElementRole.MAIN, "Main", "Main content");
        SemanticElement heading =
                element(2, ElementRole.HEADING, "Account", "Account").withCapabilities(Set.of());
        SemanticElement link = element(3, ElementRole.LINK, "Help", "Help center");
        SemanticElement form = element(4, ElementRole.FORM, "Sign in", "");
        SemanticElement field = field(5, form.id());
        SemanticElement button =
                element(6, ElementRole.BUTTON, "Continue", "Continue")
                        .withCapabilities(
                                Set.of(ElementCapability.CLICK, ElementCapability.SUBMIT));
        SemanticElement tableElement = element(7, ElementRole.TABLE, "Invoices", "");
        SemanticElement listElement = element(8, ElementRole.LIST, "Steps", "");
        SemanticElement imageElement = element(9, ElementRole.IMAGE, "Logo", "");
        SemanticElement dialogElement = element(10, ElementRole.DIALOG, "Confirmation", "");
        SemanticElement alertElement = element(11, ElementRole.ALERT, "Saved", "Saved");
        SemanticElement tabsElement = element(12, ElementRole.TABLIST, "Views", "");
        SemanticElement tabElement = element(13, ElementRole.TAB, "Summary", "Summary");
        SemanticElement panelElement = element(14, ElementRole.TABPANEL, "Summary", "Details");
        SemanticElement menuElement = element(15, ElementRole.MENU, "Account menu", "");
        SemanticElement menuItem = element(16, ElementRole.MENUITEM, "Profile", "Profile");
        List<SemanticElement> elements =
                List.of(
                        main,
                        heading,
                        link,
                        form,
                        field,
                        button,
                        tableElement,
                        listElement,
                        imageElement,
                        dialogElement,
                        alertElement,
                        tabsElement,
                        tabElement,
                        panelElement,
                        menuElement,
                        menuItem);

        SemanticRelationship formOwns =
                new SemanticRelationship(
                        form.id(), field.id(), SemanticRelationshipType.BELONGS_TO);
        SemanticRelationship tabControls =
                new SemanticRelationship(
                        tabElement.id(), panelElement.id(), SemanticRelationshipType.CONTROLS);
        List<SemanticRelationship> relationships = List.of(formOwns, tabControls);
        TableObservation table =
                new TableObservation(
                        tableElement.id(),
                        "Invoices",
                        List.of("Number", "Total"),
                        2,
                        2,
                        List.of(List.of("A-1", "$10"), List.of("A-2", "$20")),
                        true,
                        false);
        ListObservation list =
                new ListObservation(listElement.id(), true, 3, List.of("One", "Two"), true);
        HeadingObservation headingObservation =
                new HeadingObservation(
                        heading.id(), heading.index(), heading.text(), 1, Optional.empty());
        PageMetadata metadata = metadata("https://example.test/account", "Account");
        ObservationStatistics statistics =
                new ObservationStatistics(
                        18,
                        elements.size(),
                        2,
                        7,
                        1,
                        1,
                        1,
                        1,
                        Duration.ofMillis(42),
                        List.of(
                                new ObservationTruncation(
                                        ObservationTruncationType.TABLE_ROWS,
                                        3,
                                        2,
                                        Optional.of(tableElement.id()))));
        SemanticTree tree =
                new SemanticTree(
                        List.of(
                                new SemanticTreeNode(
                                        main.id(),
                                        main.index(),
                                        main.role(),
                                        main.name(),
                                        List.of(
                                                new SemanticTreeNode(
                                                        heading.id(),
                                                        heading.index(),
                                                        heading.role(),
                                                        heading.name(),
                                                        List.of(),
                                                        false),
                                                new SemanticTreeNode(
                                                        form.id(),
                                                        form.index(),
                                                        form.role(),
                                                        form.name(),
                                                        List.of(),
                                                        true)),
                                        false)),
                        true);
        return new Observation(
                new ObservationId("observation-1"),
                metadata,
                elements,
                List.of(
                        new LandmarkObservation(
                                main.id(),
                                ElementRole.MAIN,
                                "Main",
                                elements.stream().map(SemanticElement::id).toList())),
                List.of(
                        new FormObservation(
                                form.id(),
                                "Sign in",
                                Optional.of("https://example.test/login"),
                                "post",
                                List.of(
                                        new FormFieldObservation(
                                                field.id(),
                                                form.id(),
                                                ElementRole.TEXTBOX,
                                                InputFieldType.EMAIL,
                                                "email",
                                                "Email",
                                                Optional.of("name@example.test"),
                                                true,
                                                false,
                                                true,
                                                true,
                                                false,
                                                ObservedValue.plain("user@example.test"),
                                                List.of("Personal", "Work"),
                                                true)),
                                List.of(button),
                                true)),
                List.of(
                        new NavigationObservation(
                                main.id(),
                                "Primary",
                                List.of(link),
                                Optional.of(link.id()),
                                NavigationOrientation.HORIZONTAL)),
                List.of(table),
                List.of(list),
                List.of(
                        new ImageObservation(
                                imageElement.id(),
                                "Logo",
                                Optional.of("Company logo"),
                                Optional.of("https://example.test/logo.png"),
                                120,
                                80)),
                List.of(
                        new DialogObservation(
                                dialogElement.id(),
                                "Confirmation",
                                true,
                                true,
                                List.of(button.id()))),
                List.of(new AlertObservation(alertElement.id(), ElementRole.ALERT, "Saved", true)),
                List.of(
                        new TabListObservation(
                                tabsElement.id(),
                                "Views",
                                List.of(tabElement.id()),
                                Optional.of(tabElement.id()),
                                List.of(tabControls))),
                List.of(
                        new MenuObservation(
                                menuElement.id(),
                                ElementRole.MENU,
                                "Account menu",
                                List.of(menuItem.id()))),
                relationships,
                tree,
                new PageContent(
                        List.of(headingObservation),
                        List.of("Welcome\nback", "Control \u0001 text"),
                        List.of(list),
                        List.of(table)),
                statistics,
                List.of(
                        new ObservationWarning(
                                ObservationWarningType.CAPTURE_MUTATED,
                                "The document changed during capture",
                                Optional.empty())),
                ObservationFingerprint.compute(metadata, elements, relationships));
    }

    static SemanticElement element(int index, ElementRole role, String name, String text) {
        SemanticElementId id = new SemanticElementId("element-" + index);
        ElementState interaction =
                new ElementState(
                        true,
                        true,
                        true,
                        role == ElementRole.TEXTBOX,
                        false,
                        false,
                        role == ElementRole.TAB,
                        false,
                        true,
                        true,
                        false,
                        true);
        Set<ElementCapability> capabilities =
                switch (role) {
                    case LINK, BUTTON, MENUITEM, TAB -> Set.of(ElementCapability.CLICK);
                    case TEXTBOX -> Set.of(ElementCapability.TYPE, ElementCapability.CLEAR);
                    default -> Set.of();
                };
        return new SemanticElement(
                index,
                id,
                role + "|" + name,
                role,
                name,
                text,
                role == ElementRole.LINK ? "a" : "div",
                new ObservedElementState(interaction, false, Optional.of(false)),
                new ElementReference(LocatorDefinition.forRole(role).named(name)),
                Map.of("data-testid", "item-" + index, "aria-label", name),
                capabilities,
                Optional.empty(),
                Optional.empty(),
                role == ElementRole.HEADING ? Optional.of(1) : Optional.empty(),
                Optional.empty(),
                false,
                ObservedValue.empty());
    }

    static SemanticElement field(int index, SemanticElementId formId) {
        SemanticElement element = element(index, ElementRole.TEXTBOX, "Email", "");
        return new SemanticElement(
                element.index(),
                element.id(),
                element.stableKey(),
                element.role(),
                element.accessibleName(),
                element.text(),
                "input",
                new ObservedElementState(element.state().interaction(), true, Optional.empty()),
                element.reference(),
                element.attributes(),
                element.capabilities(),
                Optional.empty(),
                Optional.of(formId),
                Optional.empty(),
                Optional.of(InputFieldType.EMAIL),
                false,
                ObservedValue.plain("user@example.test"));
    }

    static PageMetadata metadata(String url, String title) {
        return new PageMetadata(
                url,
                title,
                Optional.of(" en "),
                Optional.of("UTF-8"),
                "complete",
                CAPTURED_AT,
                new ViewportSize(1280, 720),
                Optional.of(url),
                Optional.of("Description"));
    }
}
