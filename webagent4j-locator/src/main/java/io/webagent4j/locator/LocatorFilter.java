package io.webagent4j.locator;

import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.LocatorDiagnostics.RejectionReason;
import io.webagent4j.locator.api.LocatorDefinition;
import java.util.Map;
import java.util.Optional;

/** Applies mandatory role, state and exact attribute constraints before scoring. */
public final class LocatorFilter {

    /** Returns whether a live candidate satisfies every hard constraint. */
    public boolean accepts(LocatorDefinition definition, IElement element) {
        return rejectionReason(definition, element, "data-testid").isEmpty();
    }

    /** Returns the first deterministic rejection reason, or empty when all constraints pass. */
    public Optional<RejectionReason> rejectionReason(
            LocatorDefinition definition, IElement element, String testIdAttribute) {
        if (definition.role().isPresent() && element.role() != definition.role().orElseThrow()) {
            return Optional.of(RejectionReason.ROLE_MISMATCH);
        }
        ElementState state = element.state();
        if (!state.present()) {
            return Optional.of(RejectionReason.OUTSIDE_SCOPE);
        }
        if (definition.visible().isPresent()
                && state.visible() != definition.visible().orElseThrow()) {
            return Optional.of(RejectionReason.NOT_VISIBLE);
        }
        if (definition.enabled().isPresent()
                && state.enabled() != definition.enabled().orElseThrow()) {
            return Optional.of(RejectionReason.DISABLED);
        }
        if (definition.editable().isPresent()
                && state.editable() != definition.editable().orElseThrow()) {
            return Optional.of(RejectionReason.NOT_EDITABLE);
        }
        if (definition.readOnly().isPresent()
                && state.readOnly() != definition.readOnly().orElseThrow()) {
            return Optional.of(RejectionReason.NOT_READ_ONLY);
        }
        if (definition.checked().isPresent()
                && state.checked() != definition.checked().orElseThrow()) {
            return Optional.of(RejectionReason.NOT_CHECKED);
        }
        if (definition.selected().isPresent()
                && state.selected() != definition.selected().orElseThrow()) {
            return Optional.of(RejectionReason.NOT_SELECTED);
        }
        if (definition.focused().isPresent()
                && state.focused() != definition.focused().orElseThrow()) {
            return Optional.of(RejectionReason.NOT_FOCUSED);
        }
        if (definition.inViewport().isPresent()
                && state.inViewport() != definition.inViewport().orElseThrow()) {
            return Optional.of(RejectionReason.OUTSIDE_VIEWPORT);
        }
        if (definition.clickable().isPresent()
                && state.clickable() != definition.clickable().orElseThrow()) {
            return Optional.of(RejectionReason.NOT_CLICKABLE);
        }
        if (definition.covered().isPresent()
                && state.covered() != definition.covered().orElseThrow()) {
            return Optional.of(RejectionReason.NOT_COVERED);
        }
        Map<String, String> attributes = element.attributes();
        if (!definition.attributes().entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(attributes.get(entry.getKey())))) {
            return Optional.of(RejectionReason.ATTRIBUTE_MISMATCH);
        }
        if (definition.id().isPresent()
                && !definition.id().orElseThrow().equals(attributes.get("id"))) {
            return Optional.of(RejectionReason.ATTRIBUTE_MISMATCH);
        }
        if (definition.nameAttribute().isPresent()
                && !definition.nameAttribute().orElseThrow().equals(attributes.get("name"))) {
            return Optional.of(RejectionReason.ATTRIBUTE_MISMATCH);
        }
        if (definition.testId().isPresent()
                && !definition.testId().orElseThrow().equals(attributes.get(testIdAttribute))) {
            return Optional.of(RejectionReason.ATTRIBUTE_MISMATCH);
        }
        return Optional.empty();
    }
}
