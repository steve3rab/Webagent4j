package io.webagent4j.action.internal;

import io.webagent4j.action.ActionIdempotency;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.ActionSideEffect;
import io.webagent4j.action.ActionType;
import io.webagent4j.action.IAction;
import io.webagent4j.action.IActionContext;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.IElementReference;

/** Immutable internal command description executed by the shared pipeline. */
record ActionCommand<R>(
        ActionType type,
        ActionIdempotency idempotency,
        ActionSideEffect sideEffect,
        IElementReference<IElement> target,
        ITargetOperation<R> targetOperation,
        IPageOperation<R> pageOperation)
        implements IAction<R> {

    @Override
    public ActionResult<R> execute(IActionContext context) {
        return new ActionExecutor().execute(context, this, ActionExecutionConfig.defaults());
    }

    R executeBackend(io.webagent4j.action.IActionBackend backend, IElement resolvedTarget) {
        return targetOperation == null
                ? pageOperation.execute(backend)
                : targetOperation.execute(backend, resolvedTarget);
    }
}
