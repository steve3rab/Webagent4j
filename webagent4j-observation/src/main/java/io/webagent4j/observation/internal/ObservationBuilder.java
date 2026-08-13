package io.webagent4j.observation.internal;

import io.webagent4j.observation.AlertObservation;
import io.webagent4j.observation.DialogObservation;
import io.webagent4j.observation.FormObservation;
import io.webagent4j.observation.ImageObservation;
import io.webagent4j.observation.LandmarkObservation;
import io.webagent4j.observation.ListObservation;
import io.webagent4j.observation.MenuObservation;
import io.webagent4j.observation.NavigationObservation;
import io.webagent4j.observation.Observation;
import io.webagent4j.observation.ObservationFingerprint;
import io.webagent4j.observation.ObservationId;
import io.webagent4j.observation.ObservationStatistics;
import io.webagent4j.observation.ObservationWarning;
import io.webagent4j.observation.PageContent;
import io.webagent4j.observation.PageMetadata;
import io.webagent4j.observation.SemanticElement;
import io.webagent4j.observation.SemanticRelationship;
import io.webagent4j.observation.SemanticTree;
import io.webagent4j.observation.TabListObservation;
import io.webagent4j.observation.TableObservation;
import java.util.List;

/** Internal focused assembler for the complete immutable observation. */
public final class ObservationBuilder {

    private final ObservationId id;
    private final PageMetadata metadata;
    private List<SemanticElement> elements = List.of();
    private List<LandmarkObservation> landmarks = List.of();
    private List<FormObservation> forms = List.of();
    private List<NavigationObservation> navigations = List.of();
    private List<TableObservation> tables = List.of();
    private List<ListObservation> lists = List.of();
    private List<ImageObservation> images = List.of();
    private List<DialogObservation> dialogs = List.of();
    private List<AlertObservation> alerts = List.of();
    private List<TabListObservation> tabLists = List.of();
    private List<MenuObservation> menus = List.of();
    private List<SemanticRelationship> relationships = List.of();
    private SemanticTree tree = new SemanticTree(List.of(), false);
    private PageContent content = new PageContent(List.of(), List.of(), List.of(), List.of());
    private ObservationStatistics statistics;
    private List<ObservationWarning> warnings = List.of();

    public ObservationBuilder(ObservationId id, PageMetadata metadata) {
        this.id = id;
        this.metadata = metadata;
    }

    public ObservationBuilder semantic(
            List<SemanticElement> values,
            List<LandmarkObservation> landmarkValues,
            List<SemanticRelationship> relationshipValues,
            SemanticTree treeValue) {
        elements = List.copyOf(values);
        landmarks = List.copyOf(landmarkValues);
        relationships = List.copyOf(relationshipValues);
        tree = treeValue;
        return this;
    }

    public ObservationBuilder formsAndNavigation(
            List<FormObservation> formValues, List<NavigationObservation> navigationValues) {
        forms = List.copyOf(formValues);
        navigations = List.copyOf(navigationValues);
        return this;
    }

    public ObservationBuilder structuredContent(
            List<TableObservation> tableValues,
            List<ListObservation> listValues,
            List<ImageObservation> imageValues,
            PageContent pageContent) {
        tables = List.copyOf(tableValues);
        lists = List.copyOf(listValues);
        images = List.copyOf(imageValues);
        content = pageContent;
        return this;
    }

    public ObservationBuilder transientUi(DialogObserver.DialogResult result) {
        dialogs = result.dialogs();
        alerts = result.alerts();
        tabLists = result.tabLists();
        menus = result.menus();
        return this;
    }

    public ObservationBuilder diagnostics(
            ObservationStatistics values, List<ObservationWarning> warningValues) {
        statistics = values;
        warnings = List.copyOf(warningValues);
        return this;
    }

    public Observation build() {
        if (statistics == null) {
            throw new IllegalStateException("statistics must be supplied");
        }
        ObservationFingerprint fingerprint =
                ObservationFingerprint.compute(metadata, elements, relationships);
        return new Observation(
                id,
                metadata,
                elements,
                landmarks,
                forms,
                navigations,
                tables,
                lists,
                images,
                dialogs,
                alerts,
                tabLists,
                menus,
                relationships,
                tree,
                content,
                statistics,
                warnings,
                fingerprint);
    }
}
