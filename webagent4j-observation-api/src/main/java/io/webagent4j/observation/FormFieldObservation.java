package io.webagent4j.observation;

import io.webagent4j.locator.api.ElementRole;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable form field with safe value metadata and its owning form relationship. */
public record FormFieldObservation(
        SemanticElementId elementId,
        SemanticElementId formId,
        ElementRole role,
        InputFieldType type,
        String name,
        String label,
        Optional<String> placeholder,
        boolean required,
        boolean readOnly,
        boolean enabled,
        boolean valid,
        boolean sensitive,
        ObservedValue value,
        List<String> options,
        boolean optionsTruncated) {

    /** Validates and defensively stores form-field data. */
    public FormFieldObservation {
        Objects.requireNonNull(elementId, "elementId");
        Objects.requireNonNull(formId, "formId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(type, "type");
        name = Objects.requireNonNull(name, "name");
        label = Objects.requireNonNull(label, "label");
        placeholder =
                Objects.requireNonNull(placeholder, "placeholder")
                        .map(String::trim)
                        .filter(item -> !item.isEmpty());
        Objects.requireNonNull(value, "value");
        options = List.copyOf(Objects.requireNonNull(options, "options"));
        if (sensitive && !value.redacted() && value.valuePresent()) {
            throw new IllegalArgumentException("sensitive field values must be redacted");
        }
    }
}
