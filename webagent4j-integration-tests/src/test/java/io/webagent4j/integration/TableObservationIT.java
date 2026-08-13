package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.core.WebAgent;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class TableObservationIT {

    @Test
    void observesNativeTableHeadersCountsAndRows() throws IOException {
        try (ObservationTestApplication application = ObservationTestApplication.start();
                IBrowser browser =
                        WebAgent.browser().playwright().chromium().headless(true).launch()) {
            var observation = browser.open(application.url("/observation/tables")).observe();

            assertThat(observation.tables())
                    .singleElement()
                    .satisfies(
                            table -> {
                                assertThat(table.name()).isEqualTo("Invoices");
                                assertThat(table.headers()).containsExactly("Number", "Total");
                                assertThat(table.rowCount()).isEqualTo(2);
                                assertThat(table.columnCount()).isEqualTo(2);
                                assertThat(table.rows()).contains(List.of("A-1", "10 EUR"));
                            });
        }
    }
}
