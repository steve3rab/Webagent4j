package io.webagent4j.locator;

import io.webagent4j.locator.api.LocatorDefinition;
import io.webagent4j.locator.api.TextMatch;
import io.webagent4j.locator.api.TextMatchType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Builds small exact-first plans from immutable locator definitions. */
public final class LocatorPlanFactory {

    /** Creates a deterministic discovery plan for the supplied definition. */
    public LocatorPlan create(LocatorDefinition definition) {
        List<LocatorPlanStep> steps = new ArrayList<>();
        if (definition.css().isPresent()) {
            addValue(steps, LocatorStrategyType.CSS, definition, definition.css().orElseThrow());
            return new LocatorPlan(steps);
        }
        if (definition.xpath().isPresent()) {
            addValue(
                    steps, LocatorStrategyType.XPATH, definition, definition.xpath().orElseThrow());
            return new LocatorPlan(steps);
        }
        if (definition.testId().isPresent()) {
            addValue(
                    steps,
                    LocatorStrategyType.TEST_ID,
                    definition,
                    definition.testId().orElseThrow());
            return new LocatorPlan(steps);
        }
        if (definition.id().isPresent()) {
            addValue(steps, LocatorStrategyType.ID, definition, definition.id().orElseThrow());
            return new LocatorPlan(steps);
        }
        if (!definition.attributes().isEmpty()) {
            var attribute = definition.attributes().entrySet().iterator().next();
            steps.add(
                    step(
                            LocatorStrategyType.ATTRIBUTE,
                            definition,
                            Optional.empty(),
                            Optional.of(attribute.getKey()),
                            Optional.of(attribute.getValue())));
            return new LocatorPlan(steps);
        }
        if (definition.nameAttribute().isPresent()) {
            addValue(
                    steps,
                    LocatorStrategyType.NAME_ATTRIBUTE,
                    definition,
                    definition.nameAttribute().orElseThrow());
            return new LocatorPlan(steps);
        }
        if (definition.label().isPresent()) {
            addText(steps, LocatorStrategyType.LABEL, definition, definition.label().orElseThrow());
            return new LocatorPlan(steps);
        }
        if (definition.placeholder().isPresent()) {
            addText(
                    steps,
                    LocatorStrategyType.PLACEHOLDER,
                    definition,
                    definition.placeholder().orElseThrow());
            return new LocatorPlan(steps);
        }
        if (definition.title().isPresent()) {
            addText(steps, LocatorStrategyType.TITLE, definition, definition.title().orElseThrow());
            return new LocatorPlan(steps);
        }
        if (definition.altText().isPresent()) {
            addText(
                    steps,
                    LocatorStrategyType.ALT_TEXT,
                    definition,
                    definition.altText().orElseThrow());
            return new LocatorPlan(steps);
        }
        if (definition.visibleText().isPresent()) {
            addText(
                    steps,
                    LocatorStrategyType.VISIBLE_TEXT,
                    definition,
                    definition.visibleText().orElseThrow());
            return new LocatorPlan(steps);
        }
        if (definition.accessibleName().isPresent()) {
            addNamePlan(steps, definition, definition.accessibleName().orElseThrow());
            return new LocatorPlan(steps);
        }
        steps.add(
                step(
                        LocatorStrategyType.ROLE,
                        definition,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));
        return new LocatorPlan(steps);
    }

    private static void addNamePlan(
            List<LocatorPlanStep> steps, LocatorDefinition definition, TextMatch requested) {
        TextMatch exact =
                requested.type() == TextMatchType.FUZZY
                        ? TextMatch.exactIgnoringCase(requested.value())
                        : requested;
        addText(steps, LocatorStrategyType.ACCESSIBLE_NAME, definition, exact);
        if (exact.type() != TextMatchType.CONTAINS && exact.type() != TextMatchType.REGEX) {
            addText(steps, LocatorStrategyType.LABEL, definition, exact);
        }
        addText(steps, LocatorStrategyType.VISIBLE_TEXT, definition, exact);
        addText(
                steps,
                LocatorStrategyType.FUZZY_TEXT,
                definition,
                TextMatch.fuzzy(requested.value()));
    }

    private static void addText(
            List<LocatorPlanStep> steps,
            LocatorStrategyType type,
            LocatorDefinition definition,
            TextMatch text) {
        steps.add(step(type, definition, Optional.of(text), Optional.empty(), Optional.empty()));
    }

    private static void addValue(
            List<LocatorPlanStep> steps,
            LocatorStrategyType type,
            LocatorDefinition definition,
            String value) {
        steps.add(step(type, definition, Optional.empty(), Optional.empty(), Optional.of(value)));
    }

    private static LocatorPlanStep step(
            LocatorStrategyType type,
            LocatorDefinition definition,
            Optional<TextMatch> text,
            Optional<String> attributeName,
            Optional<String> value) {
        LocatorBackendQuery query =
                new LocatorBackendQuery(type, definition.role(), text, attributeName, value);
        return new LocatorPlanStep(query, type.name().toLowerCase().replace('_', ' '));
    }
}
