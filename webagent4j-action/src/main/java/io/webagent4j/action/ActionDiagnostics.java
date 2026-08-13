package io.webagent4j.action;

import java.util.Map;
import java.util.Objects;

/** Immutable non-secret diagnostics collected lazily for an action. */
public record ActionDiagnostics(
        String targetDescription, String locatorDiagnostics, Map<String, String> details) {

    /** Defensively copies diagnostic values. */
    public ActionDiagnostics {
        targetDescription = Objects.requireNonNull(targetDescription, "targetDescription");
        locatorDiagnostics = Objects.requireNonNull(locatorDiagnostics, "locatorDiagnostics");
        details = Map.copyOf(Objects.requireNonNull(details, "details"));
    }

    /** Returns empty diagnostics for compatibility and low-cost successful actions. */
    public static ActionDiagnostics empty() {
        return new ActionDiagnostics("", "", Map.of());
    }
}
