package io.webagent4j.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LocatorFailureClassifierTest {

    @Test
    void classifiesADirectNotFoundFailure() {
        RuntimeException failure = new NotFoundFailure("missing");

        assertThat(LocatorFailureClassifier.isNotFound(failure)).isTrue();
        assertThat(LocatorFailureClassifier.isAmbiguous(failure)).isFalse();
    }

    @Test
    void classifiesADirectAmbiguousFailure() {
        RuntimeException failure = new AmbiguousFailure("ambiguous");

        assertThat(LocatorFailureClassifier.isAmbiguous(failure)).isTrue();
        assertThat(LocatorFailureClassifier.isNotFound(failure)).isFalse();
    }

    @Test
    void neverClassifiesAnUntypedFailureAsNotFoundOrAmbiguous() {
        RuntimeException backendFailure = new IllegalStateException("browser crashed");

        assertThat(LocatorFailureClassifier.isNotFound(backendFailure)).isFalse();
        assertThat(LocatorFailureClassifier.isAmbiguous(backendFailure)).isFalse();
    }

    @Test
    void findsATypedFailureWrappedByAnUnrelatedRuntimeException() {
        RuntimeException wrapped = new RuntimeException("wrapper", new NotFoundFailure("missing"));

        assertThat(LocatorFailureClassifier.isNotFound(wrapped)).isTrue();
    }

    @Test
    void findsATypedAmbiguousFailureWrappedTwiceDeep() {
        RuntimeException wrapped =
                new RuntimeException(
                        "outer", new RuntimeException("inner", new AmbiguousFailure("ambiguous")));

        assertThat(LocatorFailureClassifier.isAmbiguous(wrapped)).isTrue();
    }

    @Test
    void neverMasksABackendFailureEvenWithAnUnrelatedCauseChain() {
        RuntimeException backendFailure =
                new RuntimeException("proxy error", new IllegalStateException("connection reset"));

        assertThat(LocatorFailureClassifier.isNotFound(backendFailure)).isFalse();
        assertThat(LocatorFailureClassifier.isAmbiguous(backendFailure)).isFalse();
    }

    @Test
    void neverLoopsForeverOnACyclicCauseChain() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);

        assertThat(LocatorFailureClassifier.isNotFound(first)).isFalse();
        assertThat(LocatorFailureClassifier.isAmbiguous(first)).isFalse();
    }

    @Test
    void stopsWithinABoundedCauseDepthRatherThanScanningIndefinitely() {
        RuntimeException innermost = new NotFoundFailure("missing");
        Throwable current = innermost;
        for (int i = 0; i < 32; i++) {
            current = new RuntimeException("layer " + i, current);
        }

        assertThat(LocatorFailureClassifier.isNotFound((RuntimeException) current)).isFalse();
    }

    private static final class NotFoundFailure extends RuntimeException implements ILocatorFailure {
        NotFoundFailure(String message) {
            super(message);
        }

        @Override
        public boolean isNotFound() {
            return true;
        }
    }

    private static final class AmbiguousFailure extends RuntimeException
            implements ILocatorFailure {
        AmbiguousFailure(String message) {
            super(message);
        }

        @Override
        public boolean isAmbiguous() {
            return true;
        }
    }
}
