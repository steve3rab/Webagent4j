package io.webagent4j.browsercrawler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CancellationTokenTest {

    @Test
    void freshTokenIsNotCancelled() {
        assertThat(CancellationToken.create().isCancelled()).isFalse();
    }

    @Test
    void cancelMakesIsCancelledTrue() {
        CancellationToken token = CancellationToken.create();
        token.cancel();
        assertThat(token.isCancelled()).isTrue();
    }

    @Test
    void cancelIsIdempotent() {
        CancellationToken token = CancellationToken.create();
        token.cancel();
        token.cancel();
        assertThat(token.isCancelled()).isTrue();
    }
}
