package io.webagent4j.observation;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.function.Function;

/**
 * Deterministic dependency-free JSON renderer for the public semantic model.
 *
 * <p>Portable locator definitions, backend identities, and source secret values are intentionally
 * omitted. Only values already accepted by the central redaction policy can be rendered.
 */
public final class JsonObservationRenderer implements IObservationRenderer<String> {

    @Override
    public String render(Observation observation) {
        Objects.requireNonNull(observation, "observation");
        return object(
                field("id", quoted(observation.id().value())),
                field("metadata", metadata(observation.metadata())),
                field("elements", array(observation.elements(), this::element)),
                field("landmarks", array(observation.landmarks(), this::landmark)),
                field("forms", array(observation.forms(), this::form)),
                field("navigations", array(observation.navigations(), this::navigation)),
                field("tables", array(observation.tables(), this::table)),
                field("lists", array(observation.lists(), this::list)),
                field("images", array(observation.images(), this::image)),
                field("dialogs", array(observation.dialogs(), this::dialog)),
                field("alerts", array(observation.alerts(), this::alert)),
                field("tabLists", array(observation.tabLists(), this::tabList)),
                field("menus", array(observation.menus(), this::menu)),
                field("relationships", array(observation.relationships(), this::relationship)),
                field("tree", tree(observation.tree())),
                field("content", content(observation.content())),
                field("statistics", statistics(observation.statistics())),
                field("warnings", array(observation.warnings(), this::warning)),
                field("fingerprint", quoted(observation.fingerprint().value())));
    }

    private String metadata(PageMetadata metadata) {
        return object(
                field("url", quoted(metadata.url())),
                field("title", quoted(metadata.title())),
                field("language", optional(metadata.language().orElse(null))),
                field("charset", optional(metadata.charset().orElse(null))),
                field("readyState", quoted(metadata.readyState())),
                field("capturedAt", quoted(metadata.capturedAt().toString())),
                field(
                        "viewport",
                        object(
                                field("width", number(metadata.viewport().width())),
                                field("height", number(metadata.viewport().height())))),
                field("canonicalUrl", optional(metadata.canonicalUrl().orElse(null))),
                field("description", optional(metadata.description().orElse(null))));
    }

    private String element(SemanticElement element) {
        return object(
                field("index", number(element.index())),
                field("id", quoted(element.id().value())),
                field("role", quoted(element.role().name())),
                field("accessibleName", quoted(element.accessibleName())),
                field("text", quoted(element.text())),
                field("visible", bool(element.visible())),
                field("enabled", bool(element.enabled())),
                field("editable", bool(element.state().editable())),
                field("readOnly", bool(element.state().readOnly())),
                field("checked", bool(element.state().checked())),
                field("selected", bool(element.state().selected())),
                field("focused", bool(element.state().focused())),
                field("required", bool(element.state().required())),
                field(
                        "expanded",
                        element.state()
                                .expanded()
                                .map(JsonObservationRenderer::bool)
                                .orElse("null")),
                field(
                        "capabilities",
                        array(
                                element.capabilities().stream()
                                        .sorted(Comparator.comparing(Enum::name))
                                        .toList(),
                                value -> quoted(value.name()))),
                field("attributes", attributes(element.attributes())),
                field("parentId", optionalId(element.parentId().orElse(null))),
                field("formId", optionalId(element.formId().orElse(null))),
                field(
                        "headingLevel",
                        element.headingLevel().map(JsonObservationRenderer::number).orElse("null")),
                field(
                        "fieldType",
                        element.fieldType().map(type -> quoted(type.name())).orElse("null")),
                field("sensitive", bool(element.sensitive())),
                field("value", observedValue(element.value())));
    }

    private String observedValue(ObservedValue value) {
        return object(
                field("disposition", quoted(value.disposition().name())),
                field("valuePresent", bool(value.valuePresent())),
                field("value", value.value().map(JsonObservationRenderer::quoted).orElse("null")));
    }

