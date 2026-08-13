package io.webagent4j.locator.internal;

import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.IInteractabilityChecker;
import io.webagent4j.locator.InteractabilityFailureReason;
import io.webagent4j.locator.InteractabilityResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Default state-only interactability checker. */
public final class DefaultInteractabilityChecker implements IInteractabilityChecker {

    @Override
    public InteractabilityResult check(IElement element) {
        ElementState state = Objects.requireNonNull(element, "element").state();
        List<InteractabilityFailureReason> reasons = new ArrayList<>();
        if (!state.present()) {
            reasons.add(InteractabilityFailureReason.DETACHED);
        }
        if (!state.visible()) {
            reasons.add(InteractabilityFailureReason.NOT_VISIBLE);
        }
        if (!state.enabled()) {
            reasons.add(InteractabilityFailureReason.DISABLED);
        }
        if (!state.inViewport()) {
            reasons.add(InteractabilityFailureReason.OUTSIDE_VIEWPORT);
        }
        if (state.covered()) {
            reasons.add(InteractabilityFailureReason.COVERED);
        }
        if (state.readOnly()) {
            reasons.add(InteractabilityFailureReason.READ_ONLY);
        }
        if (!state.interactabilityKnown()) {
            reasons.add(InteractabilityFailureReason.UNKNOWN);
        }
        if (reasons.isEmpty() && state.clickable()) {
            return InteractabilityResult.interactive();
        }
        if (reasons.isEmpty()) {
            reasons.add(InteractabilityFailureReason.UNKNOWN);
        }
        return InteractabilityResult.failed(reasons);
    }
}
