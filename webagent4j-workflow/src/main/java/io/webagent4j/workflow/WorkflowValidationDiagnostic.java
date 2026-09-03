package io.webagent4j.workflow;

import java.util.Objects;
import java.util.Optional;

/**
 * One safe, structured explanation of why a {@link Workflow} definition violates a structural
 * invariant - see {@link Workflow.Builder#validate()} and {@code
 * docs/workflow.md#validation-report}.
 *
 * @param code the stable category of this diagnostic
 * @param severity this diagnostic's severity - {@link WorkflowValidationSeverity#ERROR} in this
 *     version
 * @param stepId the step this diagnostic concerns, if the invariant is step-specific
 * @param variableName the variable name this diagnostic concerns, if the invariant is
 *     variable-specific
 * @param message a safe, human-readable explanation - never a raw value, secret, or {@link
 *     Throwable}
 */
public record WorkflowValidationDiagnostic(
        WorkflowValidationCode code,
        WorkflowValidationSeverity severity,
        Optional<WorkflowStepId> stepId,
        Optional<String> variableName,
        String message) {

    /** Validates diagnostic data. */
    public WorkflowValidationDiagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(severity, "severity");
        stepId = Objects.requireNonNull(stepId, "stepId");
        variableName = Objects.requireNonNull(variableName, "variableName");
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message cannot be blank");
        }
    }
}
