package io.webagent4j.action.internal;

import io.webagent4j.common.LocatorFailureClassifier;
import io.webagent4j.common.RetryPolicy;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.LocatorNotFoundException;
import io.webagent4j.wait.IWaitSleeper;
import io.webagent4j.wait.WaitBudget;
import io.webagent4j.wait.WaitInterruptedException;
import java.time.Duration;
import java.util.Objects;

/**
 * Re-resolves semantic targets with bounded retries before backend execution, consuming a shared
 * {@link WaitBudget} rather than its own independent timeout.
 *
 * <p>Only a demonstrated, typed "not found" outcome is retryable - a resolved-but-detached element
 * is treated the same way, since a node that just left the document is exactly the kind of
 * transient condition a retry exists for. {@link io.webagent4j.locator.AmbiguousLocatorException
 * Ambiguity} and any other failure (a genuine backend/runtime error, in particular) are never
 * retried: they end resolution on the first attempt, exactly as a probe throwing out of {@code
 * webagent4j-wait}'s {@code WaitEngine} would.
 */
final class ActionTargetResolver {

    IElement resolve(ActionCommand<?> command, RetryPolicy policy, WaitBudget budget) {
        if (command.target() == null) {
            return null;
        }
        RuntimeException latest = null;
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            // Every call always makes its first attempt immediately, even against an already
            // expired budget - only a later attempt is gated on the budget still having time left.
            if (attempt > 1 && budget.expired()) {
                break;
            }
            try {
                IElement element = command.target().resolve();
                if (element.state().present()) {
                    return element;
                }
                latest = new LocatorNotFoundException("Resolved target is detached");
            } catch (RuntimeException exception) {
                if (!LocatorFailureClassifier.isNotFound(exception)) {
                    throw exception;
                }
                latest = exception;
            }
            if (attempt < policy.maxAttempts() && !budget.expired()) {
                Duration delay = policy.delayBeforeAttempt(attempt + 1);
                pause(shorter(delay, budget.remaining()));
            }
        }
        throw Objects.requireNonNullElseGet(
                latest, () -> new LocatorNotFoundException("Target could not be resolved"));
    }

    private static Duration shorter(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private static void pause(Duration delay) {
        try {
            IWaitSleeper.parking().sleep(delay);
        } catch (WaitInterruptedException interrupted) {
            throw new ActionInterruptedException(interrupted);
        }
    }
}
