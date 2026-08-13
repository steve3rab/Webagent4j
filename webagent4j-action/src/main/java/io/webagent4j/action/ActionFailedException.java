package io.webagent4j.action;

/** Optional exception-style projection of a structured unsuccessful action result. */
public final class ActionFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ActionResult<?> result;

    /** Creates a safe exception without embedding backend exception text or secret values. */
    public ActionFailedException(ActionResult<?> result) {
        super("Action " + result.actionId().value() + " failed with status " + result.status());
        this.result = result;
    }

    /** Returns the structured result that caused this exception. */
    public ActionResult<?> result() {
        return result;
    }
}
