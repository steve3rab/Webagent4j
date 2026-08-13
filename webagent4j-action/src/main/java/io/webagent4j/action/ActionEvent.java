package io.webagent4j.action;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Immutable, safely renderable audit event emitted during one action execution. */
public record ActionEvent(
        ActionId actionId,
        Instant timestamp,
        ActionStage stage,
        ActionType actionType,
        String target,
        String result,
        Duration duration,
        Map<String, String> metadata) {

    /** Validates and defensively stores event data. */
    public ActionEvent {
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(actionType, "actionType");
        target = Objects.requireNonNull(target, "target");
        result = Objects.requireNonNull(result, "result");
        Objects.requireNonNull(duration, "duration");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
    }

    /** Compatibility constructor for legacy click audit events. */
    public ActionEvent(
            Instant timestamp,
            String action,
            String target,
            String result,
            Duration duration,
            Map<String, String> metadata) {
        this(
                ActionId.create(),
                timestamp,
                ActionStage.ACTION_COMPLETED,
                "click".equalsIgnoreCase(action) ? ActionType.CLICK : ActionType.WAIT,
                target,
                result,
                duration,
                metadata);
    }

    /** Returns the lowercase action name retained for source compatibility. */
    public String action() {
        return actionType.name().toLowerCase(java.util.Locale.ROOT);
    }
}
