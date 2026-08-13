package io.webagent4j.observation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TableObserverTest {

    @Test
    void reportsBoundedRowsColumnsAndCounts() {
        Observation observation = ObserverTestSupport.observeRich();

        assertThat(observation.tables())
                .singleElement()
                .satisfies(
                        table -> {
                            assertThat(table.headers()).containsExactly("Number", "Total");
                            assertThat(table.rowCount()).isEqualTo(5);
                            assertThat(table.rows()).hasSize(2);
                            assertThat(table.rowsTruncated()).isTrue();
                            assertThat(table.columnsTruncated()).isTrue();
                        });
    }
}
