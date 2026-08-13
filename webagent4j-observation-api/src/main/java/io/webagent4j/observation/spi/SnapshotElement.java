package io.webagent4j.observation.spi;

import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.observation.InputFieldType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Simple backend-neutral batch-capture DTO. It contains no browser handle or executable action.
 *
 * <p>Secret source values must never enter this DTO. A sensitive field carries only {@code
 * valuePresent}.
 */
@SuppressWarnings("checkstyle:ParameterNumber")
public record SnapshotElement(
        String backendId,
        Optional<String> parentBackendId,
        int documentOrder,
        ElementRole role,
        String tagName,
        String accessibleName,
        String label,
        String text,
        boolean textTruncated,
        Map<String, String> attributes,
        SnapshotElementState state,
        Optional<String> formOwnerBackendId,
        Optional<InputFieldType> fieldType,
        boolean sensitive,
        Optional<String> retainedValue,
        boolean valuePresent,
        List<String> selectOptions,
        int selectOptionCount,
        List<String> tableHeaders,
        List<List<String>> tableRows,
        int tableRowCount,
        int tableColumnCount,
        List<String> listItems,
        int listItemCount,
        Optional<Integer> headingLevel,
        int width,
        int height,
        boolean valid) {

    /** Validates and deeply copies captured data. */
    public SnapshotElement {
        backendId = requireText(backendId, "backendId");
        parentBackendId = normalized(parentBackendId, "parentBackendId");
        if (documentOrder < 0) {
            throw new IllegalArgumentException("documentOrder cannot be negative");
        }
        Objects.requireNonNull(role, "role");
        tagName = Objects.requireNonNull(tagName, "tagName");
        accessibleName = Objects.requireNonNull(accessibleName, "accessibleName");
        label = Objects.requireNonNull(label, "label");
        text = Objects.requireNonNull(text, "text");
        attributes =
                Collections.unmodifiableMap(
                        new LinkedHashMap<>(Objects.requireNonNull(attributes, "attributes")));
        Objects.requireNonNull(state, "state");
        formOwnerBackendId = normalized(formOwnerBackendId, "formOwnerBackendId");
        Objects.requireNonNull(fieldType, "fieldType");
        retainedValue = Objects.requireNonNull(retainedValue, "retainedValue");
        if (sensitive && retainedValue.isPresent()) {
            throw new IllegalArgumentException("sensitive values cannot enter a page snapshot");
        }
        selectOptions = List.copyOf(Objects.requireNonNull(selectOptions, "selectOptions"));
        tableHeaders = List.copyOf(Objects.requireNonNull(tableHeaders, "tableHeaders"));
        List<List<String>> rowCopy = new ArrayList<>();
        Objects.requireNonNull(tableRows, "tableRows")
                .forEach(row -> rowCopy.add(List.copyOf(row)));
        tableRows = List.copyOf(rowCopy);
        listItems = List.copyOf(Objects.requireNonNull(listItems, "listItems"));
        if (selectOptionCount < 0
                || tableRowCount < 0
                || tableColumnCount < 0
                || listItemCount < 0
                || width < 0
                || height < 0) {
            throw new IllegalArgumentException("snapshot counts and dimensions cannot be negative");
        }
        headingLevel = Objects.requireNonNull(headingLevel, "headingLevel");
    }

    private static Optional<String> normalized(Optional<String> value, String name) {
        return Objects.requireNonNull(value, name)
                .map(String::trim)
                .filter(item -> !item.isEmpty());
    }

    private static String requireText(String value, String name) {
        String result = Objects.requireNonNull(value, name).trim();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return result;
    }
}
