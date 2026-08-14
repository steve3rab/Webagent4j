package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DryRunActionIT {

    @Test
    void dryRunClickDoesNotModifyPage() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/click")) {
            var target = page.find().button().named("Increment").reference();
            var result = page.action().click(target).dryRun().execute();

            assertThat(result.success()).isTrue();
            assertThat(result.diagnostics().details()).containsEntry("execution", "dry-run");
            // The page counter should remain at its initial value because the backend action was
            // not applied.
            assertThat(page.content()).contains("id=\"counter\">0");
        }
    }
}
