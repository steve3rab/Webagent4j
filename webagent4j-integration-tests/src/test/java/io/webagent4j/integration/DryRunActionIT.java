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
            assertThat(result.dryRun()).isTrue();
            assertThat(result.executed()).isFalse();
            assertThat(result.diagnostics().details()).containsEntry("execution", "dry-run");
            assertThat(page.content()).contains("id=\"counter\">0");
        }
    }

    @Test
    void dryRunTypeDoesNotModifyTheInputValue() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/type")) {
            var email = page.find().textbox().labelled("Email").single();

            var result = page.action().type(email, "secret@example.test").dryRun().execute();

            assertThat(result.success()).isTrue();
            assertThat(result.dryRun()).isTrue();
            assertThat(email.value()).isEmpty();
        }
    }

    @Test
    void dryRunNavigateDoesNotLeaveTheCurrentPage() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/navigation/one")) {
            var result = page.action().navigate(support.url("/navigation/two")).dryRun().execute();

            assertThat(result.success()).isTrue();
            assertThat(result.dryRun()).isTrue();
            assertThat(page.content()).contains("One");
            assertThat(page.content()).doesNotContain("Two");
        }
    }
}
