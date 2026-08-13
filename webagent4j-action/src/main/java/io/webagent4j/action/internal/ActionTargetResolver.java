package io.webagent4j.action.internal;

import io.webagent4j.common.RetryPolicy;
import io.webagent4j.dom.IElement;
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
                latest = new IllegalStateException("Resolved target is detached");
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
                latest, () -> new IllegalStateException("Target could not be resolved"));
    }

    private static void pause(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ActionInterruptedException(exception);
        }
    }
}
