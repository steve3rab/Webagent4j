package io.webagent4j.crawler.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CrawlFailureTest {

    private static final URI SEED = URI.create("https://example.test/a");

    @Test
    void requestedAndFailedUrlAreTheSameWhenNoRedirectWasFollowed() {
        CrawlFailure failure =
                new CrawlFailure(
                        SEED,
                        SEED,
                        0,
                        CrawlFailureType.HTTP_SERVER_ERROR,
                        "HTTP status 503",
                        Optional.of(503),
                        Optional.empty(),
                        1,
                        Optional.empty(),
                        List.of());

        assertThat(failure.requestedUrl()).isEqualTo(failure.failedUrl());
        assertThat(failure.redirectChain()).isEmpty();
    }

    @Test
    void requestedAndFailedUrlDifferAcrossARedirectChainAndPreserveEveryHop() {
        URI b = URI.create("https://example.test/b");
        URI c = URI.create("https://example.test/c");
        RedirectHop hopOne = new RedirectHop(SEED, b, 302);
        RedirectHop hopTwo = new RedirectHop(b, c, 302);

        CrawlFailure failure =
                new CrawlFailure(
                        SEED,
                        c,
                        0,
                        CrawlFailureType.HTTP_SERVER_ERROR,
                        "HTTP status 503",
                        Optional.of(503),
                        Optional.empty(),
                        1,
                        Optional.empty(),
                        List.of(hopOne, hopTwo));

        assertThat(failure.requestedUrl()).isEqualTo(SEED);
        assertThat(failure.failedUrl()).isEqualTo(c);
        assertThat(failure.redirectChain()).containsExactly(hopOne, hopTwo);
    }

    @Test
    void allowsZeroAttemptsWhenNoRequestWasEverSent() {
        CrawlFailure failure =
                new CrawlFailure(
                        SEED,
                        SEED,
                        0,
                        CrawlFailureType.CRAWL_LIMIT_REACHED,
                        "maxPages already reached",
                        Optional.empty(),
                        Optional.empty(),
                        0,
                        Optional.empty(),
                        List.of());

        assertThat(failure.attempts()).isZero();
    }

    @Test
    void rejectsNegativeAttempts() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new CrawlFailure(
                                        SEED,
                                        SEED,
                                        0,
                                        CrawlFailureType.NETWORK,
                                        "boom",
                                        Optional.empty(),
                                        Optional.empty(),
                                        -1,
                                        Optional.empty(),
                                        List.of()));
    }

    @Test
    void defensivelyCopiesTheRedirectChainAndRejectsFurtherMutation() {
        List<RedirectHop> mutableChain = new ArrayList<>();
        mutableChain.add(new RedirectHop(SEED, SEED, 302));
        CrawlFailure failure =
                new CrawlFailure(
                        SEED,
                        SEED,
                        0,
                        CrawlFailureType.REDIRECT_LOOP,
                        "loop",
                        Optional.empty(),
                        Optional.empty(),
                        1,
                        Optional.empty(),
                        mutableChain);

        mutableChain.clear();

        assertThat(failure.redirectChain()).hasSize(1);
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> failure.redirectChain().add(new RedirectHop(SEED, SEED, 302)));
    }
}
