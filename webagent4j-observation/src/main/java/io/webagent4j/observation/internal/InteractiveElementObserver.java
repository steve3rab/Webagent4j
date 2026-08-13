package io.webagent4j.observation.internal;

import io.webagent4j.dom.ElementState;
import io.webagent4j.locator.api.ElementReference;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.observation.IElementCapabilityResolver;
import io.webagent4j.observation.ILocatorDefinitionFactory;
import io.webagent4j.observation.IObservationFilter;
import io.webagent4j.observation.IObservationRedactionPolicy;
import io.webagent4j.observation.ObservationOptions;
import io.webagent4j.observation.ObservationTruncation;
import io.webagent4j.observation.ObservationTruncationType;
import io.webagent4j.observation.ObservationWarning;
import io.webagent4j.observation.ObservationWarningType;
import io.webagent4j.observation.ObservedElementState;
import io.webagent4j.observation.ObservedValue;
import io.webagent4j.observation.SemanticElement;
import io.webagent4j.observation.SemanticElementId;
import io.webagent4j.observation.spi.PageSnapshot;
import io.webagent4j.observation.spi.SnapshotElement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Converts a bounded batch snapshot into deduplicated public semantic elements. */
public final class InteractiveElementObserver {

    private final IObservationFilter filter;
    private final IObservationRedactionPolicy redactionPolicy;
    private final ILocatorDefinitionFactory locatorFactory;
    private final IElementCapabilityResolver capabilityResolver;

    public InteractiveElementObserver(
            IObservationFilter filter,
            IObservationRedactionPolicy redactionPolicy,
            ILocatorDefinitionFactory locatorFactory,
            IElementCapabilityResolver capabilityResolver) {
        this.filter = filter;
        this.redactionPolicy = redactionPolicy;
        this.locatorFactory = locatorFactory;
        this.capabilityResolver = capabilityResolver;
    }

    public ObservedElements observe(PageSnapshot snapshot, ObservationOptions options) {
        List<SnapshotElement> ordered =
                snapshot.elements().stream()
                        .sorted(Comparator.comparingInt(SnapshotElement::documentOrder))
                        .toList();
        LinkedHashMap<String, SnapshotElement> unique = new LinkedHashMap<>();
        ordered.forEach(element -> unique.putIfAbsent(element.backendId(), element));
        List<SnapshotElement> eligible =
                unique.values().stream()
                        .filter(element -> filter.include(element, options))
                        .toList();
        List<SnapshotElement> included =
                eligible.stream().limit(options.budget().maxElements()).toList();
        Set<String> retainedIds = new LinkedHashSet<>();
        included.forEach(element -> retainedIds.add(element.backendId()));

        List<SemanticElement> elements = new ArrayList<>();
        Map<String, SnapshotElement> snapshotsById = new LinkedHashMap<>();
        Map<String, SemanticElement> elementsById = new LinkedHashMap<>();
        Map<String, SemanticElementId> elementsByDomId = new LinkedHashMap<>();
        List<ObservationTruncation> truncations = new ArrayList<>();
        List<ObservationWarning> warnings = new ArrayList<>();
        int index = 1;
        for (SnapshotElement item : included) {
            SemanticElement element = create(item, index++, retainedIds, options);
            element = element.withCapabilities(capabilityResolver.resolve(element));
            elements.add(element);
            snapshotsById.put(item.backendId(), item);
            elementsById.put(item.backendId(), element);
            String domId = item.attributes().get("id");
            if (domId != null && !domId.isBlank()) {
                elementsByDomId.putIfAbsent(domId, element.id());
            }
            if (item.textTruncated()) {
                truncations.add(
                        new ObservationTruncation(
                                ObservationTruncationType.TEXT,
                                options.budget().maxTextLength() + 1,
                                options.budget().maxTextLength(),
                                Optional.of(element.id())));
            }
            addAccessibilityWarnings(item, element.id(), warnings);
        }
        int retainedCount = included.size();
        if (snapshot.originalSemanticElementCount() > snapshot.elements().size()
                || eligible.size() > retainedCount) {
            truncations.add(
                    new ObservationTruncation(
                            ObservationTruncationType.ELEMENTS,
                            Math.max(snapshot.originalSemanticElementCount(), unique.size()),
                            retainedCount,
                            Optional.empty()));
        }
        return new ObservedElements(
                elements, snapshotsById, elementsById, elementsByDomId, truncations, warnings);
    }

