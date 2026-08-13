package io.webagent4j.action.internal;

import io.webagent4j.action.IActionBackend;

/** Internal backend-neutral page operation. */
@FunctionalInterface
interface IPageOperation<R> {
    R execute(IActionBackend backend);
}
