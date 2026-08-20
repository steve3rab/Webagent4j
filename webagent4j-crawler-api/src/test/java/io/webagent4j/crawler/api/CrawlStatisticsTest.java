package io.webagent4j.crawler.api;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class CrawlStatisticsTest {

    @Test
    void rejectsNegativeCounters() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CrawlStatistics(-1, 0, 0, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void acceptsAllZeroStatistics() {
        new CrawlStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
