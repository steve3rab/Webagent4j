package io.webagent4j.locator.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatRuntimeException;

import io.webagent4j.common.ILocatorFailure;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link ILocator#tryFind()} default-method contract, in particular how it handles a
 * typed {@link ILocatorFailure} that is wrapped by an unrelated {@code RuntimeException}, per the
 * "bounded cause-chain unwrapping" decision documented on {@link ILocator#tryFind()}.
 */
class ILocatorTryFindTest {

    @Test
    void returnsTheResolvedValueWhenResolutionSucceeds() {
        Optional<String> result = locator(() -> "target").tryFind();

        assertThat(result).contains("target");
    }

    @Test
    void returnsEmptyForADirectNotFoundFailure() {
        Optional<String> result = locator(() -> throwing(new NotFound("missing"))).tryFind();

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyForANotFoundFailureWrappedByAnUnrelatedRuntimeException() {
        Optional<String> result =
                locator(() -> throwing(new RuntimeException("wrapper", new NotFound("missing"))))
                        .tryFind();

        assertThat(result).isEmpty();
    }

    @Test
    void rethrowsADirectAmbiguousFailureInsteadOfHidingIt() {
        ILocator<String> locator = locator(() -> throwing(new Ambiguous("ambiguous")));

        assertThatRuntimeException().isThrownBy(locator::tryFind).isInstanceOf(Ambiguous.class);
    }

    @Test
    void rethrowsAnAmbiguousFailureWrappedByAnUnrelatedRuntimeException() {
        RuntimeException wrapped = new RuntimeException("wrapper", new Ambiguous("ambiguous"));
        ILocator<String> locator = locator(() -> throwing(wrapped));

        assertThatRuntimeException().isThrownBy(locator::tryFind).isSameAs(wrapped);
    }

    @Test
    void rethrowsADirectBackendFailureInsteadOfReportingNotFound() {
        RuntimeException backendFailure = new IllegalStateException("backend disconnected");
        ILocator<String> locator = locator(() -> throwing(backendFailure));

        assertThatRuntimeException().isThrownBy(locator::tryFind).isSameAs(backendFailure);
    }

    @Test
    void rethrowsABackendFailureWrappedByAnUnrelatedRuntimeExceptionInsteadOfReportingNotFound() {
        RuntimeException wrapped =
                new RuntimeException("wrapper", new IllegalStateException("backend disconnected"));
        ILocator<String> locator = locator(() -> throwing(wrapped));

        assertThatRuntimeException().isThrownBy(locator::tryFind).isSameAs(wrapped);
    }

    private static String throwing(RuntimeException failure) {
        throw failure;
    }

    private static ILocator<String> locator(Supplier<String> single) {
        return new StubLocator(single);
    }

    private static final class NotFound extends RuntimeException implements ILocatorFailure {
        NotFound(String message) {
            super(message);
        }

        @Override
        public boolean isNotFound() {
            return true;
        }
    }

    private static final class Ambiguous extends RuntimeException implements ILocatorFailure {
        Ambiguous(String message) {
            super(message);
        }

        @Override
        public boolean isAmbiguous() {
            return true;
        }
    }

    /** Minimal {@link ILocator} stub exercising only the default {@code tryFind()} method. */
    private static final class StubLocator implements ILocator<String> {

        private final Supplier<String> single;

        private StubLocator(Supplier<String> single) {
            this.single = single;
        }

        @Override
        public String single() {
            return single.get();
        }

        @Override
        public String first() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> all() {
            throw new UnsupportedOperationException();
        }

        @Override
        public IElementReference<String> reference() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<String> named(String name) {
            return this;
        }

        @Override
        public ILocator<String> nameContaining(String text) {
            return this;
        }

        @Override
        public ILocator<String> fuzzyName(String name) {
            return this;
        }

        @Override
        public ILocator<String> labelled(String label) {
            return this;
        }

        @Override
        public ILocator<String> visible() {
            return this;
        }

        @Override
        public ILocator<String> hidden() {
            return this;
        }

        @Override
        public ILocator<String> enabled() {
            return this;
        }

        @Override
        public ILocator<String> disabled() {
            return this;
        }

        @Override
        public ILocator<String> editable() {
            return this;
        }

        @Override
        public ILocator<String> readonly() {
            return this;
        }

        @Override
        public ILocator<String> checked() {
            return this;
        }

        @Override
        public ILocator<String> selected() {
            return this;
        }

        @Override
        public ILocator<String> focused() {
            return this;
        }

        @Override
        public ILocator<String> inViewport() {
            return this;
        }

        @Override
        public ILocator<String> clickable() {
            return this;
        }

        @Override
        public ILocator<String> covered() {
            return this;
        }

        @Override
        public ILocator<String> timeout(Duration timeout) {
            return this;
        }

        @Override
        public ILocator<String> waitUntilVisible() {
            return this;
        }

        @Override
        public ILocator<String> stableFor(Duration duration) {
            return this;
        }
    }
}
