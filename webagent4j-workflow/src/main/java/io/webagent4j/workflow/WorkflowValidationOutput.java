package io.webagent4j.workflow;

import java.util.Objects;

/**
 * One structurally valid declared output found while validating a {@link Workflow} definition - see
 * {@link Workflow.Builder#validate()} and {@code docs/workflow.md#validation-report}.
 *
 * <p>When the same, compatible output is declared by producers in both branches of a conditional
 * (an {@code ifElse} where both {@code thenSteps} and {@code elseSteps} declare it identically),
 * {@link #producerStepId()} names whichever branch's producer step was validated last - {@code
 * THEN}, then {@code ELSE} - not a definitional ambiguity, just a reflection of validation order.
 *
 * @param producerStepId the step that declares this output
 * @param variable the output's name, runtime type, and secret classification - never a value
 * @param definitelyAvailable whether this output is guaranteed to have been published by the time
 *     execution reaches whatever structurally follows its producer - see {@code
 *     docs/workflow.md#branching} for guard-aware definite assignment
 */
public record WorkflowValidationOutput(
        WorkflowStepId producerStepId, WorkflowVariable<?> variable, boolean definitelyAvailable) {

    /** Validates output metadata. */
    public WorkflowValidationOutput {
        Objects.requireNonNull(producerStepId, "producerStepId");
        Objects.requireNonNull(variable, "variable");
    }
}
