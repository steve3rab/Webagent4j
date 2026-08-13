package io.webagent4j.action;

/** Explicit command that performs one browser operation. */
public interface IAction<R> {

    /** Returns the stable command type. */
    ActionType type();

    /** Returns conservative execution idempotency used by retry safety. */
    ActionIdempotency idempotency();

    /** Returns the broad side-effect classification for policy integrations. */
    ActionSideEffect sideEffect();

    /** Executes the command against a backend-neutral context. */
    ActionResult<R> execute(IActionContext context);
}
