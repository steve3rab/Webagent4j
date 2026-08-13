package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.core.WebAgent;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class DialogObservationIT {

    @Test
    void observesVisibleModalDialogAndOwnedAction() throws IOException {
        try (ObservationTestApplication application = ObservationTestApplication.start();
                IBrowser browser =
                        WebAgent.browser().playwright().chromium().headless(true).launch()) {
            var observation = browser.open(application.url("/observation/dialogs")).observe();

            assertThat(observation.dialogs())
                    .singleElement()
                    .satisfies(
                            dialog -> {
                                assertThat(dialog.name()).isEqualTo("Notice");
                                assertThat(dialog.modal()).isTrue();
                                assertThat(dialog.visible()).isTrue();
                                assertThat(dialog.interactiveElements()).hasSize(1);
                            });
        }
    }
}
