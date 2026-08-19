package io.webagent4j.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.webagent4j.locator.api.TextMatch;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link FrameDefinition} mirrors {@link io.webagent4j.locator.api.LocatorDefinition}'s
 * immutable-copy pattern and rejects malformed criteria the same way, instead of silently
 * normalizing or dropping them.
 */
class FrameDefinitionTest {

    @Test
    void unconstrainedFactoryProducesAnEmptyMatchAnyDefinition() {
        FrameDefinition definition = FrameDefinition.frame();

        assertThat(definition.id()).isEmpty();
        assertThat(definition.name()).isEmpty();
        assertThat(definition.title()).isEmpty();
        assertThat(definition.url()).isEmpty();
        assertThat(definition.timeout()).isEmpty();
        assertThat(definition.unconstrained()).isTrue();
    }

    @Test
    void copyMethodsAccumulateWithoutMutatingTheOriginal() {
        FrameDefinition base = FrameDefinition.frame();

        FrameDefinition definition =
                base.withId("checkout-frame")
                        .named("checkout")
                        .withTitle("Checkout")
                        .withUrl(TextMatch.containing("/checkout"))
                        .withTimeout(Duration.ofSeconds(3));

        assertThat(base.unconstrained()).isTrue();
        assertThat(definition.unconstrained()).isFalse();
        assertThat(definition.id()).contains("checkout-frame");
        assertThat(definition.name()).contains(TextMatch.exactIgnoringCase("checkout"));
        assertThat(definition.title()).contains(TextMatch.exactIgnoringCase("Checkout"));
        assertThat(definition.url()).contains(TextMatch.containing("/checkout"));
        assertThat(definition.timeout()).contains(Duration.ofSeconds(3));
    }

    @Test
    void rejectsBlankIdAndTitleAndName() {
        FrameDefinition definition = FrameDefinition.frame();

        assertThatIllegalArgumentException().isThrownBy(() -> definition.withId("  "));
        assertThatIllegalArgumentException().isThrownBy(() -> definition.named(" "));
        assertThatIllegalArgumentException().isThrownBy(() -> definition.withTitle(""));
    }

    @Test
    void rejectsNullCriteria() {
        FrameDefinition definition = FrameDefinition.frame();

        assertThatNullPointerException().isThrownBy(() -> definition.withId(null));
        assertThatNullPointerException().isThrownBy(() -> definition.named(null));
        assertThatNullPointerException().isThrownBy(() -> definition.withTitle(null));
        assertThatNullPointerException().isThrownBy(() -> definition.withUrl(null));
        assertThatNullPointerException().isThrownBy(() -> definition.withTimeout(null));
    }

    @Test
    void rejectsANonPositiveTimeoutOverride() {
        FrameDefinition definition = FrameDefinition.frame();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> definition.withTimeout(Duration.ZERO));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> definition.withTimeout(Duration.ofSeconds(-1)));
    }

    @Test
    void trimsIdWhitespaceButPreservesTheSemanticValue() {
        FrameDefinition definition = FrameDefinition.frame().withId(" checkout ");

        assertThat(definition.id()).contains("checkout");
    }
}
