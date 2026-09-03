package io.webagent4j.workflow;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, deterministic, backend-neutral explanation of a {@link Workflow} definition's static
 * properties, produced without ever executing anything - see {@link Workflow.Builder#validate()}
 * and {@code docs/workflow.md#validation-report}.
 *
 * <p>This is a structural sibling to {@link WorkflowExecutionPlan}, not the same concept: the
 * report explains <em>whether and why a definition is valid</em>; the plan explains <em>what a
 * valid definition can structurally execute</em>. Neither depends on an actual execution existing,
 * unlike {@link WorkflowExecutionTree}, which exists only after one.
 *
 * <p>{@link Workflow.Builder#validate()} and {@link Workflow.Builder#build()} derive their
 * conclusions from the exact same internal analysis - never two independently maintained rule sets
 * that could diverge. {@code build()} remains fail-closed: it rejects an invalid definition exactly
 * as before, whether or not a caller ever calls {@code validate()} first, and this report never
 * makes an invalid definition executable.
 *
 * @param workflowId the validated definition's identifier
 * @param diagnostics every diagnostic found, in definition-traversal order - empty if {@link
 *     #valid()}
 * @param diagnosticsTruncated whether more diagnostics existed than this report's bounded capacity
 *     could hold - see {@code docs/workflow.md#validation-report}
 * @param requiredInputs every declared required input, in declaration order - name, type, and
 *     secret classification only, never a value
 * @param optionalInputs every declared optional input, in declaration order
 * @param outputs every structurally valid declared output found, in definition-traversal order
 * @param stepCount the number of steps successfully analyzed (a step whose own ID collided with an
 *     earlier one is not counted, since its contents were never analyzed)
 * @param conditionalCount the number of {@link WorkflowStepType#CONDITIONAL} steps analyzed
 * @param maximumObservedConditionalDepth the deepest conditional nesting level actually reached
 *     during analysis - may exceed {@link Workflow#MAX_CONDITIONAL_NESTING_DEPTH} by exactly one
 *     for an invalid, over-deep definition, never more, since analysis never recurses past the step
 *     that first exceeds the limit
 */
public record WorkflowValidationReport(
        WorkflowId workflowId,
        List<WorkflowValidationDiagnostic> diagnostics,
        boolean diagnosticsTruncated,
        List<WorkflowVariable<?>> requiredInputs,
        List<WorkflowVariable<?>> optionalInputs,
        List<WorkflowValidationOutput> outputs,
        int stepCount,
        int conditionalCount,
        int maximumObservedConditionalDepth) {

    /** Validates and defensively copies report data. */
    public WorkflowValidationReport {
        Objects.requireNonNull(workflowId, "workflowId");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        requiredInputs = List.copyOf(Objects.requireNonNull(requiredInputs, "requiredInputs"));
        optionalInputs = List.copyOf(Objects.requireNonNull(optionalInputs, "optionalInputs"));
        outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
        if (stepCount < 0 || conditionalCount < 0 || maximumObservedConditionalDepth < 0) {
            throw new IllegalArgumentException("counts cannot be negative");
        }
    }

    /** Returns whether the definition is valid: {@link Workflow.Builder#build()} would succeed. */
    public boolean valid() {
        return diagnostics.isEmpty();
    }
}
