package io.webagent4j.browsercrawler;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BrowserCrawlStatisticsTest {

    @Test
    void negativeCounterRejected() {
        assertThatThrownBy(() -> new BrowserCrawlStatistics(-1, 0, 0, 0, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("discoveredUrls");
    }

    @Test
    void allZeroIsValid() {
        new BrowserCrawlStatistics(0, 0, 0, 0, 0, 0, 0, 0);
    }
}
