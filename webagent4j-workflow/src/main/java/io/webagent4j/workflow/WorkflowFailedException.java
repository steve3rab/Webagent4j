package io.webagent4j.workflow;

/** Optional exception-style projection of a structured, failed {@link WorkflowResult}. */
public final class WorkflowFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient WorkflowResult result;

    /** Creates a safe exception without embedding raw secret values or exception text. */
    public WorkflowFailedException(WorkflowResult result) {
        super(
                "Workflow "
                        + result.workflowId().value()
                        + " failed with status "
                        + result.status()
                        + result.failure().map(f -> ": " + f.safeMessage()).orElse(""));
        this.result = result;
    }

    /** Returns the structured result that caused this exception. */
    public WorkflowResult result() {
        return result;
    }
}