    private SemanticElement create(
            SnapshotElement item, int index, Set<String> retainedIds, ObservationOptions options) {
        SemanticElementId id = new SemanticElementId(item.backendId());
        Optional<SemanticElementId> parent =
                item.parentBackendId().filter(retainedIds::contains).map(SemanticElementId::new);
        Optional<SemanticElementId> form =
                item.formOwnerBackendId().filter(retainedIds::contains).map(SemanticElementId::new);
        ObservedValue value = redactionPolicy.redact(item, options);
        ObservedElementState state =
                new ObservedElementState(
                        copyState(item.state().interaction()),
                        item.state().required(),
                        item.state().expanded());
        return new SemanticElement(
                index,
                id,
                stableKey(item),
                item.role(),
                ObservationText.bounded(item.accessibleName(), options.budget().maxTextLength()),
                ObservationText.bounded(item.text(), options.budget().maxTextLength()),
                item.tagName(),
                state,
                new ElementReference(locatorFactory.create(item)),
                item.attributes(),
                Set.of(),
                parent,
                form,
                item.headingLevel().filter(level -> level >= 1 && level <= 6),
                item.fieldType(),
                value.redacted(),
                value);
    }

    private static ElementState copyState(ElementState value) {
        return new ElementState(
                value.present(),
                value.visible(),
                value.enabled(),
                value.editable(),
                value.readOnly(),
                value.checked(),
                value.selected(),
                value.focused(),
                value.inViewport(),
                value.clickable(),
                value.covered(),
                value.interactabilityKnown());
    }

    private static String stableKey(SnapshotElement element) {
        String stableAttribute =
                element.attributes()
                        .getOrDefault("data-testid", element.attributes().getOrDefault("id", ""));
        if (!stableAttribute.isBlank()) {
            return String.join(
                    "|", element.role().name(), "attribute", ObservationText.key(stableAttribute));
        }
        return String.join(
                "|",
                element.role().name(),
                "semantic",
                ObservationText.key(element.accessibleName()),
                ObservationText.key(element.label()),
                element.fieldType().map(Enum::name).orElse(""));
    }

    private static void addAccessibilityWarnings(
            SnapshotElement source, SemanticElementId id, List<ObservationWarning> warnings) {
        if (source.role() == ElementRole.BUTTON && source.accessibleName().isBlank()) {
            warnings.add(
                    warning(
                            ObservationWarningType.BUTTON_WITHOUT_NAME,
                            "Button has no accessible name",
                            id));
        }
        if (source.role() == ElementRole.IMAGE
                && source.attributes().getOrDefault("alt", "").isBlank()) {
            warnings.add(
                    warning(
                            ObservationWarningType.IMAGE_WITHOUT_ALT,
                            "Image has no alternative text",
                            id));
        }
        if (source.fieldType().isPresent()
                && source.label().isBlank()
                && source.accessibleName().isBlank()) {
            warnings.add(
                    warning(
                            ObservationWarningType.FORM_CONTROL_WITHOUT_LABEL,
                            "Form control has no label or accessible name",
                            id));
        }
        if (source.headingLevel().filter(level -> level < 1 || level > 6).isPresent()) {
            warnings.add(
                    warning(
                            ObservationWarningType.INVALID_ARIA_VALUE,
                            "Heading has an invalid aria-level",
                            id));
        }
    }

    private static ObservationWarning warning(
            ObservationWarningType type, String message, SemanticElementId id) {
        return new ObservationWarning(type, message, Optional.of(id));
    }
}
