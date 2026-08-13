package io.webagent4j.observation;

import io.webagent4j.locator.api.ElementReference;
import io.webagent4j.locator.api.ElementRole;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable lightweight description of one meaningful page element.
 *
 * <p>The local index is deterministic only for this observation. The semantic id supports
 * relationships within the snapshot. The portable reference carries Phase 2 locator intent and no
 * live browser handle.
 *
 * @param index one-based document-order index local to the observation
 * @param id observation-scoped semantic identity
 * @param stableKey deterministic diff-matching evidence
 * @param role reused Phase 2 semantic role
 * @param accessibleName accessible name
 * @param text bounded meaningful text
 * @param tagName lowercase source tag for diagnostics
 * @param state compact immutable state
 * @param reference portable locator reference
 * @param attributes whitelisted semantic attributes
 * @param capabilities reliably supported actions
 * @param parentId nearest semantic-tree parent
 * @param formId owning form when present
 * @param headingLevel heading level when applicable
 * @param fieldType compact input type when applicable
 * @param sensitive whether the control is governed by secret redaction
 * @param value safely retained or redacted value metadata
 */
public record SemanticElement(
        int index,
        SemanticElementId id,
        String stableKey,
        ElementRole role,
        String accessibleName,
        String text,
        String tagName,
        ObservedElementState state,
        ElementReference reference,
        Map<String, String> attributes,
        Set<ElementCapability> capabilities,
        Optional<SemanticElementId> parentId,
        Optional<SemanticElementId> formId,
        Optional<Integer> headingLevel,
        Optional<InputFieldType> fieldType,
        boolean sensitive,
        ObservedValue value) {

    /** Validates and defensively stores all snapshot data. */
    public SemanticElement {
        if (index <= 0) {
            throw new IllegalArgumentException("index must be positive");
        }
        Objects.requireNonNull(id, "id");
        stableKey = Objects.requireNonNull(stableKey, "stableKey");
        Objects.requireNonNull(role, "role");
        accessibleName = Objects.requireNonNull(accessibleName, "accessibleName");
        text = Objects.requireNonNull(text, "text");
        tagName = Objects.requireNonNull(tagName, "tagName");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(reference, "reference");
        attributes =
                Collections.unmodifiableMap(
                        new LinkedHashMap<>(Objects.requireNonNull(attributes, "attributes")));
        EnumSet<ElementCapability> capabilityCopy = EnumSet.noneOf(ElementCapability.class);
        capabilityCopy.addAll(Objects.requireNonNull(capabilities, "capabilities"));
        capabilities = Collections.unmodifiableSet(capabilityCopy);
        Objects.requireNonNull(parentId, "parentId");
        Objects.requireNonNull(formId, "formId");
        headingLevel = Objects.requireNonNull(headingLevel, "headingLevel");
        headingLevel.ifPresent(
                level -> {
                    if (level < 1 || level > 6) {
                        throw new IllegalArgumentException("heading level must be between 1 and 6");
                    }
                });
        Objects.requireNonNull(fieldType, "fieldType");
        Objects.requireNonNull(value, "value");
        if (sensitive && !value.redacted() && value.valuePresent()) {
            throw new IllegalArgumentException("sensitive values must be redacted");
        }
    }

    /** Compatibility alias for the accessible name. */
    public String name() {
        return accessibleName;
    }

    /** Returns whether this element is visible. */
    public boolean visible() {
        return state.visible();
    }

    /** Returns whether this element is enabled. */
    public boolean enabled() {
        return state.enabled();
    }

    /** Returns an immutable copy with reliably resolved action capabilities. */
    public SemanticElement withCapabilities(Set<ElementCapability> values) {
        return new SemanticElement(
                index,
                id,
                stableKey,
                role,
                accessibleName,
                text,
                tagName,
                state,
                reference,
                attributes,
                values,
                parentId,
                formId,
                headingLevel,
                fieldType,
                sensitive,
                value);
    }
}
