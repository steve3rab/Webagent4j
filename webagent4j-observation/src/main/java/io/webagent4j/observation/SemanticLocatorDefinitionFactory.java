package io.webagent4j.observation;

import io.webagent4j.locator.api.LocatorDefinition;
import io.webagent4j.locator.api.TextMatch;
import io.webagent4j.observation.spi.SnapshotElement;

/** Default factory that reuses immutable Phase 2 locator definitions. */
public final class SemanticLocatorDefinitionFactory implements ILocatorDefinitionFactory {

    @Override
    public LocatorDefinition create(SnapshotElement element) {
        LocatorDefinition definition = LocatorDefinition.element();
        if (element.role() != io.webagent4j.locator.api.ElementRole.UNKNOWN) {
            definition = definition.withRole(element.role());
        }
        if (!element.accessibleName().isBlank()) {
            definition = definition.named(element.accessibleName());
        } else if (!element.label().isBlank()) {
            definition = definition.labelled(element.label());
        } else if (!element.text().isBlank()) {
            definition = definition.withVisibleText(TextMatch.exactIgnoringCase(element.text()));
        }
        String testId = element.attributes().get("data-testid");
        String id = element.attributes().get("id");
        if (testId != null && !testId.isBlank()) {
            definition = definition.withTestId(testId);
        } else if (id != null && !id.isBlank()) {
            definition = definition.withId(id);
        }
        return definition;
    }
}
