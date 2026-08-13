package io.webagent4j.verification;

import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.IElementReference;

/** Verification that may be bound to the current action target. */
public interface ITargetVerification extends IVerification {

    /** Returns an immutable verification bound to a re-resolvable action target. */
    IVerification bind(IElementReference<IElement> target);
}
