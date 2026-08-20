package io.webagent4j.crawler.api;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CrawlResultTest {

    @Test
    void defensivelyCopiesPagesList() {
        List<CrawledPage> pages = new ArrayList<>();
        CrawlResult result =
                new CrawlResult(
                        pages,
                        List.of(),
                        new CrawlStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0),
                        List.of(),
                        CrawlTerminationReason.COMPLETED);

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> result.pages().add(null));
    }
}
