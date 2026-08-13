package io.webagent4j.action.internal;

import io.webagent4j.action.IActionBackend;
import io.webagent4j.dom.IElement;

/** Internal backend-neutral target operation. */
@FunctionalInterface
interface ITargetOperation<R> {
    R execute(IActionBackend backend, IElement target);
}
