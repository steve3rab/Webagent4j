package io.webagent4j.extraction.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExtractedTableTest {

    private static final ExtractedTable TABLE =
            new ExtractedTable(
                    List.of("Name", "Price"),
                    List.of(
                            new ExtractedRow(List.of("Laptop B", "999")),
                            new ExtractedRow(List.of("Mouse", "19"))));

    @Test
    void cellByIndexReadsDeterministicallyByRowAndColumn() {
        assertThat(TABLE.cell(0, 0)).isEqualTo("Laptop B");
        assertThat(TABLE.cell(1, 1)).isEqualTo("19");
    }

    @Test
    void cellByHeaderResolvesTheColumnFromTheHeaderName() {
        assertThat(TABLE.cell(0, "Price")).contains("999");
    }

    @Test
    void cellByHeaderIsEmptyForAnUnknownHeader() {
        assertThat(TABLE.cell(0, "Weight")).isEmpty();
    }

    @Test
    void rowSizeReflectsItsCellCount() {
        assertThat(TABLE.rows().get(0).size()).isEqualTo(2);
    }
}
