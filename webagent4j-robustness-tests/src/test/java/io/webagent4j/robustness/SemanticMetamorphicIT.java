package io.webagent4j.robustness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IPage;
import io.webagent4j.core.WebAgent;
import io.webagent4j.locator.AmbiguousLocatorException;
import io.webagent4j.locator.LocatorConfig;
import io.webagent4j.locator.LocatorNotFoundException;
import io.webagent4j.locator.LocatorResolutionPolicy;
import io.webagent4j.locator.LocatorResolutionStatus;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@Tag("robustness")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SemanticMetamorphicIT {

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
    void structuralMutationsPreserveSemanticObservationAndResolution() {
        try (IPage page = browser.open(application.fixtureUrl("clean/semantic-controls.html"))) {
            var before = page.observe();
            List<String> buttonsBefore =
                    before.buttons().stream().map(button -> button.accessibleName()).toList();

            page.evaluate(
                    """
                    (() => {
                      const button = document.querySelector('[data-target="clean-save"]');
                      const wrapper = document.createElement('div');
                      wrapper.className = 'generated-layout-8472';
                      button.before(wrapper);
                      wrapper.append(button);
                      button.className = 'generated-control-1938';
                      button.innerHTML = '<span>Save</span> <span>profile</span>'
                        + '<svg aria-hidden="true"></svg>';
                    })()
                    """);

            var after = page.observe();
            var resolved = page.find().button().named("Save profile").single();
            assertThat(resolved.attributes().get("data-target")).isEqualTo("clean-save");
            assertThat(after.buttons().stream().map(button -> button.accessibleName()).toList())
                    .isEqualTo(buttonsBefore);
            assertThat(before.diff(after).empty()).isTrue();
            assertThat(after.fingerprint()).isEqualTo(before.fingerprint());
        }
    }

    @Test
    void semanticMutationsChangeTheResolutionOutcomeSafely() {
        LocatorConfig strict =
                LocatorConfig.builder().resolutionPolicy(LocatorResolutionPolicy.STRICT).build();
        try (IPage page = browser.open(application.fixtureUrl("clean/semantic-controls.html"))) {
            page.evaluate(
                    "document.querySelector('[data-target=\"clean-save\"]')"
                            + ".setAttribute('aria-label', 'Archive profile')");
            assertThatThrownBy(() -> page.find(strict).button().named("Save profile").single())
                    .isInstanceOf(LocatorNotFoundException.class);
            assertThat(page.find(strict).button().named("Archive profile").single()).isNotNull();

            page.evaluate(
                    "document.querySelector('[data-target=\"clean-save\"]')"
                            + ".setAttribute('disabled', '')");
            assertThatThrownBy(
                            () ->
                                    page.find(strict)
                                            .button()
                                            .named("Archive profile")
                                            .enabled()
                                            .single())
                    .isInstanceOf(LocatorNotFoundException.class)
                    .extracting(error -> ((LocatorNotFoundException) error).status())
                    .isEqualTo(LocatorResolutionStatus.NOT_INTERACTABLE);

            page.evaluate(
                    """
                    (() => {
                      const original = document.querySelector('[data-target="clean-save"]');
                      original.removeAttribute('disabled');
                      const duplicate = original.cloneNode(true);
                      duplicate.dataset.target = 'clean-save-decoy';
                      original.after(duplicate);
                    })()
                    """);
            assertThatThrownBy(() -> page.find(strict).button().named("Archive profile").single())
                    .isInstanceOf(AmbiguousLocatorException.class);
        }
    }
}
