package io.webagent4j.robustness;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IPage;
import io.webagent4j.core.WebAgent;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.observation.ElementCapability;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@Tag("robustness")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SemanticConsistencyIT {

    private RobustnessTestApplication application;
    private IBrowser browser;

    @BeforeAll
    void startInfrastructure() throws Exception {
        application = RobustnessTestApplication.start();
        browser = WebAgent.browser().playwright().chromium().headless(true).launch();
    }

    @AfterAll
    void stopInfrastructure() {
        browser.close();
        application.close();
    }

    @Test
    void observedButtonReferenceResolvesToTheSameSemanticControl() {
        try (IPage page = browser.open(application.fixtureUrl("clean/semantic-controls.html"))) {
            var observed =
                    page.observe().buttons().stream()
                            .filter(button -> button.accessibleName().equals("Save profile"))
                            .findFirst()
                            .orElseThrow();
            var resolved = page.resolve(observed.reference().definition());

            assertThat(resolved.role()).isEqualTo(ElementRole.BUTTON);
            assertThat(resolved.accessibleName()).isEqualTo(observed.accessibleName());
            assertThat(resolved.attributes().get("data-target")).isEqualTo("clean-save");
            assertThat(observed.capabilities()).contains(ElementCapability.CLICK);
        }
    }

    @Test
    void observedFormLabelAndScopedLocatorResolveTheSameField() {
        try (IPage page = browser.open(application.fixtureUrl("forms/scoped-forms.html"))) {
            var billing =
                    page.observe().forms().stream()
                            .filter(form -> form.name().equals("Billing"))
                            .findFirst()
                            .orElseThrow();
            var observedEmail = billing.fields().getFirst();
            var form = page.find().role(ElementRole.FORM).named("Billing").single();
            var resolvedEmail = form.find().textbox().labelled("Email").single();

            assertThat(observedEmail.label()).isEqualTo("Email");
            assertThat(resolvedEmail.attributes().get("data-target"))
                    .isEqualTo("form-billing-email");
        }
    }
}
