package io.webagent4j.action.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.webagent4j.action.ActionIdempotency;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.ActionSideEffect;
import io.webagent4j.action.ActionType;
import io.webagent4j.action.IActionBackend;
import io.webagent4j.action.IActionContext;
import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.wait.IMonotonicClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ActionExecutorMonotonicTimingTest {

    @Test
    void measuresEveryElapsedDurationWithTheInjectedMonotonicClock() {
        AdvancingClock clock = new AdvancingClock();
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = mock(IElement.class);
        ElementState actionable =
                new ElementState(
                        true, true, true, false, false, false, false, false, true, true, false,
                        true);
        when(target.role()).thenReturn(ElementRole.BUTTON);
        when(target.accessibleName()).thenReturn("Target");
        when(target.state())
                .thenAnswer(
                        invocation -> {
                            clock.advance(Duration.ofMillis(3));
                            return actionable;
                        });
        doAnswer(
                        invocation -> {
                            clock.advance(Duration.ofMillis(5));
                            return null;
                        })
                .when(backend)
                .click(target);
        ActionCommand<Void> command =
                new ActionCommand<>(
                        ActionType.CLICK,
                        ActionIdempotency.NON_IDEMPOTENT,
                        ActionSideEffect.LOCAL_PAGE_STATE,
                        () -> {
                            clock.advance(Duration.ofMillis(2));
                            return target;
                        },
                        (actionBackend, resolvedTarget) -> {
                            actionBackend.click(resolvedTarget);
                            return null;
                        },
                        null,
                        java.util.Optional.empty());

        ActionResult<Void> result =
                new ActionExecutor(clock)
                        .execute(context(backend), command, ActionExecutionConfig.defaults());

        assertThat(result.duration()).isEqualTo(Duration.ofMillis(13));
        assertThat(result.timings().total()).isEqualTo(Duration.ofMillis(13));
        assertThat(result.timings().resolution()).isEqualTo(Duration.ofMillis(5));
        assertThat(result.timings().preconditions()).isEqualTo(Duration.ofMillis(3));
        assertThat(result.timings().execution()).isEqualTo(Duration.ofMillis(5));
        assertThat(result.timings().stabilization()).isZero();
        assertThat(result.timings().verification()).isZero();
        assertThat(result.events())
                .allSatisfy(
                        event -> {
                            assertThat(event.timestamp()).isNotNull();
                            assertThat(event.duration()).isGreaterThanOrEqualTo(Duration.ZERO);
                        });
        assertThat(result.events().getLast().duration()).isEqualTo(Duration.ofMillis(13));
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

    private static final class AdvancingClock implements IMonotonicClock {

        private long nanos;

        @Override
        public long nanoTime() {
            return nanos;
        }

        void advance(Duration duration) {
            nanos += duration.toNanos();
        }
    }
}
