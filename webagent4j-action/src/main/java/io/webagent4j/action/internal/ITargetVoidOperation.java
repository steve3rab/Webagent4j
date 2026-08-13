package io.webagent4j.action.internal;

import io.webagent4j.action.IActionBackend;
import io.webagent4j.dom.IElement;

/** Internal void target operation. */
@FunctionalInterface
interface ITargetVoidOperation {
    void execute(IActionBackend backend, IElement target);
}
