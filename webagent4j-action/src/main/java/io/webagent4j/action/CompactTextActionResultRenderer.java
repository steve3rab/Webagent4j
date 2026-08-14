package io.webagent4j.action;

import java.time.Duration;
import java.util.Objects;

/** Deterministic compact action result renderer for logs and diagnostics. */
public final class CompactTextActionResultRenderer {

    /** Renders the compact action summary without exposing backend objects or sensitive values. */
    public String render(ActionResult<?> result) {
        Objects.requireNonNull(result, "result");

        String target = result.diagnostics().targetDescription();
        if (target == null || target.isBlank()) {
            target = result.actionType().name();
        }

        StringBuilder output = new StringBuilder();
        output.append(result.actionType())
                .append(' ')
                .append(target)
                .append(System.lineSeparator());
        output.append("status=").append(result.status()).append(System.lineSeparator());

        if (result.failure().isPresent()) {
            output.append("failure=")
                    .append(result.failure().get().type())
                    .append(System.lineSeparator());
        }

        output.append("preconditions=")
                .append(countSucceeded(result.preconditions()))
                .append('/')
                .append(result.preconditions().size())
                .append(System.lineSeparator());
        output.append("postconditions=")
                .append(countSucceeded(result.postconditions()))
                .append('/')
                .append(result.postconditions().size())
                .append(System.lineSeparator());
        output.append("duration=")
                .append(formatDuration(result.duration()))
                .append(System.lineSeparator());

        return output.toString().stripTrailing();
    }

    private static int countSucceeded(
            java.util.List<io.webagent4j.verification.VerificationResult> results) {
        return (int)
                results.stream()
                        .filter(io.webagent4j.verification.VerificationResult::success)
                        .count();
    }

    private static String formatDuration(Duration duration) {
        long millis = duration.toMillis();
        if (millis < 1000) {
            return millis + "ms";
        }
        return duration.toString().replace("PT", "").replace("S", "s");
    }
}
