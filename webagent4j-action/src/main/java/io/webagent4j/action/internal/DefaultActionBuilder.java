package io.webagent4j.action.internal;

import io.webagent4j.action.ActionEvent;
import io.webagent4j.action.ActionFailure;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.IActionBuilder;
import io.webagent4j.action.IActionContext;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ElementReference;
import io.webagent4j.locator.api.IElementReference;
import io.webagent4j.verification.IVerification;
import io.webagent4j.verification.UrlContainsVerification;
import io.webagent4j.verification.VerificationResult;
import io.webagent4j.verification.Verifier;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Internal default action-plan implementation; public only for cross-module backend composition.
 */
public final class DefaultActionBuilder implements IActionBuilder {

    private final IActionContext context;
    private final List<IVerification> postconditions = new ArrayList<>();
    private IElementReference<IElement> clickTarget;

    /** Creates a single-use builder bound to the supplied page context. */
    public DefaultActionBuilder(IActionContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public IActionBuilder click(IElement element) {
        IElement target = Objects.requireNonNull(element, "element");
        this.clickTarget = () -> target;
        return this;
    }

    @Override
    public IActionBuilder click(IElementReference<IElement> reference) {
        this.clickTarget = Objects.requireNonNull(reference, "reference");
        return this;
    }

    @Override
    public IActionBuilder click(ElementReference reference) {
        this.clickTarget = Objects.requireNonNull(reference, "reference").bind(context);
        return this;
    }

    @Override
    public IActionBuilder expectUrlContains(String expectedFragment) {
        postconditions.add(new UrlContainsVerification(expectedFragment));
        return this;
    }

    @Override
    public ActionResult<Void> execute() {
        if (clickTarget == null) {
            throw new IllegalStateException("an action must be selected before execution");
        }
        Instant started = Instant.now();
        IElement resolvedTarget = clickTarget.resolve();
        String target = resolvedTarget.role() + " '" + resolvedTarget.accessibleName() + "'";
        List<ActionEvent> events = new ArrayList<>();
        events.add(event(started, target, "started", Duration.ZERO));
        try {
            resolvedTarget.click();
            List<VerificationResult> verificationResults =
                    new Verifier().verifyAll(context, postconditions);
            Optional<VerificationResult> mismatch =
                    verificationResults.stream().filter(result -> !result.success()).findFirst();
            Duration duration = Duration.between(started, Instant.now());
            if (mismatch.isPresent()) {
                VerificationResult result = mismatch.orElseThrow();
                events.add(event(Instant.now(), target, "postcondition-failed", duration));
                return failed(
                        duration,
                        events,
                        ActionFailureType.POSTCONDITION,
                        result.description() + "; actual: " + result.actual(),
                        Optional.empty());
            }
            events.add(event(Instant.now(), target, "completed", duration));
            return new ActionResult<>(true, null, duration, events, Optional.empty());
        } catch (RuntimeException exception) {
            Duration duration = Duration.between(started, Instant.now());
            events.add(event(Instant.now(), target, "failed", duration));
            return failed(
                    duration,
                    events,
                    ActionFailureType.EXECUTION,
                    "Click execution failed",
                    Optional.of(exception));
        }
    }

    private static ActionResult<Void> failed(
            Duration duration,
            List<ActionEvent> events,
            ActionFailureType type,
            String message,
            Optional<Throwable> cause) {
        return new ActionResult<>(
                false,
                null,
                duration,
                events,
                Optional.of(new ActionFailure(type, message, cause)));
    }

    private static ActionEvent event(
            Instant timestamp, String target, String result, Duration duration) {
        return new ActionEvent(timestamp, "click", target, result, duration, Map.of());
    }
}
