package io.webagent4j.action.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionIdempotency;
import io.webagent4j.action.ActionOptions;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.ActionSideEffect;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.action.ActionType;
import io.webagent4j.action.IActionBackend;
import io.webagent4j.action.IActionContext;
import io.webagent4j.action.IStabilizationStrategy;
import io.webagent4j.action.ObservationCapturePolicy;
import io.webagent4j.action.StabilizationResult;
import io.webagent4j.action.policy.IActionPolicy;
import io.webagent4j.common.RetryPolicy;
import io.webagent4j.dom.BoundingBox;
import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.policy.PolicyDecision;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Proves the generic governed-execution target-identity capability model: absence of an
 * exact-identity verification capability is never treated as proof that a target is still the
 * authorized one. {@link IElement#verifiedForExecution()}'s default implementation fails closed
 * ({@link Optional#empty()}) rather than delegating to {@link
 * IElement#isStillTheOriginallyResolvedTarget()}'s permissive default - a backend that never
 * overrides {@link IElement#verifiedForExecution()} can still be used for every ungoverned action,
 * but any action that requires exact-target verification consistently fails closed against it
 * rather than silently succeeding on no evidence at all (Invariant G2).
 *
 * <p>This complements {@link ActionTargetIdentityRevalidationTest}, which covers the positive
 * (identity proven) and negative (identity disproven by an overriding backend) cases using Mockito
 * mocks; the tests here specifically exercise the interface's own default method behavior, which a
 * Mockito mock cannot exercise - an unstubbed mock method already returns Mockito's own smart-null
 * default ({@code Optional.empty()}), which happens to coincide with the correct answer regardless
 * of what {@code IElement}'s actual default implementation says. A real, non-mock {@link IElement}
 * implementation that simply never overrides {@link IElement#verifiedForExecution()} is the only
 * way to prove the interface's own default is fail-closed.
 */
class GovernedTargetVerificationCapabilityTest {

    @Test
    void
            aBackendWithNoExactIdentityCapabilityAtAllFailsClosedRatherThanInheritingPermissiveTrust() {
        // GT-003: this element never overrides verifiedForExecution() (or
        // isStillTheOriginallyResolvedTarget()) at all - it relies entirely on IElement's own
        // default methods. Before the fix, the default verifiedForExecution() delegated to
        // isStillTheOriginallyResolvedTarget()'s permissive true default, so this exact scenario
        // silently succeeded; a real IElement default (not a Mockito mock) is required to prove
        // the interface's own default, not a test double's stub, is what fails closed here.
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = new MinimalElementWithNoIdentityCapability();

        ActionResult<Void> result =
                executeGoverned(backend, target, ctx -> PolicyDecision.allow("test.allowed"));

        assertThat(result.success()).isFalse();
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.NOT_EXECUTED);
        assertThat(result.status()).isEqualTo(ActionStatus.EXECUTION_FAILED);
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(ActionFailureType.TARGET_CHANGED);
        verify(backend, never()).click(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void verificationThrowingARuntimeExceptionFailsClosedWithAStructuredResultRatherThanCrashing() {
        // GT-004: verifiedForExecution() is a backend extension point. A backend that throws while
        // verifying must never escape the pipeline as a raw, unstructured exception, and the raw
        // message must never appear in the safe result surface - only the in-process cause.
        IActionBackend backend = mock(IActionBackend.class);
        RuntimeException secretBearingFailure =
                new RuntimeException("verification backend unavailable: password=SECRET42");
        IElement target =
                new ConfigurableVerificationElement(
                        () -> {
                            throw secretBearingFailure;
                        });

        ActionResult<Void> result =
                executeGoverned(backend, target, ctx -> PolicyDecision.allow("test.allowed"));

        assertThat(result.success()).isFalse();
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.NOT_EXECUTED);
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(ActionFailureType.TARGET_CHANGED);
        verify(backend, never()).click(org.mockito.ArgumentMatchers.any());
        assertThat(result.toCompactText()).doesNotContain("SECRET42");
        assertThat(result.diagnostics().toString()).doesNotContain("SECRET42");
        // The raw cause remains available in-process for a caller who explicitly wants it.
        assertThat(result.failure().orElseThrow().cause().orElseThrow())
                .isSameAs(secretBearingFailure);
    }

    @Test
    void verificationReturningNullInsteadOfOptionalFailsClosedRatherThanThrowingOrSucceeding() {
        // GT-005: a malformed implementation returning null (violating the interface's Optional
        // contract) must still fail closed, not NPE out of the pipeline and not be treated as
        // present.
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = new ConfigurableVerificationElement(() -> null);

        ActionResult<Void> result =
                executeGoverned(backend, target, ctx -> PolicyDecision.allow("test.allowed"));

        assertThat(result.success()).isFalse();
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.NOT_EXECUTED);
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(ActionFailureType.TARGET_CHANGED);
        verify(backend, never()).click(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aBackendWithRealVerificationCapabilityStillExecutesExactlyOnceOnProvenIdentity() {
        // Positive control mirrored from GT-001, using the same real (non-mock) element type as
        // the negative cases above, so the fail-closed tests above are not merely testing an
        // element that is broken in every other way too.
        IActionBackend backend = mock(IActionBackend.class);
        ConfigurableVerificationElement target =
                new ConfigurableVerificationElement(Optional::empty);
        target.setVerifiedForExecutionSupplier(() -> Optional.of(target));

        ActionResult<Void> result =
                executeGoverned(backend, target, ctx -> PolicyDecision.allow("test.allowed"));

        assertThat(result.success()).isTrue();
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.REAL);
        verify(backend, times(1)).click(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void dryRunNeverConsultsExactTargetVerificationAtAll() {
        // GT-007 supplement: dry-run short-circuits before the TOCTOU revalidation block, so
        // verifiedForExecution() must never even be called, regardless of what it would answer.
        IActionBackend backend = mock(IActionBackend.class);
        boolean[] verificationCalled = {false};
        IElement target =
                new ConfigurableVerificationElement(
                        () -> {
                            verificationCalled[0] = true;
                            return Optional.empty();
                        });

        ActionResult<Void> result =
                executeGoverned(backend, target, ctx -> PolicyDecision.allow("test.allowed"), true);

        assertThat(result.dryRun()).isTrue();
        assertThat(verificationCalled[0]).isFalse();
        verify(backend, never()).click(org.mockito.ArgumentMatchers.any());
    }

    private static ActionResult<Void> executeGoverned(
            IActionBackend backend, IElement target, IActionPolicy policy) {
        return executeGoverned(backend, target, policy, false);
    }

    private static ActionResult<Void> executeGoverned(
            IActionBackend backend, IElement target, IActionPolicy policy, boolean dryRun) {
        ActionCommand<Void> command =
                new ActionCommand<>(
                        ActionType.CLICK,
                        ActionIdempotency.NON_IDEMPOTENT,
                        ActionSideEffect.LOCAL_PAGE_STATE,
                        () -> target,
                        (actionBackend, resolvedTarget) -> {
                            actionBackend.click(resolvedTarget);
                            return null;
                        },
                        null,
                        Optional.empty());
        IStabilizationStrategy alwaysStable =
                (context, remaining) -> new StabilizationResult(true, Duration.ZERO, "settled");
        ActionExecutionConfig config =
                new ActionExecutionConfig(
                        new ActionOptions(
                                Duration.ofSeconds(5),
                                Duration.ofMillis(10),
                                RetryPolicy.defaults(),
                                ObservationCapturePolicy.NONE),
                        List.of(),
                        List.of(),
                        alwaysStable,
                        false,
                        dryRun,
                        Optional.of(policy),
                        Optional.empty());

        return new ActionExecutor().execute(context(backend), command, config);
    }

    private static IActionContext context(IActionBackend backend) {
        return new IActionContext() {
            @Override
            public String url() {
                return "https://example.test";
            }

            @Override
            public String title() {
                return "Example";
            }

            @Override
            public IActionBackend actionBackend() {
                return backend;
            }
        };
    }

    /**
     * A real, non-mock {@link IElement} that never overrides {@link
     * IElement#verifiedForExecution()} or {@link IElement#isStillTheOriginallyResolvedTarget()} -
     * relying entirely on the interface's own default methods, exactly like a backend that never
     * implemented exact-identity tracking.
     */
    private static final class MinimalElementWithNoIdentityCapability implements IElement {
        @Override
        public ElementRole role() {
            return ElementRole.BUTTON;
        }

        @Override
        public String accessibleName() {
            return "Target";
        }

        @Override
        public String text() {
            return "";
        }

        @Override
        public String tagName() {
            return "button";
        }

        @Override
        public Map<String, String> attributes() {
            return Map.of();
        }

        @Override
        public boolean visible() {
            return true;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public ElementState state() {
            return new ElementState(
                    true, true, true, false, false, false, false, false, true, true, false, true);
        }

        @Override
        public Optional<BoundingBox> boundingBox() {
            return Optional.empty();
        }

        @Override
        public void click() {}
    }

    /**
     * A real, non-mock {@link IElement} whose {@code verifiedForExecution()} is fully injectable.
     */
    private static final class ConfigurableVerificationElement implements IElement {
        private Supplier<Optional<IElement>> verifiedForExecutionSupplier;

        ConfigurableVerificationElement(Supplier<Optional<IElement>> verifiedForExecutionSupplier) {
            this.verifiedForExecutionSupplier = verifiedForExecutionSupplier;
        }

        void setVerifiedForExecutionSupplier(Supplier<Optional<IElement>> supplier) {
            this.verifiedForExecutionSupplier = supplier;
        }

        @Override
        public Optional<IElement> verifiedForExecution() {
            return verifiedForExecutionSupplier.get();
        }

        @Override
        public ElementRole role() {
            return ElementRole.BUTTON;
        }

        @Override
        public String accessibleName() {
            return "Target";
        }

        @Override
        public String text() {
            return "";
        }

        @Override
        public String tagName() {
            return "button";
        }

        @Override
        public Map<String, String> attributes() {
            return Map.of();
        }

        @Override
        public boolean visible() {
            return true;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public ElementState state() {
            return new ElementState(
                    true, true, true, false, false, false, false, false, true, true, false, true);
        }

        @Override
        public Optional<BoundingBox> boundingBox() {
            return Optional.empty();
        }

        @Override
        public void click() {}
    }
}
