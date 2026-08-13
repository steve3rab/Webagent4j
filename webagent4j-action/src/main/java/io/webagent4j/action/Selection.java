package io.webagent4j.action;

import java.util.Objects;

/** Immutable select-option request using exactly one portable selection mode. */
public record Selection(SelectionType type, String value, int index) {

    /** Validates mode-specific selection data. */
    public Selection {
        Objects.requireNonNull(type, "type");
        value = Objects.requireNonNull(value, "value");
        if (type == SelectionType.INDEX && index < 0) {
            throw new IllegalArgumentException("index cannot be negative");
        }
        if (type != SelectionType.INDEX && value.isBlank()) {
            throw new IllegalArgumentException("value cannot be blank");
        }
    }

    /** Selects by submitted option value. */
    public static Selection byValue(String value) {
        return new Selection(SelectionType.VALUE, value, -1);
    }

    /** Selects by visible option label. */
    public static Selection byLabel(String label) {
        return new Selection(SelectionType.LABEL, label, -1);
    }

    /** Selects by zero-based option index. */
    public static Selection byIndex(int index) {
        return new Selection(SelectionType.INDEX, "", index);
    }
}
