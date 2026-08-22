package io.webagent4j.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.verification.VerificationResult;
import java.time.Duration;
import java.time.Instant;
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
                        ActionFailureType.PRECONDITION_FAILED,
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
                .contains("failure=PRECONDITION_FAILED")
                .contains("duration=73ms");
        assertThat(compact).doesNotContain("token").doesNotContain("super-secret-value");
    }

    @Test
    void enforcesTheCompleteStatusExecutionModeAndFailureTypeMatrix() {
        for (ActionStatus status : ActionStatus.values()) {
            for (ActionExecutionMode executionMode : ActionExecutionMode.values()) {
                assertOutcomeShape(status, executionMode, null);
                for (ActionFailureType failureType : ActionFailureType.values()) {
                    assertOutcomeShape(status, executionMode, failureType);
                }
            }
        }
    }

    @Test
    void rejectsContradictoryFailureCategories() {
        assertThatThrownBy(
                        () ->
                                actionResult(
                                        ActionStatus.CANCELLED,
                                        ActionExecutionMode.REAL,
                                        failure(ActionFailureType.PRECONDITION_FAILED)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                actionResult(
                                        ActionStatus.PRECONDITION_FAILED,
                                        ActionExecutionMode.NOT_EXECUTED,
                                        failure(ActionFailureType.POSTCONDITION_FAILED)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                actionResult(
                                        ActionStatus.VERIFICATION_FAILED,
                                        ActionExecutionMode.REAL,
                                        failure(ActionFailureType.BACKEND_FAILURE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                actionResult(
                                        ActionStatus.TIMEOUT,
                                        ActionExecutionMode.REAL,
                                        failure(ActionFailureType.INTERRUPTED)))
                .isInstanceOf(IllegalArgumentException.class);
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

    @Test
    void rejectsNegativeDurationsAndKeepsAuditRenderingStructural() {
        String sentinel = "WEBAGENT4J_UNTRUSTED_EVENT_TEXT";
        ActionEvent event =
                new ActionEvent(
                        new ActionId("event"),
                        Instant.EPOCH,
                        ActionStage.ACTION_COMPLETED,
                        ActionType.CLICK,
                        sentinel,
                        sentinel,
                        Duration.ZERO,
                        Map.of("untrusted", sentinel));

        assertThat(event.toString()).doesNotContain(sentinel).contains("ACTION_COMPLETED", "CLICK");
        assertThatThrownBy(
                        () ->
                                new ActionEvent(
                                        new ActionId("event"),
                                        Instant.EPOCH,
                                        ActionStage.ACTION_COMPLETED,
                                        ActionType.CLICK,
                                        "target",
                                        "result",
                                        Duration.ofNanos(-1),
                                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StabilizationResult(false, Duration.ofNanos(-1), "not stable"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new ActionResult<>(
                                        true,
                                        null,
                                        Duration.ofNanos(-1),
                                        List.of(),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsImpossibleStatusAndExecutionModePairs() {
        ActionFailure failure =
                new ActionFailure(
                        ActionFailureType.PRECONDITION_FAILED,
                        "precondition failed",
                        Optional.empty());

        assertThatThrownBy(
                        () ->
                                actionResult(
                                        ActionStatus.SUCCESS,
                                        ActionExecutionMode.NOT_EXECUTED,
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                actionResult(
                                        ActionStatus.PRECONDITION_FAILED,
                                        ActionExecutionMode.REAL,
                                        Optional.of(failure)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                actionResult(
                                        ActionStatus.EXECUTION_FAILED,
                                        ActionExecutionMode.DRY_RUN,
                                        Optional.of(failure)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ActionResult<Void> actionResult(
            ActionStatus status,
            ActionExecutionMode executionMode,
            Optional<ActionFailure> failure) {
        return new ActionResult<>(
                new ActionId("action"),
                ActionType.CLICK,
                executionMode,
                status,
                null,
                Duration.ZERO,
                ActionTimings.empty(Duration.ZERO),
                List.of(),
                List.of(),
                null,
                null,
                null,
                List.of(),
                failure,
                ActionDiagnostics.empty());
    }

    private static void assertOutcomeShape(
            ActionStatus status, ActionExecutionMode executionMode, ActionFailureType failureType) {
        Optional<ActionFailure> failure =
                failureType == null ? Optional.empty() : failure(failureType);
        String description = status + "/" + executionMode + "/" + failureType;
        if (isValidOutcome(status, executionMode, failureType)) {
            assertThatCode(() -> actionResult(status, executionMode, failure))
                    .as(description)
                    .doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> actionResult(status, executionMode, failure))
                    .as(description)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static Optional<ActionFailure> failure(ActionFailureType failureType) {
        return Optional.of(new ActionFailure(failureType, "failed", Optional.empty()));
    }

    private static boolean isValidOutcome(
            ActionStatus status, ActionExecutionMode executionMode, ActionFailureType failureType) {
        return switch (status) {
            case SUCCESS ->
                    failureType == null
                            && (executionMode == ActionExecutionMode.REAL
                                    || executionMode == ActionExecutionMode.DRY_RUN);
            case PRECONDITION_FAILED ->
                    executionMode == ActionExecutionMode.NOT_EXECUTED
                            && failureType == ActionFailureType.PRECONDITION_FAILED;
            case EXECUTION_FAILED ->
                    switch (executionMode) {
                        case NOT_EXECUTED ->
                                failureType == ActionFailureType.TARGET_NOT_FOUND
                                        || failureType == ActionFailureType.TARGET_AMBIGUOUS
                                        || failureType == ActionFailureType.BACKEND_FAILURE;
                        case REAL ->
                                failureType == ActionFailureType.TARGET_NOT_INTERACTABLE
                                        || failureType
                                                == ActionFailureType.ACTION_NOT_SUPPORTED_BY_TARGET
                                        || failureType == ActionFailureType.BACKEND_FAILURE
                                        || failureType == ActionFailureType.UPLOAD_FAILURE
                                        || failureType == ActionFailureType.DOWNLOAD_FAILURE;
                        case DRY_RUN -> false;
                    };
            case VERIFICATION_FAILED ->
                    executionMode == ActionExecutionMode.REAL
                            && failureType == ActionFailureType.POSTCONDITION_FAILED;
            case TIMEOUT ->
                    (executionMode == ActionExecutionMode.REAL
                                    || executionMode == ActionExecutionMode.NOT_EXECUTED)
                            && failureType == ActionFailureType.TIMEOUT;
            case CANCELLED ->
                    (executionMode == ActionExecutionMode.REAL
                                    || executionMode == ActionExecutionMode.NOT_EXECUTED)
                            && failureType == ActionFailureType.INTERRUPTED;
        };
    }
}
