package io.webagent4j.observation;

import io.webagent4j.dom.ElementState;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.observation.spi.PageSnapshot;
import io.webagent4j.observation.spi.SnapshotElement;
import io.webagent4j.observation.spi.SnapshotElementState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ObservationSnapshotFixtures {

    private ObservationSnapshotFixtures() {}

    static PageSnapshot richSnapshot() {
        List<SnapshotElement> elements = new ArrayList<>();
        elements.add(element("main", null, 0, ElementRole.MAIN, "main", "Main"));
        elements.add(
                builder("heading-1", "main", 1, ElementRole.HEADING, "h1", "Dashboard")
                        .text("Dashboard")
                        .heading(1)
                        .build());
        elements.add(
                builder("heading-3", "main", 2, ElementRole.HEADING, "h3", "Recent")
                        .text("Recent")
                        .heading(3)
                        .build());
        elements.add(
                builder("nav", "main", 3, ElementRole.NAVIGATION, "nav", "Primary")
                        .attribute("aria-orientation", "horizontal")
                        .build());
        elements.add(
                builder("help", "nav", 4, ElementRole.LINK, "a", "Help")
                        .text("Help")
                        .attribute("href-resolved", "https://example.test/help")
                        .attribute("aria-current", "page")
                        .build());
        elements.add(
                builder("form", "main", 5, ElementRole.FORM, "form", "Sign in")
                        .attribute("action-resolved", "https://example.test/login")
                        .attribute("method", "post")
                        .build());
        elements.add(
                builder("email", "form", 6, ElementRole.TEXTBOX, "input", "Email")
                        .label("Email address")
                        .field(InputFieldType.EMAIL)
                        .form("form")
                        .required()
                        .editable()
                        .value("user@example.test")
                        .attribute("placeholder", "name@example.test")
                        .build());
        elements.add(
                builder("password", "form", 7, ElementRole.TEXTBOX, "input", "Password")
                        .label("Password")
                        .field(InputFieldType.PASSWORD)
                        .form("form")
                        .sensitive(true)
                        .valuePresent(true)
                        .attribute("autocomplete", "current-password")
                        .editable()
                        .build());
        elements.add(
                builder("country", "form", 8, ElementRole.SELECT, "select", "Country")
                        .label("Country")
                        .field(InputFieldType.SELECT)
                        .form("form")
                        .options(List.of("France", "Germany"), 4)
                        .build());
        elements.add(
                builder("submit", "form", 9, ElementRole.BUTTON, "button", "Continue")
                        .text("Continue")
                        .form("form")
                        .attribute("type", "submit")
                        .build());
        elements.add(
                builder("table", "main", 10, ElementRole.TABLE, "table", "Invoices")
                        .table(
                                List.of("Number", "Total"),
                                List.of(List.of("A-1", "$10"), List.of("A-2", "$20")),
                                5,
                                3)
                        .build());
        elements.add(
                builder("list", "main", 11, ElementRole.LIST, "ol", "Steps")
                        .list(List.of("First", "Second"), 4)
                        .build());
        elements.add(
                builder("image", "main", 12, ElementRole.IMAGE, "img", "Company logo")
                        .attribute("alt", "Company logo")
                        .attribute("src-resolved", "https://example.test/logo.png")
                        .dimensions(120, 80)
                        .build());
        elements.add(
                builder("dialog", "main", 13, ElementRole.DIALOG, "dialog", "Confirmation")
                        .attribute("open", "")
                        .build());
        elements.add(
                builder("close", "dialog", 14, ElementRole.BUTTON, "button", "Close")
                        .text("Close")
                        .build());
        elements.add(
                builder("alert", "main", 15, ElementRole.ALERT, "div", "Saved")
                        .text("Saved successfully")
                        .build());
        elements.add(builder("tabs", "main", 16, ElementRole.TABLIST, "div", "Views").build());
        elements.add(
                builder("summary-tab", "tabs", 17, ElementRole.TAB, "button", "Summary")
                        .selected()
                        .attribute("aria-controls", "summary-panel")
                        .build());
        elements.add(
                builder("panel", "tabs", 18, ElementRole.TABPANEL, "section", "Summary")
                        .attribute("id", "summary-panel")
                        .build());
        elements.add(builder("menu", "main", 19, ElementRole.MENU, "div", "Account menu").build());
        elements.add(
                builder("profile", "menu", 20, ElementRole.MENUITEM, "button", "Profile").build());
        elements.add(
                builder("paragraph", "main", 21, ElementRole.UNKNOWN, "p", "")
                        .text("Bounded visible page content")
                        .textTruncated()
                        .build());
        elements.add(
                builder("hidden", "main", 22, ElementRole.BUTTON, "button", "Hidden")
                        .hidden()
                        .build());
        elements.add(
                builder("decorative", "main", 23, ElementRole.IMAGE, "img", "")
                        .attribute("alt", "")
                        .build());
        elements.add(builder("unnamed", "main", 24, ElementRole.BUTTON, "button", "").build());
        return new PageSnapshot(
                "https://example.test/dashboard?token=must-not-log#fragment",
                "Dashboard",
                Optional.of("en"),
                Optional.of("UTF-8"),
                "complete",
                new ViewportSize(1280, 720),
                Optional.of("https://example.test/dashboard"),
                Optional.of("Dashboard description"),
                elements,
                27,
                30,
                Duration.ofMillis(8),
                true,
                List.of("password=must-never-escape"));
    }

    static SnapshotElement element(
            String id, String parent, int order, ElementRole role, String tag, String name) {
        return builder(id, parent, order, role, tag, name).build();
    }

    static Builder builder(
            String id, String parent, int order, ElementRole role, String tag, String name) {
        return new Builder(id, parent, order, role, tag, name);
    }

    static final class Builder {
        private final String id;
        private final String parent;
        private final int order;
        private final ElementRole role;
        private final String tag;
        private final String name;
        private String label = "";
        private String text = "";
        private boolean textTruncated;
        private final Map<String, String> attributes = new LinkedHashMap<>();
        private boolean visible = true;
        private boolean editable;
        private boolean required;
        private boolean selected;
        private String form;
        private InputFieldType fieldType;
        private boolean sensitive;
        private String retainedValue;
        private boolean valuePresent;
        private List<String> options = List.of();
        private int optionCount;
        private List<String> headers = List.of();
        private List<List<String>> rows = List.of();
        private int rowCount;
        private int columnCount;
        private List<String> items = List.of();
        private int itemCount;
        private Integer heading;
        private int width;
        private int height;

        Builder(String id, String parent, int order, ElementRole role, String tag, String name) {
            this.id = id;
            this.parent = parent;
            this.order = order;
            this.role = role;
            this.tag = tag;
            this.name = name;
        }

        Builder label(String value) {
            label = value;
            return this;
        }

        Builder text(String value) {
            text = value;
            return this;
        }

        Builder textTruncated() {
            textTruncated = true;
            return this;
        }

        Builder attribute(String key, String value) {
            attributes.put(key, value);
            return this;
        }

        Builder hidden() {
            visible = false;
            return this;
        }

        Builder editable() {
            editable = true;
            return this;
        }

        Builder required() {
            required = true;
            return this;
        }

        Builder selected() {
            selected = true;
            return this;
        }

        Builder form(String value) {
            form = value;
            return this;
        }

        Builder field(InputFieldType value) {
            fieldType = value;
            return this;
        }

        Builder sensitive(boolean value) {
            sensitive = value;
            return this;
        }

        Builder value(String value) {
            retainedValue = value;
            valuePresent = true;
            return this;
        }

        Builder valuePresent(boolean value) {
            valuePresent = value;
            return this;
        }

        Builder options(List<String> values, int count) {
            options = values;
            optionCount = count;
            return this;
        }

        Builder table(
                List<String> headerValues,
                List<List<String>> rowValues,
                int totalRows,
                int totalColumns) {
            headers = headerValues;
            rows = rowValues;
            rowCount = totalRows;
            columnCount = totalColumns;
            return this;
        }

        Builder list(List<String> values, int count) {
            items = values;
            itemCount = count;
            return this;
        }

        Builder heading(int value) {
            heading = value;
            return this;
        }

        Builder dimensions(int elementWidth, int elementHeight) {
            width = elementWidth;
            height = elementHeight;
            return this;
        }

        SnapshotElement build() {
            ElementState interaction =
                    new ElementState(
                            true, visible, true, editable, false, false, selected, false, visible,
                            visible, false, true);
            return new SnapshotElement(
                    id,
                    Optional.ofNullable(parent),
                    order,
                    role,
                    tag,
                    name,
                    label,
                    text,
                    textTruncated,
                    attributes,
                    new SnapshotElementState(interaction, required, Optional.empty()),
                    Optional.ofNullable(form),
                    Optional.ofNullable(fieldType),
                    sensitive,
                    Optional.ofNullable(retainedValue),
                    valuePresent,
                    options,
                    optionCount,
                    headers,
                    rows,
                    rowCount,
                    columnCount,
                    items,
                    itemCount,
                    Optional.ofNullable(heading),
                    width,
                    height,
                    true);
        }
    }
}
