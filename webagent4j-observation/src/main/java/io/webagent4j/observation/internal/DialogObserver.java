package io.webagent4j.observation.internal;

import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.observation.AlertObservation;
import io.webagent4j.observation.DialogObservation;
import io.webagent4j.observation.MenuObservation;
import io.webagent4j.observation.SemanticElement;
import io.webagent4j.observation.SemanticElementId;
import io.webagent4j.observation.SemanticRelationship;
import io.webagent4j.observation.SemanticRelationshipType;
import io.webagent4j.observation.TabListObservation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Extracts dialogs, alerts, explicit ARIA tabs, and explicit ARIA menus. */
public final class DialogObserver {

    public DialogResult observe(ObservedElements observed) {
        List<DialogObservation> dialogs = new ArrayList<>();
        List<AlertObservation> alerts = new ArrayList<>();
        List<TabListObservation> tabLists = new ArrayList<>();
        List<MenuObservation> menus = new ArrayList<>();
        List<SemanticRelationship> relationships = new ArrayList<>();
        for (SemanticElement element : observed.elements()) {
            switch (element.role()) {
                case DIALOG, ALERTDIALOG -> dialogs.add(dialog(element, observed, relationships));
                case ALERT, STATUS ->
                        alerts.add(
                                new AlertObservation(
                                        element.id(),
                                        element.role(),
                                        element.text().isBlank()
                                                ? element.accessibleName()
                                                : element.text(),
                                        element.visible()));
                case TABLIST -> tabLists.add(tabList(element, observed, relationships));
                case MENU, MENUBAR -> menus.add(menu(element, observed, relationships));
                default -> {
                    // Other roles are handled by their specialized observers.
                }
            }
        }
        return new DialogResult(dialogs, alerts, tabLists, menus, relationships);
    }

    private static DialogObservation dialog(
            SemanticElement dialog,
            ObservedElements observed,
            List<SemanticRelationship> relationships) {
        List<SemanticElementId> interactive =
                observed.elements().stream()
                        .filter(element -> !element.capabilities().isEmpty())
                        .filter(
                                element ->
                                        SemanticDescendants.isDescendant(
                                                element,
                                                dialog.id(),
                                                observed.elementsByBackendId()))
                        .map(SemanticElement::id)
                        .toList();
        interactive.forEach(
                child ->
                        relationships.add(
                                new SemanticRelationship(
                                        dialog.id(), child, SemanticRelationshipType.OWNS)));
        boolean modal =
                dialog.tagName().equals("dialog") && dialog.attributes().containsKey("open")
                        || dialog.attributes()
                                .getOrDefault("aria-modal", "false")
                                .equalsIgnoreCase("true");
        return new DialogObservation(
                dialog.id(), dialog.accessibleName(), modal, dialog.visible(), interactive);
    }

    private static TabListObservation tabList(
            SemanticElement tabList,
            ObservedElements observed,
            List<SemanticRelationship> relationships) {
        List<SemanticElement> tabs =
                observed.elements().stream()
                        .filter(element -> element.role() == ElementRole.TAB)
                        .filter(
                                element ->
                                        SemanticDescendants.isDescendant(
                                                element,
                                                tabList.id(),
                                                observed.elementsByBackendId()))
                        .toList();
        Optional<SemanticElementId> selected =
                tabs.stream()
                        .filter(element -> element.state().selected())
                        .map(SemanticElement::id)
                        .findFirst();
        List<SemanticRelationship> panels = new ArrayList<>();
        for (SemanticElement tab : tabs) {
            String controlled = tab.attributes().get("aria-controls");
            if (controlled == null) {
                continue;
            }
            for (String domId : controlled.trim().split("\\s+")) {
                SemanticElementId panel = observed.elementsByDomId().get(domId);
                if (panel != null) {
                    SemanticRelationship relationship =
                            new SemanticRelationship(
                                    tab.id(), panel, SemanticRelationshipType.CONTROLS);
                    panels.add(relationship);
                    relationships.add(relationship);
                }
            }
        }
        return new TabListObservation(
                tabList.id(),
                tabList.accessibleName(),
                tabs.stream().map(SemanticElement::id).toList(),
                selected,
                panels);
    }

    private static MenuObservation menu(
            SemanticElement menu,
            ObservedElements observed,
            List<SemanticRelationship> relationships) {
        List<SemanticElementId> items =
                observed.elements().stream()
                        .filter(element -> element.role() == ElementRole.MENUITEM)
                        .filter(
                                element ->
                                        SemanticDescendants.isDescendant(
                                                element, menu.id(), observed.elementsByBackendId()))
                        .map(SemanticElement::id)
                        .toList();
        items.forEach(
                item ->
                        relationships.add(
                                new SemanticRelationship(
                                        menu.id(), item, SemanticRelationshipType.OWNS)));
        return new MenuObservation(menu.id(), menu.role(), menu.accessibleName(), items);
    }

    public record DialogResult(
            List<DialogObservation> dialogs,
            List<AlertObservation> alerts,
            List<TabListObservation> tabLists,
            List<MenuObservation> menus,
            List<SemanticRelationship> relationships) {

        public DialogResult {
            dialogs = List.copyOf(dialogs);
            alerts = List.copyOf(alerts);
            tabLists = List.copyOf(tabLists);
            menus = List.copyOf(menus);
            relationships = List.copyOf(relationships);
        }
    }
}
