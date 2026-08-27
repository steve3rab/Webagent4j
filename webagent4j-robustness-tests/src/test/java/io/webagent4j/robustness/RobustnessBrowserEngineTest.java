package io.webagent4j.robustness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RobustnessBrowserEngineTest {

    @Test
    void defaultsToChromiumWhenThePropertyIsAbsent() {
        assertThat(RobustnessBrowserEngine.fromPropertyValue(""))
                .isEqualTo(RobustnessBrowserEngine.CHROMIUM);
    }

    @Test
    void treatsAWhitespaceOnlyValueAsAbsent() {
        assertThat(RobustnessBrowserEngine.fromPropertyValue("   "))
                .isEqualTo(RobustnessBrowserEngine.CHROMIUM);
    }

    @Test
    void acceptsTheThreeExactAllowedValues() {
        assertThat(RobustnessBrowserEngine.fromPropertyValue("chromium"))
                .isEqualTo(RobustnessBrowserEngine.CHROMIUM);
        assertThat(RobustnessBrowserEngine.fromPropertyValue("firefox"))
                .isEqualTo(RobustnessBrowserEngine.FIREFOX);
        assertThat(RobustnessBrowserEngine.fromPropertyValue("webkit"))
                .isEqualTo(RobustnessBrowserEngine.WEBKIT);
    }

    @Test
    void rejectsAnUnknownExplicitValueRatherThanFallingBackToChromium() {
        assertThatThrownBy(() -> RobustnessBrowserEngine.fromPropertyValue("safari"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("safari");
    }

    @Test
    void rejectsUnrecognizedRandomValues() {
        assertThatThrownBy(() -> RobustnessBrowserEngine.fromPropertyValue("random"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("random");
    }

    @Test
    void rejectsAliasesRatherThanSilentlyNormalizingThem() {
        assertThatThrownBy(() -> RobustnessBrowserEngine.fromPropertyValue("chrome"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RobustnessBrowserEngine.fromPropertyValue("ff"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCaseVariantsToKeepTheContractExactAndDeterministic() {
        assertThatThrownBy(() -> RobustnessBrowserEngine.fromPropertyValue("Chromium"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RobustnessBrowserEngine.fromPropertyValue("FIREFOX"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void propertyValueRenderingRoundTripsForEveryEngine() {
        for (RobustnessBrowserEngine engine : RobustnessBrowserEngine.values()) {
            assertThat(RobustnessBrowserEngine.fromPropertyValue(engine.propertyValue()))
                    .isEqualTo(engine);
        }
    }

    @Test
    void currentReadsTheRobustnessBrowserSystemProperty() {
        String previous = System.getProperty("robustness.browser");
        try {
            System.clearProperty("robustness.browser");
            assertThat(RobustnessBrowserEngine.current())
                    .isEqualTo(RobustnessBrowserEngine.CHROMIUM);

            System.setProperty("robustness.browser", "webkit");
            assertThat(RobustnessBrowserEngine.current()).isEqualTo(RobustnessBrowserEngine.WEBKIT);

            System.setProperty("robustness.browser", "firefox");
            assertThat(RobustnessBrowserEngine.current())
                    .isEqualTo(RobustnessBrowserEngine.FIREFOX);
        } finally {
            if (previous == null) {
                System.clearProperty("robustness.browser");
            } else {
                System.setProperty("robustness.browser", previous);
            }
        }
    }
}
