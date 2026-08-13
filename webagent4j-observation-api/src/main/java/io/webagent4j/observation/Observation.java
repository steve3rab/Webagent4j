package io.webagent4j.observation;

import io.webagent4j.locator.api.ElementRole;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, thread-safe semantic snapshot of a page at one point in time.
 *
 * <p>Getters never read the live browser. Capture a fresh observation after a page mutation. All
 * collections are immutable and ordered by document order where applicable. Volatile identity,
 * timestamp, and duration are excluded from {@link #fingerprint()}.
 *
 * @param id unique capture identity
 * @param metadata page-level snapshot metadata
 * @param elements ordered deduplicated semantic elements
 * @param landmarks accessibility landmarks
 * @param forms forms with field ownership
 * @param navigations navigation regions
 * @param tables bounded tables
 * @param lists bounded semantic lists
 * @param images meaningful images
 * @param dialogs dialogs and alert dialogs
 * @param alerts alerts and statuses
 * @param tabLists tab structures
 * @param menus explicit ARIA menus
 * @param relationships important semantic relationships
 * @param tree bounded semantic tree
 * @param content compact visible content
 * @param statistics capture counts and truncation
 * @param warnings factual non-fatal issues
 * @param fingerprint deterministic semantic fingerprint
 */
public record Observation(
        ObservationId id,
        PageMetadata metadata,
        List<SemanticElement> elements,
        List<LandmarkObservation> landmarks,
        List<FormObservation> forms,
        List<NavigationObservation> navigations,
        List<TableObservation> tables,
        List<ListObservation> lists,
        List<ImageObservation> images,
        List<DialogObservation> dialogs,
        List<AlertObservation> alerts,
        List<TabListObservation> tabLists,
        List<MenuObservation> menus,
        List<SemanticRelationship> relationships,
        SemanticTree tree,
        PageContent content,
        ObservationStatistics statistics,
        List<ObservationWarning> warnings,
        ObservationFingerprint fingerprint) {

    /** Validates and defensively stores the complete snapshot. */
    public Observation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(metadata, "metadata");
        elements = List.copyOf(Objects.requireNonNull(elements, "elements"));
        landmarks = List.copyOf(Objects.requireNonNull(landmarks, "landmarks"));
        forms = List.copyOf(Objects.requireNonNull(forms, "forms"));
        navigations = List.copyOf(Objects.requireNonNull(navigations, "navigations"));
        tables = List.copyOf(Objects.requireNonNull(tables, "tables"));
        lists = List.copyOf(Objects.requireNonNull(lists, "lists"));
        images = List.copyOf(Objects.requireNonNull(images, "images"));
        dialogs = List.copyOf(Objects.requireNonNull(dialogs, "dialogs"));
        alerts = List.copyOf(Objects.requireNonNull(alerts, "alerts"));
        tabLists = List.copyOf(Objects.requireNonNull(tabLists, "tabLists"));
        menus = List.copyOf(Objects.requireNonNull(menus, "menus"));
        relationships = List.copyOf(Objects.requireNonNull(relationships, "relationships"));
        Objects.requireNonNull(tree, "tree");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(statistics, "statistics");
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        Objects.requireNonNull(fingerprint, "fingerprint");
        validateIndices(elements);
    }

    /** Returns the captured URL. */
    public String url() {
        return metadata.url();
    }

    /** Returns the captured title. */
    public String title() {
        return metadata.title();
    }

    /** Returns all observed headings in document order. */
    public List<HeadingObservation> headings() {
        return content.headings();
    }

    /** Returns all observed links in document order. */
    public List<SemanticElement> links() {
        return byRole(ElementRole.LINK);
    }

    /** Returns all observed buttons in document order. */
    public List<SemanticElement> buttons() {
        return byRole(ElementRole.BUTTON);
    }

    /** Returns all elements carrying the requested Phase 2 role. */
    public List<SemanticElement> byRole(ElementRole role) {
        Objects.requireNonNull(role, "role");
        return elements.stream().filter(element -> element.role() == role).toList();
    }

    /** Returns all elements with at least one reliable action capability. */
    public List<SemanticElement> interactiveElements() {
        return elements.stream().filter(element -> !element.capabilities().isEmpty()).toList();
    }

    /** Returns the element at a one-based observation-local index. */
    public SemanticElement element(int index) {
        if (index <= 0 || index > elements.size()) {
            throw new IndexOutOfBoundsException("observation element index: " + index);
        }
        SemanticElement result = elements.get(index - 1);
        if (result.index() != index) {
            return elements.stream()
                    .filter(element -> element.index() == index)
                    .findFirst()
                    .orElseThrow(
                            () ->
                                    new IndexOutOfBoundsException(
                                            "observation element index: " + index));
        }
        return result;
    }

    /** Renders the deterministic compact semantic representation. */
    public String toCompactText() {
        return new CompactTextObservationRenderer().render(this);
    }

    /** Renders deterministic valid JSON without serializing backend objects or secret values. */
    public String toJson() {
        return new JsonObservationRenderer().render(this);
    }

    /** Computes a deterministic MVP semantic diff from this snapshot to the supplied snapshot. */
    public ObservationDiff diff(Observation after) {
        return new ObservationDiffer().diff(this, after);
    }

    private static void validateIndices(List<SemanticElement> elements) {
        Map<Integer, SemanticElement> indices = new LinkedHashMap<>();
        for (SemanticElement element : elements) {
            if (indices.put(element.index(), element) != null) {
                throw new IllegalArgumentException("element indices must be unique");
            }
        }
    }
}
