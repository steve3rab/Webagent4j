package io.webagent4j.workflow;

/**
 * Severity of one {@link WorkflowValidationDiagnostic}.
 *
 * <p>Every diagnostic {@link Workflow.Builder#validate()} can currently produce corresponds to a
 * structural invariant {@link Workflow.Builder#build()} already enforces as fail-closed, so every
 * diagnostic is {@link #ERROR} in this version - there is no informational or stylistic diagnostic
 * category (see {@code docs/workflow.md#validation-report}).
 */
public enum WorkflowValidationSeverity {
    /** The definition violates a structural invariant {@link Workflow.Builder#build()} enforces. */
    ERROR
}
