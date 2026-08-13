package io.webagent4j.action;

/** Explicit command that performs one browser operation. */
public interface IAction<R> {

    /** Executes the command against a backend-neutral context. */
    ActionResult<R> execute(IActionContext context);
}
