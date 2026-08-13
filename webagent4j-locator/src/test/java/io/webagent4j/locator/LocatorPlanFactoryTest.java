package io.webagent4j.locator;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.LocatorDefinition;
import io.webagent4j.locator.api.TextMatch;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocatorPlanFactoryTest {

    private final LocatorPlanFactory factory = new LocatorPlanFactory();

    @Test
    void createsFocusedPlansForExplicitAndAttributeQueries() {
        assertFirst(LocatorDefinition.css(".x"), LocatorStrategyType.CSS);
        assertFirst(LocatorDefinition.xpath("//button"), LocatorStrategyType.XPATH);
        assertFirst(LocatorDefinition.element().withTestId("submit"), LocatorStrategyType.TEST_ID);
        assertFirst(LocatorDefinition.element().withId("submit"), LocatorStrategyType.ID);
        assertFirst(
                LocatorDefinition.element().withAttribute("data-x", "y"),
                LocatorStrategyType.ATTRIBUTE);
        assertFirst(
                LocatorDefinition.element().withNameAttribute("action"),
                LocatorStrategyType.NAME_ATTRIBUTE);
    }

    @Test
    void createsSemanticExactFirstFallbackPlans() {
        assertFirst(
                LocatorDefinition.forRole(ElementRole.TEXTBOX).labelled("Email"),
                LocatorStrategyType.LABEL);
        assertFirst(
                LocatorDefinition.element().withPlaceholder(TextMatch.exact("Email")),
                LocatorStrategyType.PLACEHOLDER);
        assertFirst(
                LocatorDefinition.element().withTitle(TextMatch.exact("Open")),
                LocatorStrategyType.TITLE);
        assertFirst(
                LocatorDefinition.element().withAltText(TextMatch.exact("Avatar")),
                LocatorStrategyType.ALT_TEXT);
        assertFirst(
                LocatorDefinition.element().withVisibleText(TextMatch.exact("Continue")),
                LocatorStrategyType.VISIBLE_TEXT);
        assertFirst(LocatorDefinition.forRole(ElementRole.BUTTON), LocatorStrategyType.ROLE);

        List<LocatorStrategyType> types =
                factory
                        .create(LocatorDefinition.forRole(ElementRole.BUTTON).fuzzyName("Sign in"))
                        .steps()
                        .stream()
                        .map(step -> step.query().strategy())
                        .toList();
        assertThat(types)
                .containsExactly(
                        LocatorStrategyType.ACCESSIBLE_NAME,
                        LocatorStrategyType.LABEL,
                        LocatorStrategyType.VISIBLE_TEXT,
                        LocatorStrategyType.FUZZY_TEXT);
    }

    private void assertFirst(LocatorDefinition definition, LocatorStrategyType expected) {
        assertThat(factory.create(definition).steps().get(0).query().strategy())
                .isEqualTo(expected);
    }
}
