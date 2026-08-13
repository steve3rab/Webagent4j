package io.webagent4j.locator;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.dom.IElement;
import io.webagent4j.locator.LocatorTestFixtures.FakeBackend;
import io.webagent4j.locator.LocatorTestFixtures.TestElement;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.LocatorDefinition;
import io.webagent4j.locator.api.TextMatch;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocatorStrategyTest {

    private final LocatorEngine engine = new LocatorEngine();

    @Test
    void resolvesLabelsPlaceholdersTextAndAttributes() {
        IElement input =
                new TestElement(
                        ElementRole.TEXTBOX,
                        "Email address",
                        "",
                        "input",
                        Map.of(
                                "id",
                                "email",
                                "name",
                                "emailAddress",
                                "placeholder",
                                "Email address",
                                "title",
                                "Account email",
                                "data-testid",
                                "email-field"),
                        true,
                        true);
        FakeBackend backend = new FakeBackend(List.of(input));
        LocatorContext context = context(backend);

        assertSelected(
                context,
                LocatorDefinition.forRole(ElementRole.TEXTBOX).labelled("Email address"),
                LocatorStrategyType.LABEL);
        assertSelected(
                context,
                LocatorDefinition.element()
                        .withPlaceholder(TextMatch.exactIgnoringCase("email address")),
                LocatorStrategyType.PLACEHOLDER);
        assertSelected(
                context, LocatorDefinition.element().withId("email"), LocatorStrategyType.ID);
        assertSelected(
                context,
                LocatorDefinition.element().withNameAttribute("emailAddress"),
                LocatorStrategyType.NAME_ATTRIBUTE);
        assertSelected(
                context,
                LocatorDefinition.element().withTestId("email-field"),
                LocatorStrategyType.TEST_ID);
        assertSelected(
                context,
                LocatorDefinition.element().withTitle(TextMatch.exactIgnoringCase("account email")),
                LocatorStrategyType.TITLE);
    }

    @Test
    void resolvesVisibleAndAlternativeTextPlusExplicitSelectors() {
        IElement image =
                new TestElement(
                        ElementRole.IMAGE,
                        "Profile photo",
                        "",
                        "img",
                        Map.of("alt", "Profile photo"),
                        true,
                        true);
        IElement button = LocatorTestFixtures.element(ElementRole.BUTTON, "Continue");
        LocatorContext context = context(new FakeBackend(List.of(image, button)));

        assertSelected(
                context,
                LocatorDefinition.element()
                        .withAltText(TextMatch.exactIgnoringCase("profile photo")),
                LocatorStrategyType.ALT_TEXT);
        assertSelected(
                context,
                LocatorDefinition.element()
                        .withVisibleText(TextMatch.exactIgnoringCase("continue")),
                LocatorStrategyType.VISIBLE_TEXT);
        assertThat(engine.locate(context, LocatorDefinition.css("button")).strategy())
                .isEqualTo(LocatorStrategyType.CSS);
        assertThat(engine.locate(context, LocatorDefinition.xpath("//button")).strategy())
                .isEqualTo(LocatorStrategyType.XPATH);
        assertThat(engine.locate(context, LocatorDefinition.forRole(ElementRole.IMAGE)).strategy())
                .isEqualTo(LocatorStrategyType.ROLE);
    }

    private void assertSelected(
            LocatorContext context, LocatorDefinition definition, LocatorStrategyType strategy) {
        assertThat(engine.locate(context, definition).strategy()).isEqualTo(strategy);
    }

    private static LocatorContext context(FakeBackend backend) {
        return LocatorContext.page(backend, LocatorConfig.defaults(Duration.ofMillis(5)));
    }
}
