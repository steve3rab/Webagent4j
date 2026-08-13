package io.webagent4j.verification;

import static io.webagent4j.verification.Verifications.allOf;
import static io.webagent4j.verification.Verifications.anyOf;
import static io.webagent4j.verification.Verifications.attributeEquals;
import static io.webagent4j.verification.Verifications.elementChecked;
import static io.webagent4j.verification.Verifications.elementDisabled;
import static io.webagent4j.verification.Verifications.elementEditable;
import static io.webagent4j.verification.Verifications.elementEnabled;
import static io.webagent4j.verification.Verifications.elementExists;
import static io.webagent4j.verification.Verifications.elementFocused;
import static io.webagent4j.verification.Verifications.elementHidden;
import static io.webagent4j.verification.Verifications.elementNotExists;
import static io.webagent4j.verification.Verifications.elementSelected;
import static io.webagent4j.verification.Verifications.elementUnchecked;
import static io.webagent4j.verification.Verifications.elementVisible;
import static io.webagent4j.verification.Verifications.not;
import static io.webagent4j.verification.Verifications.textContains;
import static io.webagent4j.verification.Verifications.textEquals;
import static io.webagent4j.verification.Verifications.titleContains;
import static io.webagent4j.verification.Verifications.titleEquals;
import static io.webagent4j.verification.Verifications.urlEquals;
import static io.webagent4j.verification.Verifications.urlMatches;
import static io.webagent4j.verification.Verifications.valueEquals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.IElementReference;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class VerificationEngineTest {

    private final IVerificationContext context =
            new IVerificationContext() {
                @Override
                public String url() {
                    return "https://local.test/checkout";
                }

                @Override
                public String title() {
                    return "Checkout complete";
                }
            };

    @Test
    void evaluatesPageAndCompositeVerifications() {
        assertThat(urlEquals(context.url()).verify(context).success()).isTrue();
        assertThat(urlMatches(Pattern.compile(".*/checkout")).verify(context).success()).isTrue();
        assertThat(titleEquals(context.title()).verify(context).success()).isTrue();
        assertThat(titleContains("complete").verify(context).success()).isTrue();
        assertThat(
                        allOf(urlEquals(context.url()), titleContains("Checkout"))
                                .verify(context)
                                .success())
                .isTrue();
        assertThat(anyOf(urlEquals("wrong"), titleContains("complete")).verify(context).success())
                .isTrue();
        assertThat(not(urlEquals("wrong")).verify(context).success()).isTrue();
    }

    @Test
    void evaluatesEveryCoreElementStateAndValue() {
        IElement element = mock(IElement.class);
        when(element.state())
                .thenReturn(
                        new ElementState(
                                true, true, true, true, false, true, true, true, true, true, false,
                                true));
        when(element.text()).thenReturn("Ready now");
        when(element.attributes()).thenReturn(Map.of("data-state", "ready", "value", "42"));
        IElementReference<IElement> target = () -> element;

        assertThat(elementExists(target).verify(context).success()).isTrue();
        assertThat(elementVisible(target).verify(context).success()).isTrue();
        assertThat(elementEnabled(target).verify(context).success()).isTrue();
        assertThat(elementEditable(target).verify(context).success()).isTrue();
        assertThat(elementChecked(target).verify(context).success()).isTrue();
        assertThat(elementSelected(target).verify(context).success()).isTrue();
        assertThat(elementFocused(target).verify(context).success()).isTrue();
        assertThat(textEquals(target, "Ready now").verify(context).success()).isTrue();
        assertThat(textContains(target, "Ready").verify(context).success()).isTrue();
        assertThat(attributeEquals(target, "data-state", "ready").verify(context).success())
                .isTrue();
        assertThat(valueEquals(target, "42").verify(context).success()).isTrue();
        assertThat(elementHidden(target).verify(context).success()).isFalse();
        assertThat(elementDisabled(target).verify(context).success()).isFalse();
        assertThat(elementUnchecked(target).verify(context).success()).isFalse();
    }

    @Test
    void treatsResolutionFailureAsElementAbsence() {
        IElementReference<IElement> missing =
                () -> {
                    throw new IllegalStateException("missing");
                };
        assertThat(elementNotExists(missing).verify(context).success()).isTrue();
    }

    @Test
    void pollsVerificationWithoutRepeatingExternalSideEffects() {
        AtomicInteger evaluations = new AtomicInteger();
        IVerification delayed =
                current ->
                        new VerificationResult(
                                evaluations.incrementAndGet() >= 3, "delayed", "state");

        List<VerificationResult> results =
                new VerificationEngine()
                        .awaitAll(
                                context,
                                List.of(delayed),
                                Duration.ofSeconds(1),
                                Duration.ofMillis(1));

        assertThat(results)
                .singleElement()
                .satisfies(result -> assertThat(result.success()).isTrue());
        assertThat(evaluations).hasValue(3);
    }

    @Test
    void rejectsInvalidFactoryAndPollingArguments() {
        assertThatThrownBy(() -> titleEquals(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> allOf()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new VerificationPoller()
                                        .await(
                                                urlEquals("x"),
                                                context,
                                                Duration.ZERO,
                                                Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
