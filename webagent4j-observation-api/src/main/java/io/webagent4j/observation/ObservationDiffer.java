package io.webagent4j.observation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic semantic snapshot differ that does not inspect raw HTML. */
public final class ObservationDiffer {

    /** Compares two observations using semantic identity and stable locator evidence. */
    public ObservationDiff diff(Observation before, Observation after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        MatchResult matches = match(before.elements(), after.elements());
        List<ChangedSemanticElement> changed = new ArrayList<>();
        matches.pairs()
                .forEach(
                        pair -> {
                            Set<ChangedProperty> properties =
                                    changedProperties(pair.before(), pair.after());
                            if (!properties.isEmpty()) {
                                changed.add(
                                        new ChangedSemanticElement(
                                                pair.before(), pair.after(), properties));
                            }
                        });
        return new ObservationDiff(
                matches.added(),
                matches.removed(),
                changed,
                !before.url().equals(after.url()),
                !before.title().equals(after.title()),
                dialogDifference(after.dialogs(), before.dialogs()),
                dialogDifference(before.dialogs(), after.dialogs()));
    }

    private static MatchResult match(List<SemanticElement> before, List<SemanticElement> after) {
        Map<SemanticElementId, SemanticElement> remainingAfter = new LinkedHashMap<>();
        after.forEach(element -> remainingAfter.put(element.id(), element));
        List<ElementPair> pairs = new ArrayList<>();
        List<SemanticElement> unmatchedBefore = new ArrayList<>();
        for (SemanticElement element : before) {
            SemanticElement direct = remainingAfter.remove(element.id());
            if (direct != null && direct.role() == element.role()) {
                pairs.add(new ElementPair(element, direct));
            } else {
                if (direct != null) {
                    remainingAfter.put(direct.id(), direct);
                }
                unmatchedBefore.add(element);
            }
        }

        Map<String, ArrayDeque<SemanticElement>> byStableKey = new LinkedHashMap<>();
        remainingAfter
                .values()
                .forEach(
                        element ->
                                byStableKey
                                        .computeIfAbsent(
                                                element.stableKey(), ignored -> new ArrayDeque<>())
                                        .add(element));
        List<SemanticElement> removed = new ArrayList<>();
        for (SemanticElement element : unmatchedBefore) {
            ArrayDeque<SemanticElement> candidates = byStableKey.get(element.stableKey());
            if (candidates == null || candidates.isEmpty()) {
                removed.add(element);
            } else {
                SemanticElement matched = candidates.removeFirst();
                remainingAfter.remove(matched.id());
                pairs.add(new ElementPair(element, matched));
            }
        }
        return new MatchResult(pairs, removed, new ArrayList<>(remainingAfter.values()));
    }

    private static EnumSet<ChangedProperty> changedProperties(
            SemanticElement before, SemanticElement after) {
        EnumSet<ChangedProperty> result = EnumSet.noneOf(ChangedProperty.class);
        changed(result, before.role(), after.role(), ChangedProperty.ROLE);
        changed(
                result,
                before.accessibleName(),
                after.accessibleName(),
                ChangedProperty.ACCESSIBLE_NAME);
        changed(result, before.text(), after.text(), ChangedProperty.TEXT);
        changed(result, before.visible(), after.visible(), ChangedProperty.VISIBLE);
        changed(result, before.enabled(), after.enabled(), ChangedProperty.ENABLED);
        changed(
                result,
                before.state().editable(),
                after.state().editable(),
                ChangedProperty.EDITABLE);
        changed(
                result,
                before.state().readOnly(),
                after.state().readOnly(),
                ChangedProperty.READ_ONLY);
        changed(result, before.state().checked(), after.state().checked(), ChangedProperty.CHECKED);
        changed(
                result,
                before.state().selected(),
                after.state().selected(),
                ChangedProperty.SELECTED);
        changed(result, before.state().focused(), after.state().focused(), ChangedProperty.FOCUSED);
        changed(
                result,
                before.state().expanded(),
                after.state().expanded(),
                ChangedProperty.EXPANDED);
        changed(result, before.value(), after.value(), ChangedProperty.VALUE);
        changed(result, before.capabilities(), after.capabilities(), ChangedProperty.CAPABILITIES);
        changed(result, before.formId(), after.formId(), ChangedProperty.FORM_RELATIONSHIP);
        return result;
    }

    private static void changed(
            Set<ChangedProperty> target, Object before, Object after, ChangedProperty property) {
        if (!Objects.equals(before, after)) {
            target.add(property);
        }
    }

    private static List<DialogObservation> dialogDifference(
            List<DialogObservation> candidates, List<DialogObservation> other) {
        Set<String> otherKeys =
                other.stream()
                        .filter(DialogObservation::visible)
                        .map(dialog -> dialog.elementId() + "|" + dialog.name())
                        .collect(java.util.stream.Collectors.toSet());
        return candidates.stream()
                .filter(DialogObservation::visible)
                .filter(dialog -> !otherKeys.contains(dialog.elementId() + "|" + dialog.name()))
                .toList();
    }

    private record ElementPair(SemanticElement before, SemanticElement after) {}

    private record MatchResult(
            List<ElementPair> pairs, List<SemanticElement> removed, List<SemanticElement> added) {}
}
