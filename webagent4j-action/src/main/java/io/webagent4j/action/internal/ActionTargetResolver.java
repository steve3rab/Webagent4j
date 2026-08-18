package io.webagent4j.action.internal;

import io.webagent4j.common.RetryPolicy;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.LocatorNotFoundException;
import io.webagent4j.wait.IWaitSleeper;
import io.webagent4j.wait.WaitInterruptedException;
import java.time.Duration;
import java.util.Objects;

/** Re-resolves semantic targets with bounded retries before backend execution. */
final class ActionTargetResolver {

    IElement resolve(ActionCommand<?> command, RetryPolicy policy, Duration budget) {
        if (command.target() == null) {
            return null;
        }
        RuntimeException latest = null;
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            try {
                IElement element = command.target().resolve();
                if (element.state().present()) {
                    return element;
                }
                latest = new LocatorNotFoundException("Resolved target is detached");
            } catch (RuntimeException exception) {
                latest = exception;
            }
            if (attempt < policy.maxAttempts()) {
                Duration delay = policy.delayBeforeAttempt(attempt + 1);
                if (delay.compareTo(budget) >= 0) {
                    break;
                }
                pause(delay);
            }
        }
        throw Objects.requireNonNullElseGet(
                latest, () -> new LocatorNotFoundException("Target could not be resolved"));
    }

    private static void pause(Duration delay) {
        try {
            IWaitSleeper.parking().sleep(delay);
        } catch (WaitInterruptedException interrupted) {
            throw new ActionInterruptedException(interrupted);
        }
    }
}
