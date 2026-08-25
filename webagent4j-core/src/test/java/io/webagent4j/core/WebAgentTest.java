package io.webagent4j.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.common.BrowserException;
import io.webagent4j.common.Timeouts;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class WebAgentTest {

    @Test
    void exposesBuildVersion() {
        assertThat(WebAgent.VERSION)
                .isNotNull()
                .isNotBlank()
                .doesNotContain("${")
                .isNotEqualToIgnoringCase("unknown")
                .isNotEqualToIgnoringCase("dev")
                .matches("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?");

        assertThat(WebAgent.browser()).isNotNull();
    }

    @Test
    void reportsAnActionableErrorWhenNoBackendIsInstalled() {
        BrowserBuilder builder =
                WebAgent.browser()
                        .playwright()
                        .chromium()
                        .headless(false)
                        .locale(Locale.CANADA)
                        .timeouts(Timeouts.defaults());

        assertThatThrownBy(builder::launch)
                .isInstanceOf(BrowserException.class)
                .hasMessageContaining("webagent4j-browser-playwright");
    }
}
