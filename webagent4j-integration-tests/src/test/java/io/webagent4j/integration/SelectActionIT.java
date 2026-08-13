package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SelectActionIT {

    @Test
    void selectsByValueLabelAndIndex() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/select")) {
            var country = page.find().select().labelled("Country").single();

            page.action().selectByValue(country, "de").execute().throwIfFailed();
            assertThat(country.value()).isEqualTo("de");
            page.action().selectByLabel(country, "France").execute().throwIfFailed();
            assertThat(country.value()).isEqualTo("fr");
            page.action().selectByIndex(country, 1).execute().throwIfFailed();
            assertThat(country.value()).isEqualTo("de");
        }
    }
}
