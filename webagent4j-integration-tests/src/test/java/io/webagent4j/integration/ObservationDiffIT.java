package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.core.WebAgent;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.observation.ChangedProperty;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class ObservationDiffIT {

    @Test
    void reportsAddRemoveChangeDialogAndSelectionAcrossDynamicSnapshots() throws IOException {
        try (ObservationTestApplication application = ObservationTestApplication.start();
                IBrowser browser =
                        WebAgent.browser().playwright().chromium().headless(true).launch()) {
            var page = browser.open(application.url("/observation/dynamic"));
            var before = page.observe();
            page.evaluate("mutateObservationPage()");
            var diff = before.diff(page.observe());

            assertThat(diff.elementsAdded())
                    .extracting("role")
                    .contains(ElementRole.STATUS, ElementRole.DIALOG);
            assertThat(diff.elementsRemoved())
                    .extracting("accessibleName")
                    .contains("Dismiss old notification");
            assertThat(diff.elementsChanged())
                    .anySatisfy(
                            change ->
                                    assertThat(change.changedProperties())
                                            .contains(ChangedProperty.SELECTED));
            assertThat(diff.dialogsOpened())
                    .singleElement()
                    .satisfies(
                            dialog -> assertThat(dialog.name()).isEqualTo("Notification details"));
            assertThat(diff.dialogsClosed()).isEmpty();
        }
    }
}
