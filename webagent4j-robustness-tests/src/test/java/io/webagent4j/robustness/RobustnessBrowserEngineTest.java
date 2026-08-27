package io.webagent4j.robustness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RobustnessBrowserEngineTest {

    @Test
    void defaultsToChromiumWhenThePropertyValueIsNull() {
        assertThat(RobustnessBrowserEngine.fromPropertyValue(null))
                .isEqualTo(RobustnessBrowserEngine.CHROMIUM);
    }

    @Test
    void defaultsToChromiumWhenThePropertyValueIsAnExplicitEmptyString() {
        assertThat(RobustnessBrowserEngine.fromPropertyValue(""))
                .isEqualTo(RobustnessBrowserEngine.CHROMIUM);
    }

    @Test
    void rejectsAWhitespaceOnlyValueRatherThanTreatingItAsAbsent() {
        // An explicitly supplied whitespace-only value is real invalid input, not the same as
        // the property being absent -- it must fail, not silently default to Chromium.
        assertThatThrownBy(() -> RobustnessBrowserEngine.fromPropertyValue("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsValuesWithLeadingOrTrailingWhitespaceRatherThanTrimmingThem() {
        assertThatThrownBy(() -> RobustnessBrowserEngine.fromPropertyValue(" firefox "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RobustnessBrowserEngine.fromPropertyValue("webkit "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RobustnessBrowserEngine.fromPropertyValue(" chromium"))
                .isInstanceOf(IllegalArgumentException.class);
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
        assertThatThrownBy(() -> RobustnessBrowserEngine.fromPropertyValue("FF"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAPaddedCaseVariantEvenThoughEachDefectAloneWouldAlsoFail() {
        assertThatThrownBy(() -> RobustnessBrowserEngine.fromPropertyValue(" Chromium "))
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

    @Test
    void currentDistinguishesAnAbsentPropertyFromAnExplicitWhitespaceOnlyValue() {
        String previous = System.getProperty("robustness.browser");
        try {
            System.clearProperty("robustness.browser");
            assertThat(RobustnessBrowserEngine.current())
                    .as("an absent property defaults to Chromium")
                    .isEqualTo(RobustnessBrowserEngine.CHROMIUM);

            System.setProperty("robustness.browser", "   ");
            assertThatThrownBy(RobustnessBrowserEngine::current)
                    .as("an explicit whitespace-only property must fail, not default")
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            if (previous == null) {
                System.clearProperty("robustness.browser");
            } else {
                System.setProperty("robustness.browser", previous);
            }
        }
    }
}
