package io.webagent4j.observation.internal;

import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.observation.FormFieldObservation;
import io.webagent4j.observation.FormObservation;
import io.webagent4j.observation.InputFieldType;
import io.webagent4j.observation.ObservationTruncation;
import io.webagent4j.observation.ObservationTruncationType;
import io.webagent4j.observation.SemanticElement;
import io.webagent4j.observation.SemanticRelationship;
import io.webagent4j.observation.SemanticRelationshipType;
import io.webagent4j.observation.spi.SnapshotElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Builds forms, field ownership, safe value metadata, options, and submit relationships. */
public final class FormObserver {

    public FormResult observe(ObservedElements observed) {
        List<FormObservation> forms = new ArrayList<>();
        List<SemanticRelationship> relationships = new ArrayList<>();
        List<ObservationTruncation> truncations = new ArrayList<>();
        for (SemanticElement form : observed.elements()) {
            if (form.role() != ElementRole.FORM) {
                continue;
            }
            List<FormFieldObservation> fields = new ArrayList<>();
            List<SemanticElement> actions = new ArrayList<>();
            for (SemanticElement element : observed.elements()) {
                if (element.formId().filter(form.id()::equals).isEmpty()) {
                    continue;
                }
                SnapshotElement snapshot =
                        observed.snapshotsByBackendId().get(element.id().value());
                if (element.fieldType().isPresent()) {
                    List<String> options = snapshot == null ? List.of() : snapshot.selectOptions();
                    int originalOptions =
                            snapshot == null ? options.size() : snapshot.selectOptionCount();
                    boolean optionsTruncated = originalOptions > options.size();
                    fields.add(
                            new FormFieldObservation(
                                    element.id(),
                                    form.id(),
                                    element.role(),
                                    element.fieldType().orElse(InputFieldType.OTHER),
                                    element.accessibleName(),
                                    snapshot == null ? "" : snapshot.label(),
                                    Optional.ofNullable(element.attributes().get("placeholder")),
                                    element.state().required(),
                                    element.state().readOnly(),
                                    element.enabled(),
                                    snapshot == null || snapshot.valid(),
                                    element.sensitive(),
                                    element.value(),
                                    options,
                                    optionsTruncated));
                    relationships.add(
                            new SemanticRelationship(
                                    element.id(), form.id(), SemanticRelationshipType.BELONGS_TO));
                    if (optionsTruncated) {
                        truncations.add(
                                new ObservationTruncation(
                                        ObservationTruncationType.SELECT_OPTIONS,
                                        originalOptions,
                                        options.size(),
                                        Optional.of(element.id())));
                    }
                }
                if (element.role() == ElementRole.BUTTON) {
                    actions.add(element);
                    SemanticRelationshipType type =
                            "submit".equalsIgnoreCase(element.attributes().getOrDefault("type", ""))
                                    ? SemanticRelationshipType.SUBMITS
                                    : SemanticRelationshipType.BELONGS_TO;
                    relationships.add(new SemanticRelationship(element.id(), form.id(), type));
                }
            }
            forms.add(
                    new FormObservation(
                            form.id(),
                            form.accessibleName(),
                            Optional.ofNullable(form.attributes().get("action-resolved"))
                                    .or(() -> Optional.ofNullable(form.attributes().get("action"))),
                            form.attributes().getOrDefault("method", "GET"),
                            fields,
                            actions,
                            form.attributes()
                                    .getOrDefault("aria-invalid", "false")
                                    .equalsIgnoreCase("false")));
        }
        return new FormResult(forms, relationships, truncations);
    }

    public record FormResult(
            List<FormObservation> forms,
            List<SemanticRelationship> relationships,
            List<ObservationTruncation> truncations) {

        public FormResult {
            forms = List.copyOf(forms);
            relationships = List.copyOf(relationships);
            truncations = List.copyOf(truncations);
        }
    }
}
