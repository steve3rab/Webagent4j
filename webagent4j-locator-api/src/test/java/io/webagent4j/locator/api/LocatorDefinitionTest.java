package io.webagent4j.locator.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LocatorDefinitionTest {

    @Test
    void buildsRichImmutableSemanticDefinitions() {
        LocatorDefinition base = LocatorDefinitions.element();
        LocatorDefinition definition =
                base.role(ElementRole.BUTTON)
                        .named(" Sign in ")
                        .nameContaining("Sign")
                        .fuzzyName("Log in")
                        .withAccessibleName(TextMatch.exact("Sign in"))
                        .labelled("Account action")
                        .withPlaceholder(TextMatch.containing("email"))
                        .withTitle(TextMatch.exactIgnoringCase("Open"))
                        .withAltText(TextMatch.fuzzy("Profile image"))
                        .withVisibleText(new TextMatch(TextMatchType.STARTS_WITH, "Sign"))
                        .withId("submit")
                        .withNameAttribute("submitAction")
                        .withAttribute("data-kind", "primary")
                        .withTestId("login-submit")
                        .visibleOnly()
                        .hiddenOnly()
                        .enabledOnly()
                        .disabledOnly()
                        .editableOnly()
                        .readOnlyOnly()
                        .checkedOnly()
                        .selectedOnly()
                        .focusedOnly()
                        .inViewportOnly()
                        .clickableOnly()
                        .coveredOnly()
                        .stableFor(Duration.ofMillis(100))
                        .withTimeout(Duration.ofSeconds(2))
                        .waitingUntilVisible();

        assertThat(base).isNotEqualTo(definition);
        assertThat(definition.role()).contains(ElementRole.BUTTON);
        assertThat(definition.accessibleName()).contains(TextMatch.exact("Sign in"));
        assertThat(definition.label()).contains(TextMatch.exactIgnoringCase("Account action"));
        assertThat(definition.attributes()).containsEntry("data-kind", "primary");
        assertThat(definition.visible()).contains(true);
        assertThat(definition.enabled()).contains(false);
        assertThat(definition.editable()).contains(true);
        assertThat(definition.checked()).contains(true);
        assertThat(definition.selected()).contains(true);
        assertThat(definition.readOnly()).contains(true);
        assertThat(definition.focused()).contains(true);
        assertThat(definition.inViewport()).contains(true);
        assertThat(definition.clickable()).contains(true);
        assertThat(definition.covered()).contains(true);
        assertThat(definition.stability()).contains(Duration.ofMillis(100));
        assertThat(definition.waitUntilVisible()).isTrue();
    }

    @Test
    void createsSelectorAndRoleDefinitions() {
        assertThat(LocatorDefinition.forRole(ElementRole.LINK).role()).contains(ElementRole.LINK);
        assertThat(LocatorDefinition.css(" .primary ").css()).contains(".primary");
        assertThat(LocatorDefinition.xpath(" //button ").xpath()).contains("//button");
        assertThat(TextMatch.containing("docs").type()).isEqualTo(TextMatchType.CONTAINS);
        assertThat(new TextMatch(TextMatchType.ENDS_WITH, "end").value()).isEqualTo("end");
        assertThat(new TextMatch(TextMatchType.REGEX, "Sign.*").type())
                .isEqualTo(TextMatchType.REGEX);
    }

    @Test
    void rejectsInvalidDefinitionsAndTextCriteria() {
        assertThatThrownBy(() -> TextMatch.exact(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextMatch(TextMatchType.REGEX, "["))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocatorDefinition.element().withTimeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocatorDefinition.css(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new LocatorDefinition(
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Map.of(),
                                        Optional.empty(),
                                        Optional.of(".x"),
                                        Optional.of("//x"),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        false,
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocatorDefinition.element().withAttribute(" ", "value"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocatorDefinition.element().stableFor(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