    private String landmark(LandmarkObservation landmark) {
        return object(
                field("elementId", quoted(landmark.elementId().value())),
                field("role", quoted(landmark.role().name())),
                field("name", quoted(landmark.name())),
                field("children", array(landmark.children(), this::id)));
    }

    private String form(FormObservation form) {
        return object(
                field("elementId", quoted(form.elementId().value())),
                field("name", quoted(form.name())),
                field("action", optional(form.action().orElse(null))),
                field("method", quoted(form.method())),
                field("fields", array(form.fields(), this::fieldObservation)),
                field("actions", array(form.actions(), this::element)),
                field("valid", bool(form.valid())));
    }

    private String fieldObservation(FormFieldObservation field) {
        return object(
                field("elementId", quoted(field.elementId().value())),
                field("formId", quoted(field.formId().value())),
                field("role", quoted(field.role().name())),
                field("type", quoted(field.type().name())),
                field("name", quoted(field.name())),
                field("label", quoted(field.label())),
                field("placeholder", optional(field.placeholder().orElse(null))),
                field("required", bool(field.required())),
                field("readOnly", bool(field.readOnly())),
                field("enabled", bool(field.enabled())),
                field("valid", bool(field.valid())),
                field("sensitive", bool(field.sensitive())),
                field("value", observedValue(field.value())),
                field("options", array(field.options(), JsonObservationRenderer::quoted)),
                field("optionsTruncated", bool(field.optionsTruncated())));
    }

    private String navigation(NavigationObservation navigation) {
        return object(
                field("elementId", quoted(navigation.elementId().value())),
                field("name", quoted(navigation.name())),
                field("links", array(navigation.links(), this::element)),
                field("currentItem", optionalId(navigation.currentItem().orElse(null))),
                field("orientation", quoted(navigation.orientation().name())));
    }

    private String table(TableObservation table) {
        return object(
                field("elementId", quoted(table.elementId().value())),
                field("name", quoted(table.name())),
                field("headers", array(table.headers(), JsonObservationRenderer::quoted)),
                field("rowCount", number(table.rowCount())),
                field("columnCount", number(table.columnCount())),
                field(
                        "rows",
                        array(table.rows(), row -> array(row, JsonObservationRenderer::quoted))),
                field("rowsTruncated", bool(table.rowsTruncated())),
                field("columnsTruncated", bool(table.columnsTruncated())));
    }

    private String list(ListObservation list) {
        return object(
                field("elementId", quoted(list.elementId().value())),
                field("ordered", bool(list.ordered())),
                field("itemCount", number(list.itemCount())),
                field("items", array(list.items(), JsonObservationRenderer::quoted)),
                field("truncated", bool(list.truncated())));
    }

    private String image(ImageObservation image) {
        return object(
                field("elementId", quoted(image.elementId().value())),
                field("accessibleName", quoted(image.accessibleName())),
                field("alt", optional(image.alt().orElse(null))),
                field("source", optional(image.source().orElse(null))),
                field("width", number(image.width())),
                field("height", number(image.height())));
    }

    private String dialog(DialogObservation dialog) {
        return object(
                field("elementId", quoted(dialog.elementId().value())),
                field("name", quoted(dialog.name())),
                field("modal", bool(dialog.modal())),
                field("visible", bool(dialog.visible())),
                field("interactiveElements", array(dialog.interactiveElements(), this::id)));
    }

    private String alert(AlertObservation alert) {
        return object(
                field("elementId", quoted(alert.elementId().value())),
                field("role", quoted(alert.role().name())),
                field("text", quoted(alert.text())),
                field("visible", bool(alert.visible())));
    }

    private String tabList(TabListObservation tabList) {
        return object(
                field("elementId", quoted(tabList.elementId().value())),
                field("name", quoted(tabList.name())),
                field("tabs", array(tabList.tabs(), this::id)),
                field("selectedTab", optionalId(tabList.selectedTab().orElse(null))),
                field(
                        "panelRelationships",
                        array(tabList.panelRelationships(), this::relationship)));
    }

