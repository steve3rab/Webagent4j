package io.webagent4j.action.internal;

import io.webagent4j.action.ActionOptions;
import io.webagent4j.action.ActionPlan;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.IActionContext;
import io.webagent4j.action.IPreparedAction;
import io.webagent4j.action.ObservationCapturePolicy;
import io.webagent4j.common.RetryPolicy;
import io.webagent4j.dom.IElement;
import io.webagent4j.verification.ITargetVerification;
import io.webagent4j.verification.IVerification;
import io.webagent4j.verification.VerificationResult;
import io.webagent4j.verification.VerificationType;
import io.webagent4j.verification.Verifications;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Single-use fluent configuration backed by one immutable action command. */
final class DefaultPreparedAction<R> implements IPreparedAction<R> {

    private final IActionContext context;
    private final ActionCommand<R> command;
    private final List<IVerification> preconditions = new ArrayList<>();
    private final List<IVerification> postconditions = new ArrayList<>();
    private ActionOptions options = ActionOptions.defaults();
    private boolean sensitive;
    private boolean dryRun;

    DefaultPreparedAction(IActionContext context, ActionCommand<R> command) {
        this.context = Objects.requireNonNull(context, "context");
        this.command = Objects.requireNonNull(command, "command");
        if (command.target() != null && command.type() == io.webagent4j.action.ActionType.CHECK) {
            postconditions.add(Verifications.elementChecked(command.target()));
        }
        if (command.target() != null && command.type() == io.webagent4j.action.ActionType.UNCHECK) {
            postconditions.add(Verifications.elementUnchecked(command.target()));
        }
    }

    DefaultPreparedAction<R> sensitive() {
        sensitive = true;
        return this;
    }

    @Override
    public IPreparedAction<R> dryRun() {
        this.dryRun = true;
        return this;
    }

    @Override
    public IPreparedAction<R> require(IVerification verification) {
        preconditions.add(bind(verification));
        return this;
    }

    @Override
    public IPreparedAction<R> precondition(Predicate<IElement> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        if (command.target() == null) {
            throw new IllegalStateException("This action does not have an element target");
        }
        preconditions.add(
                new IVerification() {
                    @Override
                    public VerificationType type() {
                        return VerificationType.CUSTOM;
                    }

                    @Override
                    public VerificationResult verify(
                            io.webagent4j.verification.IVerificationContext verificationContext) {
                        boolean success = predicate.test(command.target().resolve());
                        return new VerificationResult(
                                success,
                                type(),
                                "Custom target precondition",
                                "true",
                                Boolean.toString(success),
                                Duration.ZERO,
                                false);
                    }
                });
        return this;
    }

    @Override
    public IPreparedAction<R> expect(IVerification verification) {
        postconditions.add(bind(verification));
        return this;
    }

    @Override
    public IPreparedAction<R> expectUrlContains(String expectedFragment) {
        return expect(Verifications.urlContains(expectedFragment));
    }

    @Override
    public IPreparedAction<R> timeout(Duration timeout) {
        options =
                new ActionOptions(
                        timeout,
                        options.verificationInterval(),
                        options.resolutionRetry(),
                        options.observationCapture());
        return this;
    }

    @Override
    public IPreparedAction<R> retry(RetryPolicy retryPolicy) {
        options =
                new ActionOptions(
                        options.timeout(),
                        options.verificationInterval(),
                        retryPolicy,
                        options.observationCapture());
        return this;
    }

    @Override
    public IPreparedAction<R> captureObservations(ObservationCapturePolicy policy) {
        options =
                new ActionOptions(
                        options.timeout(),
                        options.verificationInterval(),
                        options.resolutionRetry(),
                        policy);
        return this;
    }

    @Override
    public ActionResult<R> execute() {
        return new ActionExecutor().execute(context, command, buildConfig());
    }

    @Override
    public ActionPlan<R> plan() {
        ActionExecutionConfig config = buildConfig();
        return new ActionExecutor()
                .prepare(
                        context,
                        command,
                        config,
                        () -> new ActionExecutor().execute(context, command, config));
    }

    private ActionExecutionConfig buildConfig() {
        return new ActionExecutionConfig(
                options,
                List.copyOf(preconditions),
                List.copyOf(postconditions),
                (current, remaining) -> io.webagent4j.action.StabilizationResult.none(),
                sensitive,
                dryRun);
    }

    private IVerification bind(IVerification verification) {
        Objects.requireNonNull(verification, "verification");
        if (verification instanceof ITargetVerification targetVerification) {
            if (command.target() == null) {
                throw new IllegalStateException("Target verification requires an element action");
            }
            return targetVerification.bind(command.target());
        }
        return verification;
    }
}
