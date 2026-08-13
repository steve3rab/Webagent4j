package io.webagent4j.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ActionModelTest {

    @Test
    void keepsSecretOutOfStringRepresentationsWhileAllowingBackendUse() {
        String sensitive = "WEBAGENT4J_ACTION_SECRET";
        Secret secret = Secret.of(sensitive);
        AtomicReference<String> consumed = new AtomicReference<>();

        secret.use(
                value -> {
                    consumed.set(value);
                    return null;
                });

        assertThat(consumed).hasValue(sensitive);
        assertThat(secret).hasToString("[REDACTED]");
        assertThat(secret.toString()).doesNotContain(sensitive);
    }

    @Test
    void returnsAndThrowsStructuredActionFailure() {
        ActionFailure failure =
                new ActionFailure(
                        ActionFailureType.TARGET_NOT_INTERACTABLE,
                        "Target is disabled",
                        Optional.empty());
        ActionResult<Void> result =
                new ActionResult<>(
                        false, null, Duration.ofMillis(2), List.of(), Optional.of(failure));

        assertThat(result.success()).isFalse();
        assertThat(result.failure()).contains(failure);
        assertThatThrownBy(result::throwIfFailed)
                .isInstanceOf(ActionFailedException.class)
                .hasMessageContaining(result.actionId().value());
    }

    @Test
    void validatesSelectionsAndDownloadMetadata() {
        assertThat(Selection.byLabel("France").type()).isEqualTo(SelectionType.LABEL);
        assertThat(Selection.byValue("fr").type()).isEqualTo(SelectionType.VALUE);
        assertThat(Selection.byIndex(2).index()).isEqualTo(2);
        assertThatThrownBy(() -> Selection.byIndex(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new DownloadedFile(
                                        "file.txt",
                                        java.nio.file.Path.of("file.txt"),
                                        -1,
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