    private String menu(MenuObservation menu) {
        return object(
                field("elementId", quoted(menu.elementId().value())),
                field("role", quoted(menu.role().name())),
                field("name", quoted(menu.name())),
                field("items", array(menu.items(), this::id)));
    }

    private String relationship(SemanticRelationship relationship) {
        return object(
                field("source", quoted(relationship.source().value())),
                field("target", quoted(relationship.target().value())),
                field("type", quoted(relationship.type().name())));
    }

    private String tree(SemanticTree tree) {
        return object(
                field("roots", array(tree.roots(), this::treeNode)),
                field("depthTruncated", bool(tree.depthTruncated())));
    }

    private String treeNode(SemanticTreeNode node) {
        return object(
                field("elementId", quoted(node.elementId().value())),
                field("index", number(node.index())),
                field("role", quoted(node.role().name())),
                field("name", quoted(node.name())),
                field("children", array(node.children(), this::treeNode)),
                field("depthTruncated", bool(node.depthTruncated())));
    }

    private String content(PageContent content) {
        return object(
                field("headings", array(content.headings(), this::heading)),
                field("textBlocks", array(content.textBlocks(), JsonObservationRenderer::quoted)),
                field("lists", array(content.lists(), this::list)),
                field("tables", array(content.tables(), this::table)));
    }

    private String heading(HeadingObservation heading) {
        return object(
                field("elementId", quoted(heading.elementId().value())),
                field("index", number(heading.index())),
                field("text", quoted(heading.text())),
                field("level", number(heading.level())),
                field("parentHeadingId", optionalId(heading.parentHeadingId().orElse(null))));
    }

    private String statistics(ObservationStatistics statistics) {
        return object(
                field("elementsVisited", number(statistics.elementsVisited())),
                field("elementsIncluded", number(statistics.elementsIncluded())),
                field("elementsFiltered", number(statistics.elementsFiltered())),
                field("interactiveElements", number(statistics.interactiveElements())),
                field("forms", number(statistics.forms())),
                field("links", number(statistics.links())),
                field("buttons", number(statistics.buttons())),
                field("tables", number(statistics.tables())),
                field("durationMillis", number(statistics.duration().toMillis())),
                field("truncated", bool(statistics.truncated())),
                field("truncations", array(statistics.truncations(), this::truncation)));
    }

    private String truncation(ObservationTruncation truncation) {
        return object(
                field("type", quoted(truncation.type().name())),
                field("originalCount", number(truncation.originalCount())),
                field("retainedCount", number(truncation.retainedCount())),
                field("elementId", optionalId(truncation.elementId().orElse(null))));
    }

    private String warning(ObservationWarning warning) {
        return object(
                field("type", quoted(warning.type().name())),
                field("message", quoted(warning.message())),
                field("elementId", optionalId(warning.elementId().orElse(null))));
    }

    private String attributes(Map<String, String> attributes) {
        StringJoiner fields = new StringJoiner(",", "{", "}");
        attributes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> fields.add(field(entry.getKey(), quoted(entry.getValue()))));
        return fields.toString();
    }

    private String id(SemanticElementId id) {
        return quoted(id.value());
    }

    private static <T> String array(List<T> values, Function<T, String> renderer) {
        StringJoiner items = new StringJoiner(",", "[", "]");
        values.forEach(value -> items.add(renderer.apply(value)));
        return items.toString();
    }

    private static String object(String... fields) {
        return "{" + String.join(",", fields) + "}";
    }

    private static String field(String name, String encodedValue) {
        return quoted(name) + ':' + encodedValue;
    }

    private static String optional(String value) {
        return value == null ? "null" : quoted(value);
    }

    private static String optionalId(SemanticElementId value) {
        return value == null ? "null" : quoted(value.value());
    }

    private static String bool(boolean value) {
        return Boolean.toString(value);
    }

    private static String number(Number value) {
        return value.toString();
    }

    private static String quoted(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) {
                        result.append(String.format("\\u%04x", (int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.append('"').toString();
    }
}
