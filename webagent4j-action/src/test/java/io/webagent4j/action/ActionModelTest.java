package io.webagent4j.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.verification.VerificationResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
    void reportsDryRunExecutionExplicitly() {
        ActionResult<Void> result =
                new ActionResult<>(
                        ActionId.create(),
                        ActionType.CLICK,
                        ActionExecutionMode.DRY_RUN,
                        ActionStatus.SUCCESS,
                        null,
                        Duration.ofMillis(184),
                        ActionTimings.empty(Duration.ofMillis(184)),
                        List.of(new VerificationResult(true, "ready", "ready")),
                        List.of(new VerificationResult(true, "updated", "updated")),
                        null,
                        null,
                        null,
                        List.of(),
                        Optional.empty(),
                        new ActionDiagnostics(
                                "BUTTON \"Commander\"", "", Map.of("execution", "dry-run")));

        assertThat(result.dryRun()).isTrue();
        assertThat(result.executed()).isFalse();
        assertThat(result.success()).isTrue();
    }

    @Test
    void rendersCompactActionSummaryForSuccess() {
        ActionResult<Void> result =
                new ActionResult<>(
                        ActionId.create(),
                        ActionType.CLICK,
                        ActionExecutionMode.REAL,
                        ActionStatus.SUCCESS,
                        null,
                        Duration.ofMillis(184),
                        new ActionTimings(
                                Duration.ofMillis(184),
                                Duration.ofMillis(10),
                                Duration.ofMillis(20),
                                Duration.ofMillis(30),
                                Duration.ofMillis(40),
                                Duration.ofMillis(84)),
                        List.of(new VerificationResult(true, "ready", "ready")),
                        List.of(new VerificationResult(true, "updated", "updated")),
                        null,
                        null,
                        null,
                        List.of(),
                        Optional.empty(),
                        new ActionDiagnostics("BUTTON \"Commander\"", "", Map.of()));

        String compact = result.toCompactText();

        assertThat(compact)
                .contains("CLICK BUTTON \"Commander\"")
                .contains("status=SUCCESS")
                .contains("preconditions=1/1")
                .contains("postconditions=1/1")
                .contains("duration=184ms");
    }

    @Test
    void rendersCompactActionSummaryForFailureWithoutSensitiveFields() {
        ActionFailure failure =
                new ActionFailure(
                        ActionFailureType.TARGET_NOT_INTERACTABLE,
                        "token=super-secret-value",
                        Optional.empty());
        ActionResult<Void> result =
                new ActionResult<>(
                        ActionId.create(),
                        ActionType.CLICK,
                        ActionExecutionMode.NOT_EXECUTED,
                        ActionStatus.PRECONDITION_FAILED,
                        null,
                        Duration.ofMillis(73),
                        ActionTimings.empty(Duration.ofMillis(73)),
                        List.of(),
                        List.of(),
                        null,
                        null,
                        null,
                        List.of(),
                        Optional.of(failure),
                        new ActionDiagnostics("BUTTON \"Commander\"", "", Map.of()));

        String compact = result.toCompactText();

        assertThat(compact)
                .contains("CLICK BUTTON \"Commander\"")
                .contains("status=PRECONDITION_FAILED")
                .contains("failure=TARGET_NOT_INTERACTABLE")
                .contains("duration=73ms");
        assertThat(compact).doesNotContain("token").doesNotContain("super-secret-value");
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
